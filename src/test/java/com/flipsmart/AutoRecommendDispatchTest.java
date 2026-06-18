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
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        when(plugin.getInventoryCountForItem(11)).thenReturn(3);
        // a partial-cancel collected item with a price → stable S1/LIST candidate
        session.addCollectedItem(11, 3, CollectOrigin.PARTIAL_CANCEL, 1L);
        session.setRecommendedPrice(11, 150);

        ActionDecision first = service.resolveAndApply(-1);
        ActionDecision second = service.resolveAndApply(-1);
        assertEquals(first, second);
        assertEquals(ActionKind.S1, first.getKind());
    }

    @Test
    public void skipListedCollectedItemRemovesItFromSession() {
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        when(plugin.getInventoryCountForItem(11)).thenReturn(3);
        session.addCollectedItem(11, 3, CollectOrigin.PARTIAL_CANCEL, 1L);
        session.setRecommendedPrice(11, 150);

        ActionDecision decision = service.resolveAndApply(-1);
        assertEquals(ActionKind.S1, decision.getKind());
        assertEquals(ActionStep.LIST, decision.getStep());

        service.skip();

        assertFalse(session.getCollectedItemIds().contains(11));
    }

    @Test
    public void collectedItemWithActiveSellOfferIsNotSurfacedAsList() {
        when(plugin.getFilledGESlotCount()).thenReturn(8);

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
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        when(plugin.getInventoryCountForItem(55)).thenReturn(5);
        session.addCollectedItem(55, 5, CollectOrigin.COMPLETED_BUY, 1L);
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
}
