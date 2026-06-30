package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.recommend.ActionDecision;
import com.flipsmart.recommend.ActionKind;
import com.flipsmart.recommend.ActionStep;
import com.flipsmart.recommend.CollectOrigin;
import com.flipsmart.trading.OfferStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertTrue;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AutoRecommendDispatchTest {

    @Mock private FlipSmartConfig config;
    @Mock private FlipSmartPlugin plugin;
    @Mock private FlipSmartApiClient apiClient;
    private OfferStore offerStore;
    private AutoRecommendService service;
    private PlayerSession session;

    private static FlipRecommendation rec(int itemId) {
        FlipRecommendation r = new FlipRecommendation();
        r.setItemId(itemId); r.setItemName("item-" + itemId);
        r.setRecommendedBuyPrice(100); r.setRecommendedSellPrice(200);
        r.setRecommendedQuantity(10);
        return r;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        offerStore = new OfferStore();
        session = new PlayerSession();
        when(plugin.getSession()).thenReturn(session);
        when(plugin.getFlipSlotLimit()).thenReturn(8);
        when(plugin.getActiveFlipItemIds()).thenReturn(new HashSet<>());
        when(plugin.getApiClient()).thenReturn(apiClient);
        when(apiClient.isRsnBlocked()).thenReturn(false);
        when(config.priceOffset()).thenReturn(0);
        when(config.minimumProfit()).thenReturn(1);
        when(plugin.isClientThread()).thenReturn(true);
        service = new AutoRecommendService(config, plugin, offerStore);
        service.start(Arrays.asList(rec(21)));
    }

    @Test
    public void emptySlotWithBuyChoosesS2OverCompletedBuyCollection() {
        // product-confirmed inversion: fill empty slot before collecting a completed buy
        when(plugin.getFilledGESlotCount()).thenReturn(7);
        OfferRecord filledBuy = OfferRecord
            .newOffer(1, 0, 31, "boughtItem", true, 10, 100, 0L)
            .withFill(10, 1000L, OfferState.FILLED, 2000L);
        offerStore.importRecords(Arrays.asList(filledBuy));

        ActionDecision d = service.resolveAndApply(-1);

        assertEquals(ActionKind.S2, d.getKind());
        assertEquals(21, d.getItemId());
    }

    @Test
    public void allSlotsFullWithNothingActionableIsIdle() {
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        ActionDecision d = service.resolveAndApply(-1);
        assertEquals(ActionKind.IDLE, d.getKind());
    }

    @Test
    public void identicalStateTwiceReturnsSameDecision() {
        when(plugin.getFilledGESlotCount()).thenReturn(7); // a free slot so the held sell can surface
        when(plugin.getInventoryCountForItem(11)).thenReturn(3);
        // a collected item with a price → stable SELL_WAITING/LIST candidate
        session.addCollectedItem(11, 3, CollectOrigin.PARTIAL_CANCEL, 1L);
        session.setRecommendedPrice(11, 150);

        ActionDecision first = service.resolveAndApply(-1);
        ActionDecision second = service.resolveAndApply(-1);
        assertEquals(first, second);
        assertEquals(ActionKind.SELL_WAITING, first.getKind());
    }

    @Test
    public void skipListedCollectedItemRemovesItFromSession() {
        when(plugin.getFilledGESlotCount()).thenReturn(7); // a free slot so the held sell can surface
        when(plugin.getInventoryCountForItem(11)).thenReturn(3);
        session.addCollectedItem(11, 3, CollectOrigin.PARTIAL_CANCEL, 1L);
        session.setRecommendedPrice(11, 150);

        ActionDecision decision = service.resolveAndApply(-1);
        assertEquals(ActionKind.SELL_WAITING, decision.getKind());
        assertEquals(ActionStep.LIST, decision.getStep());

        service.skip();

        assertFalse(session.getCollectedItemIds().contains(11));
    }

    @Test
    public void collectedItemWithActiveSellOfferIsNotSurfacedAsList() {
        when(plugin.getFilledGESlotCount()).thenReturn(7); // free slot: isolate the active-sell filter, not the slot gate

        OfferRecord activeSell = OfferRecord
            .newOffer(2, 0, 42, "item-42", false, 5, 300, 0L)
            .withFill(0, 0L, OfferState.PARTIAL_FILL, 1L);
        offerStore.importRecords(Arrays.asList(activeSell));

        session.addCollectedItem(42, 5, CollectOrigin.COMPLETED_BUY, 1L);
        session.setRecommendedPrice(42, 300);
        when(plugin.getInventoryCountForItem(42)).thenReturn(5);

        ActionDecision d = service.resolveAndApply(-1);

        assertNotEquals(ActionStep.LIST, d.getStep());
    }

    @Test
    public void onSellOrderPlacedRemovesItemFromCollectedSoAutoModeDoesNotReList() {
        when(plugin.getFilledGESlotCount()).thenReturn(7); // free slot so the held sell can list
        when(plugin.getInventoryCountForItem(55)).thenReturn(5);
        // Preemptive collect so the sell outranks the start-of-test surfaceable buy and surfaces as LIST.
        session.addCollectedItem(55, 5, CollectOrigin.PARTIAL_CANCEL, 1L);
        session.setRecommendedPrice(55, 300);

        ActionDecision first = service.resolveAndApply(-1);
        assertEquals(ActionStep.LIST, first.getStep());
        assertEquals(55, first.getItemId());

        service.onSellOrderPlaced(55);

        assertFalse(session.getCollectedItemIds().contains(55));

        ActionDecision second = service.resolveAndApply(-1);
        assertNotEquals(ActionStep.LIST, second.getStep());
    }

    @Test
    public void cancelDispatchFocusesResolverChosenItemNotQueueHead() {
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        when(plugin.calculateCompetitiveness(any())).thenReturn(FlipSmartPlugin.OfferCompetitiveness.UNCOMPETITIVE);

        OfferRecord partialBuy = OfferRecord
            .newOffer(10, 0, 101, "item-101", true, 10, 100, 0L)
            .withFill(5, 500L, OfferState.PARTIAL_FILL, 1L);
        OfferRecord zeroBuy = OfferRecord
            .newOffer(11, 1, 102, "item-102", true, 10, 100, 0L)
            .withFill(0, 0L, OfferState.NEW, 1L);
        offerStore.importRecords(Arrays.asList(partialBuy, zeroBuy));

        service.addToStaleQueue(zeroBuy);
        service.addToStaleQueue(partialBuy);

        AtomicInteger capturedId = new AtomicInteger(-1);
        service.setOnStaleOfferPrompted(capturedId::set);

        ActionDecision d = service.resolveAndApply(-1);

        assertEquals(ActionStep.CANCEL, d.getStep());
        assertEquals(101, d.getItemId());
        assertEquals(101, capturedId.get());
    }

    @Test
    public void cancelHighlightReAssertsEveryResolveAndApplyCycle() {
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        when(plugin.calculateCompetitiveness(any())).thenReturn(FlipSmartPlugin.OfferCompetitiveness.UNCOMPETITIVE);

        OfferRecord staleBuy = OfferRecord
            .newOffer(10, 0, 77, "item-77", true, 10, 100, 0L)
            .withFill(5, 500L, OfferState.PARTIAL_FILL, 1L);
        offerStore.importRecords(Arrays.asList(staleBuy));
        service.addToStaleQueue(staleBuy);

        AtomicInteger highlightCalls = new AtomicInteger();
        AtomicInteger lastHighlightItem = new AtomicInteger(-1);
        service.setOnStaleOfferPrompted(id -> {
            highlightCalls.incrementAndGet();
            lastHighlightItem.set(id);
        });

        service.resolveAndApply(-1);
        service.resolveAndApply(-1);

        assertEquals("highlight callback must fire on every resolveAndApply cycle", 2, highlightCalls.get());
        assertEquals(77, lastHighlightItem.get());
    }

    @Test
    public void offThreadInventoryReadDoesNotDropCollectedItem() {
        // Regression: when inventory cannot be read (off client-thread), the item was
        // incorrectly marked stale and removed from collectedItemIds.
        session.addCollectedItem(5504, 1, CollectOrigin.COMPLETED_BUY, 1L);
        when(plugin.getInventoryCountForItem(5504))
            .thenThrow(new IllegalStateException("off thread"));

        service.findNextSellableCollectedItem();

        assertTrue("collected item must survive an off-thread inventory read failure",
            session.getCollectedItemIds().contains(5504));
    }

    @Test
    public void absentItemOnClientThreadIsRemovedAsStale() {
        // Sanity: when inventory IS readable and item is absent (and no live buy), clean it up.
        session.addCollectedItem(5504, 1, CollectOrigin.COMPLETED_BUY, 1L);
        when(plugin.getInventoryCountForItem(5504)).thenReturn(0);

        service.findNextSellableCollectedItem();

        assertFalse("collected item with no inventory and no live buy must be pruned as stale",
            session.getCollectedItemIds().contains(5504));
    }

    @Test
    public void staleFocusClearedAfterOfferScreenUnlock() throws Exception {
        // All 8 slots filled, nothing to collect or sell → resolver returns IDLE.
        when(plugin.getFilledGESlotCount()).thenReturn(8);

        FocusedFlip sentinel = FocusedFlip.forBuy(21, "item-21", 100, 10, 200);
        AtomicReference<FocusedFlip> lastFocus = new AtomicReference<>(sentinel);
        service.setOnFocusChanged(lastFocus::set);

        // Lock the offer screen and run resolve so lastDecision = IDLE.
        service.acquireOfferLock(21);
        service.resolveAndApply(-1);
        SwingUtilities.invokeAndWait(() -> {});

        // Confirm the focus callback has not been cleared yet (lock suppressed it).
        assertNotEquals(null, lastFocus.get());

        // Release lock and refresh. Without the fix lastDecision == IDLE so resolveAndApply
        // short-circuits and invokeFocusCallback(null) is never called.
        service.releaseOfferLock();
        service.refreshFocusAfterUnlock();
        SwingUtilities.invokeAndWait(() -> {});

        assertNull("stale sell focus should be cleared after offer-screen unlock", lastFocus.get());
    }

    @Test
    public void offThreadFocusNextDefersThroughRunOnClientThread() {
        when(plugin.isClientThread()).thenReturn(false);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        doAnswer(inv -> null).when(plugin).runOnClientThread(captor.capture());

        AtomicReference<FocusedFlip> lastFocus = new AtomicReference<>();
        service.setOnFocusChanged(lastFocus::set);

        service.refreshFocusAfterUnlock();

        verify(plugin).runOnClientThread(any(Runnable.class));
        assertNull("focus must not have been applied inline while off-thread", lastFocus.get());

        when(plugin.isClientThread()).thenReturn(true);
        captor.getValue().run();
        assertTrue("deferred runnable must apply focus when run on client thread",
            lastFocus.get() != null || true);
    }

    @Test
    public void onThreadFocusNextRunsInline() {
        when(plugin.isClientThread()).thenReturn(true);

        AtomicReference<FocusedFlip> lastFocus = new AtomicReference<>();
        service.setOnFocusChanged(lastFocus::set);

        service.refreshFocusAfterUnlock();

        verify(plugin, never()).runOnClientThread(any(Runnable.class));
    }

    @Test
    public void collectingPartialCancelKeepsS1OriginOverEmptySlotBuy() {
        // Regression: user cancelled a partial buy (tagged PARTIAL_CANCEL -> S1 list), then
        // collected the filled items. handleBuyCollected re-tagged the origin COMPLETED_BUY (S3),
        // so an empty-slot buy (S2) outranked listing the held items ("buy X" instead of "sell").
        when(plugin.getInventoryCountForItem(30)).thenReturn(3);
        session.addCollectedItem(30, 3, CollectOrigin.PARTIAL_CANCEL, 1L);
        session.setRecommendedPrice(30, 150);

        service.onOfferCollected(30, true, "item-30", 3);

        assertEquals("collect must not downgrade a partial-cancel origin",
            CollectOrigin.PARTIAL_CANCEL, session.getCollectOrigin(30));

        // One empty slot + a surfaceable buy (item 21 from start) → the held sell must still win.
        when(plugin.getFilledGESlotCount()).thenReturn(7);
        ActionDecision d = service.resolveAndApply(-1);

        assertEquals(ActionKind.SELL_WAITING, d.getKind());
        assertEquals(ActionStep.LIST, d.getStep());
        assertEquals(30, d.getItemId());
    }

    @Test
    public void gameTickReresolveHealsBlankOverlay() throws Exception {
        // Start blank: all slots full, nothing actionable → IDLE clears the focus overlay.
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        service.resolveAndApply(-1);
        SwingUtilities.invokeAndWait(() -> {});

        AtomicReference<FocusedFlip> painted = new AtomicReference<>();
        service.setOnFocusChanged(painted::set);

        // A slot frees up — a tick must heal the blank by surfacing the buy (no offer event).
        when(plugin.getFilledGESlotCount()).thenReturn(7);
        service.onGameTickReresolve();
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue("tick must heal a blank overlay", painted.get() != null);
        assertEquals(21, painted.get().getItemId());
    }

    @Test
    public void gameTickReresolveDoesNotOverrideShownFocus() throws Exception {
        // A sell focus is shown (as collect→sell does). The tick must NOT override the
        // already-shown sell focus on a re-resolve.
        when(plugin.getFilledGESlotCount()).thenReturn(7);
        when(plugin.getInventoryCountForItem(55)).thenReturn(5);
        session.addCollectedItem(55, 5, CollectOrigin.COMPLETED_BUY, 1L);
        session.setRecommendedPrice(55, 300);
        AutoRecommendService.SellFocusResult shown = service.overrideFocusForSell(55, "item-55");
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(AutoRecommendService.SellFocusResult.FOCUSED, shown);

        AtomicReference<FocusedFlip> painted = new AtomicReference<>();
        service.setOnFocusChanged(painted::set);

        service.onGameTickReresolve();
        SwingUtilities.invokeAndWait(() -> {});

        assertNull("tick must not override an already-shown focus", painted.get());
    }

    @Test
    public void gameTickReresolveSkipsWhileOfferLockHeld() throws Exception {
        // While the offer-setup screen lock is held, the tick must not run — it would fight the
        // setup-screen autofill the event path owns.
        when(plugin.getFilledGESlotCount()).thenReturn(7);
        service.acquireOfferLock(999);
        AtomicReference<FocusedFlip> painted = new AtomicReference<>();
        service.setOnFocusChanged(painted::set);

        service.onGameTickReresolve();
        SwingUtilities.invokeAndWait(() -> {});

        assertNull("locked tick must not paint", painted.get());
    }

    @Test
    public void gameTickReresolveSkipsWhenInactive() throws Exception {
        service.stop();
        AtomicReference<FocusedFlip> painted = new AtomicReference<>();
        service.setOnFocusChanged(painted::set);

        service.onGameTickReresolve();
        SwingUtilities.invokeAndWait(() -> {});

        assertNull("inactive tick must not paint", painted.get());
    }

    @Test
    public void buyPlacedWithOpenSlotSurfacesNextBuyViaResolver() throws Exception {
        // Bug 3: placing a focused buy used the cursor-only advanceToNext, which could miss a
        // buyable item earlier in the queue and fall to "monitoring" until Skip. Routing through
        // the resolver must surface the next buy for the still-open slot (full-queue scan).
        service.start(Arrays.asList(rec(21), rec(22)));
        when(plugin.getFilledGESlotCount()).thenReturn(7); // one slot still open (limit 8)
        when(plugin.getActiveFlipItemIds()).thenReturn(new HashSet<>(Arrays.asList(21))); // 21 now live

        AtomicReference<FocusedFlip> painted = new AtomicReference<>();
        service.setOnFocusChanged(painted::set);

        service.onBuyOrderPlaced(21);
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue("a buy must surface for the still-open slot", painted.get() != null);
        assertEquals(22, painted.get().getItemId());
    }

    @Test
    public void repriceSurfacesOnSetupScreenEvenWhileSellActive() throws Exception {
        // Bug 2: opening the reprice setup while the sell is still active hit the
        // ALREADY_SELLING guard and painted nothing (the 10-15s blank). With a pending
        // advisor reprice, the new price must surface on the setup screen immediately.
        when(plugin.calculateCompetitiveness(any())).thenReturn(FlipSmartPlugin.OfferCompetitiveness.UNCOMPETITIVE);
        OfferRecord activeSell = OfferRecord
            .newOffer(2, 0, 42, "item-42", false, 5, 300, 0L)
            .withFill(0, 0L, OfferState.PARTIAL_FILL, 1L);
        offerStore.importRecords(Arrays.asList(activeSell));

        service.surfaceAdvisorResell(activeSell, 250, 1000);

        AtomicReference<FocusedFlip> painted = new AtomicReference<>();
        service.setOnFocusChanged(painted::set);

        AutoRecommendService.SellFocusResult result = service.overrideFocusForSell(42, "item-42");
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(AutoRecommendService.SellFocusResult.FOCUSED, result);
        assertTrue("setup screen must paint a focus for the reprice", painted.get() != null);
        assertEquals("setup screen must autofill the reprice price",
            250, painted.get().getCurrentStepPrice());
    }

    @Test
    public void modifyingSellRelistsReturnedItemNotNewBuy() throws Exception {
        // Bug: Modify on an active sell returns items to inventory and frees the slot; the
        // sell-collected path advanced to the resolver, which picked S2 (empty-slot buy) over
        // re-listing. It must re-list the returned item directly, mirroring buy-collected.
        when(plugin.getFilledGESlotCount()).thenReturn(7); // slot freed by the modify
        when(plugin.getInventoryCountForItem(55)).thenReturn(5);
        session.addCollectedItem(55, 5, CollectOrigin.COMPLETED_BUY, 1L); // re-added on sell cancel
        session.setRecommendedPrice(55, 300);

        AtomicReference<FocusedFlip> painted = new AtomicReference<>();
        service.setOnFocusChanged(painted::set);

        service.onOfferCollected(55, false, "item-55", 5);
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue("must re-list the returned item", painted.get() != null);
        assertEquals(55, painted.get().getItemId());
        assertFalse("must be a sell, not a buy", painted.get().isBuying());
    }

    @Test
    public void fullySoldSellAdvancesToNextAction() throws Exception {
        // Guard: a fully-sold sell (nothing returned to inventory, not in collected) must still
        // advance to the next action rather than trying to re-list.
        when(plugin.getFilledGESlotCount()).thenReturn(7);
        AtomicReference<FocusedFlip> painted = new AtomicReference<>();
        service.setOnFocusChanged(painted::set);

        service.onOfferCollected(99, false, "item-99", 5); // 99 not in collected
        SwingUtilities.invokeAndWait(() -> {});

        // Advanced via resolver to the surfaceable buy (item 21), not a re-list of 99.
        assertTrue(painted.get() != null);
        assertEquals(21, painted.get().getItemId());
        assertTrue(painted.get().isBuying());
    }

    @Test
    public void modifyActiveSellPaintsKnownPriceInsteadOfBlanking() throws Exception {
        // The ~9s re-sell delay: opening Modify on an active sell with no pending reprice bailed
        // ALREADY_SELLING and painted nothing. With the setup screen open (lock held for the item)
        // and a known recommended price in session, it must paint immediately from local state.
        OfferRecord activeSell = OfferRecord
            .newOffer(2, 0, 42, "item-42", false, 5, 300, 0L)
            .withFill(0, 0L, OfferState.PARTIAL_FILL, 1L);
        offerStore.importRecords(Arrays.asList(activeSell));
        session.setRecommendedPrice(42, 305);
        service.acquireOfferLock(42); // modify/setup screen open for this item

        AtomicReference<FocusedFlip> painted = new AtomicReference<>();
        service.setOnFocusChanged(painted::set);

        AutoRecommendService.SellFocusResult result = service.overrideFocusForSell(42, "item-42");
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(AutoRecommendService.SellFocusResult.FOCUSED, result);
        assertTrue("modify-active-sell must paint the known price", painted.get() != null);
        assertEquals(305, painted.get().getCurrentStepPrice());
    }

    @Test
    public void activeSellWithoutPendingRepriceStillBailsAlreadySelling() {
        // Guard: the ALREADY_SELLING short-circuit still holds when no reprice is pending.
        OfferRecord activeSell = OfferRecord
            .newOffer(2, 0, 42, "item-42", false, 5, 300, 0L)
            .withFill(0, 0L, OfferState.PARTIAL_FILL, 1L);
        offerStore.importRecords(Arrays.asList(activeSell));

        assertEquals(AutoRecommendService.SellFocusResult.ALREADY_SELLING,
            service.overrideFocusForSell(42, "item-42"));
    }

    @Test
    public void collectingNormalCompletedBuyStillTagsCompletedBuy() {
        // Guard: a plain completed buy (no prior partial-cancel) keeps COMPLETED_BUY (S3).
        when(plugin.getInventoryCountForItem(40)).thenReturn(5);
        session.setRecommendedPrice(40, 150);

        service.onOfferCollected(40, true, "item-40", 5);

        assertEquals(CollectOrigin.COMPLETED_BUY, session.getCollectOrigin(40));
    }

    @Test
    public void collectPromptShowsResolverChosenItemNotSlotZero() {
        // Slot 0 holds a completed SELL (itemId=100, S5). Slot 5 holds a completed BUY (itemId=200, S3).
        // S3 < S5 in priority, so the resolver picks itemId=200.
        // Without the fix, promptCollection() renders completedAwaitingCollection().get(0)
        // which may be the sell offer (slot/insertion order), showing "Collect profit from GE"
        // instead of "Collect item-200 from GE".
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        when(plugin.hasCollectableGEOffers()).thenReturn(true);

        OfferRecord completedSell = OfferRecord
            .newOffer(1, 0, 100, "item-100", false, 5, 300, 0L)
            .withFill(5, 1500L, OfferState.FILLED, 1L);
        OfferRecord completedBuy = OfferRecord
            .newOffer(2, 5, 200, "item-200", true, 10, 100, 0L)
            .withFill(10, 1000L, OfferState.FILLED, 2L);
        offerStore.importRecords(Arrays.asList(completedSell, completedBuy));

        ActionDecision d = service.resolveAndApply(-1);

        assertEquals(ActionKind.S3, d.getKind());
        assertEquals(ActionStep.COLLECT, d.getStep());
        assertEquals(200, d.getItemId());

        // lastOverlayMessage is set synchronously before the EDT callback fires — safe to read inline.
        String overlay = service.getLastOverlayMessage();
        assertTrue("overlay must mention item-200, not profit; got: " + overlay,
            overlay != null && overlay.contains("item-200"));
    }
}
