package com.flipsmart.exit;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExitTradesControllerQueueTest
{
	private static final String NATURE_RUNE = "Nature rune";

	private OfferStore store;
	private ExitTradesController controller;

	@Before
	public void setUp()
	{
		store = new OfferStore();
		controller = new ExitTradesController(store);
		controller.setBuyBasisSupplier(itemId -> itemId == 4151 ? 1_000_000 : 0);
	}

	private void place(int slot, int itemId, String name, boolean buy, int price, int qty)
	{
		java.util.List<OfferRecord> existing = new java.util.ArrayList<>(store.export());
		existing.add(OfferRecord.newOffer(1000L + slot, slot, itemId, name, buy, qty, price, 1L));
		store.importRecords(existing);
	}

	@Test
	public void buildsQueueInSlotOrderSkippingEmpties()
	{
		place(0, 561, NATURE_RUNE, false, 100, 1000);
		place(3, 4151, "Abyssal whip", true, 1_400_000, 1);
		controller.start(ExitTradesMode.INSTANT);

		assertTrue(controller.isActive());
		assertEquals(2, controller.getTargets().size());
		assertEquals(0, controller.getTargets().get(0).getSlot());
		assertEquals(3, controller.getTargets().get(1).getSlot());
		assertFalse(controller.getTargets().get(0).isBuy());
		assertTrue(controller.getTargets().get(1).isBuy());
		assertEquals(1_000_000, controller.getTargets().get(1).getBuyBasis());
	}

	@Test
	public void currentTargetIsFirstNonDone()
	{
		place(0, 561, NATURE_RUNE, false, 100, 1000);
		place(1, 561, NATURE_RUNE, false, 100, 1000);
		controller.start(ExitTradesMode.INSTANT);
		controller.getTargets().get(0).setPhase(ExitPhase.DONE);
		assertEquals(1, controller.currentTarget().getSlot());
	}

	@Test
	public void regularModeActiveWithNoQueueAndDoesNotOwnOverlay()
	{
		place(0, 561, NATURE_RUNE, false, 100, 1000); // occupied slots are ignored in REGULAR
		controller.start(ExitTradesMode.REGULAR);
		assertTrue(controller.isActive());       // suppresses buys
		assertFalse(controller.ownsOverlay());   // hands overlay to the normal sell flow
		assertEquals(0, controller.getTargets().size());
		controller.surfaceCurrent();             // no-op; must not deactivate
		assertTrue(controller.isActive());
		assertNull(controller.currentTarget());
	}

	@Test
	public void breakevenOwnsOverlay()
	{
		place(0, 561, NATURE_RUNE, false, 100, 1000);
		controller.start(ExitTradesMode.BREAKEVEN);
		assertTrue(controller.ownsOverlay());
	}

	@Test
	public void clearDeactivates()
	{
		place(0, 561, NATURE_RUNE, false, 100, 1000);
		controller.start(ExitTradesMode.INSTANT);
		controller.clear();
		assertFalse(controller.isActive());
		assertNull(controller.currentTarget());
	}
}
