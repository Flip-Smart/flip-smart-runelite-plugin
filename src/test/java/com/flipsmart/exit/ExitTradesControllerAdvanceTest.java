package com.flipsmart.exit;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExitTradesControllerAdvanceTest
{
	private OfferStore store;
	private ExitTradesController controller;

	@Before
	public void setUp()
	{
		store = new OfferStore();
		controller = new ExitTradesController(store);
		controller.setBuyBasisSupplier(itemId -> 1000);
	}

	private void seed(int slot, int itemId, boolean buy)
	{
		java.util.List<OfferRecord> existing = new java.util.ArrayList<>(store.export());
		existing.add(OfferRecord.newOffer(2000L + slot, slot, itemId, "x", buy, 10, 100, 1L));
		store.importRecords(existing);
	}

	private OfferRecord rec(int slot, int itemId, boolean buy, int filled, OfferState state)
	{
		OfferRecord r = OfferRecord.newOffer(9000L + slot, slot, itemId, "x", buy, 10, 100, 1L);
		return r.withFill(filled, 0L, state, 2L);
	}

	@Test
	public void sellRelistMarksDone()
	{
		seed(0, 561, false);
		controller.start(ExitTradesMode.INSTANT);
		controller.onOfferChanged(rec(0, 561, false, 0, OfferState.NEW));
		assertEquals(ExitPhase.DONE, controller.getTargets().get(0).getPhase());
		assertEquals(1, controller.actedCount());
	}

	@Test
	public void buyCancelThenCollectThenResoldGoesDone()
	{
		seed(2, 4151, true);
		controller.start(ExitTradesMode.INSTANT);
		// Cancel a partially-filled buy: items still sit in the slot awaiting collection.
		controller.onOfferChanged(rec(2, 4151, true, 3, OfferState.CANCELLED_PARTIAL));
		assertEquals(ExitPhase.AWAITING_COLLECT, controller.getTargets().get(0).getPhase());
		assertEquals(1, controller.actedCount());

		// Collect the bought stock into inventory.
		controller.onOfferChanged(rec(2, 4151, true, 3, OfferState.COLLECTED));
		assertEquals(ExitPhase.CANCELLED_HOLDING, controller.getTargets().get(0).getPhase());

		// Re-list the held stock as a sell.
		controller.onOfferChanged(rec(2, 4151, false, 0, OfferState.NEW));
		assertEquals(ExitPhase.DONE, controller.getTargets().get(0).getPhase());
	}

	@Test
	public void fullyFilledBuyAwaitsCollectThenHolds()
	{
		seed(3, 4151, true);
		controller.start(ExitTradesMode.INSTANT);
		controller.onOfferChanged(rec(3, 4151, true, 10, OfferState.FILLED));
		assertEquals(ExitPhase.AWAITING_COLLECT, controller.getTargets().get(0).getPhase());
		controller.onOfferChanged(rec(3, 4151, true, 10, OfferState.COLLECTED));
		assertEquals(ExitPhase.CANCELLED_HOLDING, controller.getTargets().get(0).getPhase());
	}

	@Test
	public void soldSellAwaitsCollectThenDone()
	{
		seed(0, 561, false);
		controller.start(ExitTradesMode.INSTANT);
		controller.onOfferChanged(rec(0, 561, false, 10, OfferState.FILLED)); // sold on its own
		assertEquals(ExitPhase.AWAITING_COLLECT, controller.getTargets().get(0).getPhase());
		controller.onOfferChanged(rec(0, 561, false, 10, OfferState.COLLECTED)); // profit collected
		assertEquals(ExitPhase.DONE, controller.getTargets().get(0).getPhase());
	}

	@Test
	public void sellCancelledToRelistBecomesHoldingNotSkipped()
	{
		// Live sell the player cancels to re-list at the exit price: the unsold stock returns to
		// inventory. The target must become CANCELLED_HOLDING (remembering the returned quantity)
		// so surfaceCurrent re-lists it via the lag-tolerant held path — never skipped as DONE.
		seed(3, 561, false);
		controller.start(ExitTradesMode.INSTANT);
		boolean advanced = controller.onOfferChanged(rec(3, 561, false, 0, OfferState.CANCELLED_EMPTY));
		assertTrue(advanced);
		assertEquals(ExitPhase.CANCELLED_HOLDING, controller.getTargets().get(0).getPhase());
		assertEquals(10, controller.getTargets().get(0).getHeldQuantity()); // total 10 - filled 0
	}

	@Test
	public void partiallySoldSellCancelledHoldsRemainingQuantity()
	{
		seed(4, 561, false);
		controller.start(ExitTradesMode.INSTANT);
		// 4 of 10 sold before the player cancelled; 6 return to inventory to re-list.
		controller.onOfferChanged(rec(4, 561, false, 4, OfferState.CANCELLED_PARTIAL));
		assertEquals(ExitPhase.CANCELLED_HOLDING, controller.getTargets().get(0).getPhase());
		assertEquals(6, controller.getTargets().get(0).getHeldQuantity());
	}

	@Test
	public void buyCancelWithNoStockGoesDone()
	{
		seed(1, 4151, true);
		controller.start(ExitTradesMode.INSTANT);
		controller.onOfferChanged(rec(1, 4151, true, 0, OfferState.CANCELLED_EMPTY));
		assertEquals(ExitPhase.DONE, controller.getTargets().get(0).getPhase());
	}

	@Test
	public void sameItemAcrossTwoSlotsAttributesEventToCorrectSlot()
	{
		seed(0, 561, false); // sell item 561 in slot 0
		seed(1, 561, false); // sell item 561 in slot 1 (same item, separate slot)
		controller.start(ExitTradesMode.INSTANT);

		// Slot 1's offer fills; only slot 1's target should advance, not slot 0's.
		controller.onOfferChanged(rec(1, 561, false, 10, OfferState.FILLED));
		assertEquals(ExitPhase.PENDING, controller.getTargets().get(0).getPhase());
		assertEquals(ExitPhase.AWAITING_COLLECT, controller.getTargets().get(1).getPhase());
	}

	@Test
	public void actedCountZeroBeforeAnyAction()
	{
		seed(0, 561, false);
		seed(1, 4151, true);
		controller.start(ExitTradesMode.INSTANT);
		assertEquals(0, controller.actedCount());
	}
}
