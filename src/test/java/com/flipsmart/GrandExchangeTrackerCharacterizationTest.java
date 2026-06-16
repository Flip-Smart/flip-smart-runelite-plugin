package com.flipsmart;

import com.flipsmart.api.dto.TransactionRequest;
import com.flipsmart.domain.offer.TrackedOffer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.game.ItemManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization net for {@link GrandExchangeTracker#handleOfferChanged}.
 *
 * <p>These tests pin the CURRENT observable behavior of the live GE offer-handling
 * path so that the #735 refactor (routing offers through OfferStore +
 * OfferStateMachine) can be proven behavior-preserving. They drive the real
 * tracker with sequences of GE events and assert on the observable outputs:
 * Mockito {@code verify(...)} counts/arguments on {@link FlipSmartApiClient} and
 * state on a REAL {@link PlayerSession}.
 *
 * <p>Per the spirit of characterization testing, these encode what the code does
 * TODAY, including a couple of surprising-but-real behaviors that are flagged
 * inline. They are NOT specifications of desired behavior.
 */
public class GrandExchangeTrackerCharacterizationTest
{
	private static final int ITEM_A = 4151; // Abyssal whip
	private static final String NAME_A = "Abyssal whip";
	private static final int ITEM_B = 1305; // Dragon longsword
	private static final String NAME_B = "Dragon longsword";
	private static final String RSN = "TestPlayer";
	private static final int SLOT = 0;

	private PlayerSession session;            // real instance — assert on its state
	private FlipSmartApiClient apiClient;     // mock — verify transaction recording
	private ActiveFlipTracker activeFlipTracker;
	private ItemManager itemManager;
	private TradeActivityLog tradeActivityLog;

	private GrandExchangeTracker tracker;

	@Before
	public void setUp()
	{
		session = new PlayerSession();
		session.setRsn(RSN);

		apiClient = mock(FlipSmartApiClient.class);
		activeFlipTracker = mock(ActiveFlipTracker.class);
		itemManager = mock(ItemManager.class);
		tradeActivityLog = mock(TradeActivityLog.class);

		// Stub the async API surface the handler touches so chained calls don't NPE.
		when(apiClient.recordTransactionAsync(any(TransactionRequest.class)))
			.thenReturn(CompletableFuture.completedFuture(null));
		when(apiClient.dismissActiveFlipAsync(anyInt(), any()))
			.thenReturn(CompletableFuture.completedFuture(true));
		when(apiClient.syncActiveFlipAsync(anyInt(), any(), anyInt(), anyInt(), anyInt(), any()))
			.thenReturn(CompletableFuture.completedFuture(true));
		when(apiClient.markActiveFlipSellingAsync(anyInt(), any()))
			.thenReturn(CompletableFuture.completedFuture(true));

		// Default: no items in inventory (offline-fill correction off unless a test opts in).
		when(activeFlipTracker.getInventoryCountForItem(anyInt())).thenReturn(0);

		// REAL constructor argument order: (session, apiClient, activeFlipTracker, itemManager, tradeActivityLog).
		tracker = new GrandExchangeTracker(
			session, apiClient, activeFlipTracker, itemManager, tradeActivityLog);

		// AutoRecommendService / ManualAdjustmentTracker are left unset (null) — the
		// tracker treats that as "auto-recommend inactive, no manual adjustment", which
		// is the simplest observable baseline for transaction-recording characterization.
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/**
	 * Build an OfferContext through the real Lombok builder. {@code isBuy} is derived
	 * from the GE offer state, matching how the plugin populates the field in
	 * production (buy-side states map to isBuy=true).
	 */
	private GrandExchangeTracker.OfferContext ctx(
		int slot, GrandExchangeOfferState state, int itemId, String itemName,
		int totalQuantity, int price, int quantitySold, int spent)
	{
		return GrandExchangeTracker.OfferContext.builder()
			.slot(slot)
			.state(state)
			.itemId(itemId)
			.itemName(itemName)
			.totalQuantity(totalQuantity)
			.price(price)
			.quantitySold(quantitySold)
			.spent(spent)
			.isBuy(isBuy(state))
			.build();
	}

	private static boolean isBuy(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
	}

	private ArgumentCaptor<TransactionRequest> captureFills()
	{
		ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
		verify(apiClient, times(expectedRecordCount())).recordTransactionAsync(captor.capture());
		return captor;
	}

	// expectedRecordCount is set per-test before capture so the captor verify is exact.
	private int expectedRecords = -1;

	private int expectedRecordCount()
	{
		return expectedRecords;
	}

	// ==================================================================
	// Scenario 1 — Happy buy: placed -> partial(+4) -> complete(10).
	// Each fill recorded exactly once; no double count.
	// ==================================================================
	@Test
	public void scenario1_happyBuy_eachFillRecordedOnce_noDoubleCount()
	{
		// Placement: BUYING, 0 sold. Records a "new buy order" transaction (qty 0).
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 0, 0));
		// Partial fill: 4 sold, spent 400.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 4, 400));
		// Completion: 10 sold, spent 1000 (BOUGHT).
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BOUGHT, ITEM_A, NAME_A, 10, 100, 10, 1000));

		// Observable: three recordTransactionAsync calls — the placement (qty 0) plus
		// the two fill deltas (+4, then +6). The deltas must NOT double-count the
		// cumulative total.
		expectedRecords = 3;
		ArgumentCaptor<TransactionRequest> captor = captureFills();
		java.util.List<TransactionRequest> reqs = captor.getAllValues();

		assertEquals("placement records quantity 0", 0, reqs.get(0).quantity);
		assertTrue("placement is a buy", reqs.get(0).isBuy);

		assertEquals("first fill delta is +4", 4, reqs.get(1).quantity);
		assertEquals("first fill price-per-item = 400/4", 100, reqs.get(1).pricePerItem);

		assertEquals("second fill delta is +6 (10 cumulative - 4 prior), not 10", 6, reqs.get(2).quantity);
		assertEquals("second fill price-per-item = (1000-400)/6", 100, reqs.get(2).pricePerItem);

		int totalRecorded = reqs.stream().mapToInt(r -> r.quantity).sum();
		assertEquals("total recorded quantity equals the 10 actually filled", 10, totalRecorded);
	}

	// ==================================================================
	// Scenario 2 — Cancel with 0 fills -> no fill/transaction recorded;
	// active flip dismissed.
	// ==================================================================
	@Test
	public void scenario2_cancelZeroFills_noTransaction_activeFlipDismissed()
	{
		// Place a buy, then cancel before any fill.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 0, 0));
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.CANCELLED_BUY, ITEM_A, NAME_A, 10, 100, 0, 0));

		// Observable: only the placement transaction (qty 0); NO fill transaction
		// recorded for the zero-fill cancel.
		expectedRecords = 1;
		ArgumentCaptor<TransactionRequest> captor = captureFills();
		assertEquals("only the placement transaction is recorded", 0, captor.getValue().quantity);

		// Observable: a zero-fill BUY cancel dismisses the active flip on the backend.
		verify(apiClient, times(1)).dismissActiveFlipAsync(eq(ITEM_A), any());

		// The slot's tracked offer is removed (zero-fill cancel path).
		assertEquals("tracked offer removed on zero-fill cancel", null, session.getTrackedOffer(SLOT));
		// Recommended price is cleaned up on a zero-fill buy cancel.
		assertEquals(null, session.getRecommendedPrice(ITEM_A));
	}

	// ==================================================================
	// Scenario 3 — Partial buy -> cancel(partial) -> collect.
	// Exactly the partial qty handled; item ends up collected.
	// ==================================================================
	@Test
	public void scenario3_partialBuyThenCancelThenCollect_partialQtyCollected()
	{
		// Place, partial fill of 4, then cancel with 4 filled.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 0, 0));
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 4, 400));
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.CANCELLED_BUY, ITEM_A, NAME_A, 10, 100, 4, 400));

		// On the partial-fill cancel, syncCancelledPartialBuy adds the 4 filled items
		// to the collected set immediately. The tracked offer is kept (marked completed)
		// so the later collect routes through the resell flow.
		assertEquals("4 partial-buy items added to collected on cancel",
			4, session.getCollectedQuantity(ITEM_A));
		assertTrue(session.getCollectedItemIds().contains(ITEM_A));

		// Now the player collects: EMPTY event on the same slot. previousQuantitySold==4,
		// so handleCollectedBuyOffer runs and adds the collected qty again.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.EMPTY, ITEM_A, NAME_A, 10, 100, 0, 0));

		// CHARACTERIZATION NOTE: the item remains collected after collect (this is the
		// observable end state we are pinning). Both the cancel path and the collect path
		// call addCollectedItem(itemId, 4), but PlayerSession.addCollectedItem uses put()
		// (overwrite), NOT accumulation — so the final collected quantity is 4, the
		// partial fill, NOT 8. Pinning the overwrite semantics: a refactor that
		// accidentally switched to accumulation would double the collected qty here.
		assertEquals("item is still collected after collect", true,
			session.getCollectedItemIds().contains(ITEM_A));
		assertEquals("current behavior: collected qty stays at the partial 4 (put overwrites, no accumulation)",
			4, session.getCollectedQuantity(ITEM_A));

		// Transactions recorded: placement (0) + partial fill (+4) + final cancellation
		// fill. recordFinalCancellationFill only records when quantitySold >
		// previousQuantitySold; here previousQuantitySold==4 and quantitySold==4, so it
		// records NOTHING extra at cancel time. So only 2 records total.
		expectedRecords = 2;
		ArgumentCaptor<TransactionRequest> captor = captureFills();
		java.util.List<TransactionRequest> reqs = captor.getAllValues();
		assertEquals("placement qty 0", 0, reqs.get(0).quantity);
		assertEquals("partial fill delta +4", 4, reqs.get(1).quantity);
		int totalRecorded = reqs.stream().mapToInt(r -> r.quantity).sum();
		assertEquals("exactly the partial 4 was recorded as a fill", 4, totalRecorded);
	}

	// ==================================================================
	// Scenario 4 — Offline fill: first event after login shows quantitySold
	// greater than what we tracked -> corrected quantity recorded once.
	// ==================================================================
	@Test
	public void scenario4_offlineFill_firstEventShowsHigherSold_correctedQtyRecordedOnce()
	{
		// No prior tracked offer for this slot (login burst). First live event shows the
		// offer already 7/10 filled with spent 700 — the fills happened offline.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 7, 700));

		// Observable: with no baseline (previousOffer == null), calculateNewFillQuantity
		// returns the full quantitySold (7), so a single transaction for 7 is recorded.
		expectedRecords = 1;
		ArgumentCaptor<TransactionRequest> captor = captureFills();
		TransactionRequest req = captor.getValue();
		assertEquals("corrected offline fill quantity recorded once as 7", 7, req.quantity);
		assertEquals("price-per-item = 700/7", 100, req.pricePerItem);
		assertTrue(req.isBuy);

		// A second identical event (same cumulative 7) must NOT re-record — the tracked
		// offer now has previousQuantitySold==7, so the delta is 0.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 7, 700));
		verify(apiClient, times(1)).recordTransactionAsync(any(TransactionRequest.class));
	}

	// ==================================================================
	// Scenario 5 — Slot reuse: item A placed+bought+collected (slot freed),
	// then item B placed in the SAME slot -> B handled cleanly with no
	// mis-record attributing A's baseline to B.
	// ==================================================================
	@Test
	public void scenario5_slotReuse_itemBDoesNotInheritItemABaseline()
	{
		// Item A full lifecycle in slot 0.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 5, 100, 0, 0));
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BOUGHT, ITEM_A, NAME_A, 5, 100, 5, 500));
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.EMPTY, ITEM_A, NAME_A, 5, 100, 0, 0));

		// Slot is now free (EMPTY removed the tracked offer).
		assertEquals("slot freed after collect of item A", null, session.getTrackedOffer(SLOT));

		// Item B placed in the SAME slot, then a fill.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_B, NAME_B, 3, 200, 0, 0));
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BOUGHT, ITEM_B, NAME_B, 3, 200, 3, 600));

		ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
		verify(apiClient, times(4)).recordTransactionAsync(captor.capture());
		java.util.List<TransactionRequest> reqs = captor.getAllValues();

		// A: placement(0) + fill(+5). B: placement(0) + fill(+3).
		assertEquals("A placement", ITEM_A, reqs.get(0).itemId);
		assertEquals(0, reqs.get(0).quantity);
		assertEquals("A fill delta +5", ITEM_A, reqs.get(1).itemId);
		assertEquals(5, reqs.get(1).quantity);

		assertEquals("B placement", ITEM_B, reqs.get(2).itemId);
		assertEquals(0, reqs.get(2).quantity);

		// The critical assertion: B's fill records delta of 3 at B's own price (200),
		// NOT polluted by item A's baseline. createWithPreservedTimestamps drops the
		// existing offer when itemId differs, so there is no cross-item baseline.
		TransactionRequest bFill = reqs.get(3);
		assertEquals("B fill is for item B", ITEM_B, bFill.itemId);
		assertEquals("B fill delta is its own 3, not 3+A's 5", 3, bFill.quantity);
		assertEquals("B fill price-per-item = 600/3 = 200 (A's 100 not leaked)", 200, bFill.pricePerItem);
	}

	// ==================================================================
	// Scenario 6 — Reload mid-offer: a persisted offer preloaded into the
	// session, then a live fill event -> only the DELTA recorded, not the
	// cumulative total.
	// ==================================================================
	@Test
	public void scenario6_reloadMidOffer_preloadedOffer_onlyDeltaRecorded()
	{
		// Simulate the plugin's preload: a persisted offer with 6 already filled is put
		// into the session BEFORE any live event (this is the real putTrackedOffer API
		// that OfflineSyncService.preloadPersistedOffers uses).
		TrackedOffer preloaded = new TrackedOffer(ITEM_A, NAME_A, true, 10, 100, 6);
		preloaded.setPreviousSpent(600L);
		session.putTrackedOffer(SLOT, preloaded);

		// Live event arrives showing cumulative 9/10 filled, spent 900.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 9, 900));

		// Observable: ONLY the delta (9 - 6 = 3) is recorded, not the cumulative 9.
		expectedRecords = 1;
		ArgumentCaptor<TransactionRequest> captor = captureFills();
		TransactionRequest req = captor.getValue();
		assertEquals("only the delta of 3 recorded after reload, not cumulative 9", 3, req.quantity);
		assertEquals("incremental price-per-item = (900-600)/3", 100, req.pricePerItem);
	}

	// ==================================================================
	// Scenario 7 — World-hop during a partial fill -> no double count after
	// the re-login burst re-delivers the same cumulative state.
	// ==================================================================
	@Test
	public void scenario7_worldHopDuringPartialFill_noDoubleCount()
	{
		// Place + partial fill of 4 (pre-hop).
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 0, 0));
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 4, 400));

		// World-hop re-delivers the SAME cumulative state (4/10) on the same slot —
		// the tracked offer survived the hop in-session, so the delta is 0.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 4, 400));

		// Then real progress to 7/10 after the hop.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 7, 700));

		ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
		verify(apiClient, times(3)).recordTransactionAsync(captor.capture());
		java.util.List<TransactionRequest> reqs = captor.getAllValues();

		// placement(0) + fill(+4) + fill(+3). The re-delivered 4/10 event recorded
		// nothing (delta 0).
		assertEquals("placement", 0, reqs.get(0).quantity);
		assertEquals("first real fill +4", 4, reqs.get(1).quantity);
		assertEquals("post-hop fill is the +3 delta, not a re-counted 7", 3, reqs.get(2).quantity);

		int totalFilled = reqs.stream().mapToInt(r -> r.quantity).sum();
		assertEquals("total recorded equals 7 actually filled, no double count", 7, totalFilled);
	}

	// ==================================================================
	// Scenario 8 — Duplicate cancellation event (same slot cancelled twice)
	// -> the second event is a no-op (RuneLite #12037 guard).
	//
	// CHARACTERIZATION NOTE: the #12037 guard at the top of handleCancelledOffer
	// only fires when the tracked offer for the slot was REMOVED by the first
	// cancel. A partial-BUY cancel KEEPS the offer (marked completed) so the
	// collect flow can fire — so the guard does NOT protect partial cancels;
	// only zero-fill cancels (and sell cancels with nothing remaining) leave the
	// slot empty and are therefore truly idempotent. We pin the guard with a
	// zero-fill buy cancel, the case it actually covers.
	// ==================================================================
	@Test
	public void scenario8_duplicateZeroFillCancellation_secondEventIsNoOp()
	{
		// Place a buy, then cancel before any fill (zero-fill cancel removes the offer).
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 10, 100, 0, 0));
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.CANCELLED_BUY, ITEM_A, NAME_A, 10, 100, 0, 0));

		int recordsAfterFirstCancel = mockingDetailsRecordCount();
		int dismissAfterFirstCancel = mockingDetailsDismissCount();
		assertEquals("first zero-fill cancel removed the tracked offer",
			null, session.getTrackedOffer(SLOT));

		// Duplicate cancellation event for the SAME (now-empty) slot. The #12037 guard
		// short-circuits because getTrackedOffer(slot) == null.
		tracker.handleOfferChanged(ctx(SLOT, GrandExchangeOfferState.CANCELLED_BUY, ITEM_A, NAME_A, 10, 100, 0, 0));

		assertEquals("duplicate cancellation records no new transaction",
			recordsAfterFirstCancel, mockingDetailsRecordCount());
		assertEquals("duplicate cancellation issues no new active-flip dismiss",
			dismissAfterFirstCancel, mockingDetailsDismissCount());
		assertEquals("slot stays empty after the duplicate", null, session.getTrackedOffer(SLOT));
	}

	private int mockingDetailsDismissCount()
	{
		return (int) org.mockito.Mockito.mockingDetails(apiClient).getInvocations().stream()
			.filter(i -> i.getMethod().getName().equals("dismissActiveFlipAsync"))
			.count();
	}

	private int mockingDetailsRecordCount()
	{
		return (int) org.mockito.Mockito.mockingDetails(apiClient).getInvocations().stream()
			.filter(i -> i.getMethod().getName().equals("recordTransactionAsync"))
			.count();
	}
}
