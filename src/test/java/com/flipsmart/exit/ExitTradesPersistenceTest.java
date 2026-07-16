package com.flipsmart.exit;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExitTradesPersistenceTest
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
		existing.add(OfferRecord.newOffer(3000L + slot, slot, itemId, "x", buy, 10, 100, 1L));
		store.importRecords(existing);
	}

	@Test
	public void discardsWhenNothingActed()
	{
		seed(0, 561, false);
		seed(1, 999, false);
		controller.start(ExitTradesMode.INSTANT);
		assertNull(controller.getStateForPersistence(5000L)); // AC6
	}

	@Test
	public void persistsOnlyPendingTargetsAfterAction()
	{
		seed(0, 561, false);
		seed(1, 999, false);
		controller.start(ExitTradesMode.BREAKEVEN);
		controller.onOfferChanged(OfferRecord.newOffer(1L, 0, 561, "x", false, 10, 100, 1L)); // slot 0 relisted -> DONE

		ExitTradesController.PersistedState s = controller.getStateForPersistence(5000L);
		assertEquals(ExitTradesMode.BREAKEVEN, s.mode);
		assertEquals(1, s.pending.size());
		assertEquals(999, s.pending.get(0).itemId);
		assertEquals("PENDING", s.pending.get(0).phase);
	}

	@Test
	public void restoreRoundTrip()
	{
		seed(0, 561, false);
		seed(1, 999, true);
		controller.start(ExitTradesMode.INSTANT);
		controller.onOfferChanged(OfferRecord.newOffer(1L, 1, 999, "x", true, 10, 100, 1L)
			.withFill(2, 0L, OfferState.CANCELLED_PARTIAL, 2L)); // slot1 buy -> CANCELLED_HOLDING
		ExitTradesController.PersistedState s = controller.getStateForPersistence(5000L);

		ExitTradesController fresh = new ExitTradesController(store);
		assertTrue(fresh.restoreState(s, 6000L, 60_000L));
		assertTrue(fresh.isActive());
		assertEquals(ExitTradesMode.INSTANT, fresh.getMode());
		assertEquals(2, fresh.getTargets().size());
		assertEquals(ExitPhase.CANCELLED_HOLDING,
			fresh.getTargets().stream().filter(t -> t.getSlot() == 1).findFirst().get().getPhase());
	}

	@Test
	public void restoreRejectsExpired()
	{
		seed(0, 561, false);
		controller.start(ExitTradesMode.INSTANT);
		controller.getTargets().get(0).setPhase(ExitPhase.CANCELLED_HOLDING);
		ExitTradesController.PersistedState s = controller.getStateForPersistence(1000L);
		ExitTradesController fresh = new ExitTradesController(store);
		assertFalse(fresh.restoreState(s, 1000L + 999_999L, 60_000L));
		assertFalse(fresh.isActive());
	}
}
