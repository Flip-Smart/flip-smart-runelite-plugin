package com.flipsmart.exit;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
	public void buyCancelWithStockGoesHoldingThenDone()
	{
		seed(2, 4151, true);
		controller.start(ExitTradesMode.INSTANT);
		controller.onOfferChanged(rec(2, 4151, true, 3, OfferState.CANCELLED_PARTIAL));
		assertEquals(ExitPhase.CANCELLED_HOLDING, controller.getTargets().get(0).getPhase());
		assertEquals(1, controller.actedCount());

		controller.onOfferChanged(rec(2, 4151, false, 0, OfferState.NEW));
		assertEquals(ExitPhase.DONE, controller.getTargets().get(0).getPhase());
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
	public void actedCountZeroBeforeAnyAction()
	{
		seed(0, 561, false);
		seed(1, 4151, true);
		controller.start(ExitTradesMode.INSTANT);
		assertEquals(0, controller.actedCount());
	}
}
