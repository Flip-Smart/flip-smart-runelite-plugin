package com.flipsmart.exit;

import com.flipsmart.FocusedFlip;
import com.flipsmart.api.dto.Dtos.WikiPrice;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * #1114: Exit Trades must prompt a sell even when the stock to exit is already collected into
 * inventory with no live GE slot. Before the fix, start() seeded the queue from GE slots only, so a
 * fully-collected position built an empty queue (active=false) and silently no-opped — the reported
 * "no prompt on first attempt" bug.
 */
public class ExitTradesInventorySeedTest
{
	private OfferStore store;
	private ExitTradesController controller;
	private final AtomicReference<FocusedFlip> lastFocus = new AtomicReference<>();
	private int inventory; // held qty returned for any item

	@Before
	public void setUp()
	{
		store = new OfferStore();
		controller = new ExitTradesController(store);
		inventory = 0;
		controller.setBuyBasisSupplier(itemId -> 1000);
		controller.setWikiPriceSupplier(itemId -> new WikiPrice(1100, 950));
		controller.setInventoryQtySupplier(itemId -> inventory);
		controller.setItemNameSupplier(itemId -> "Item");
		controller.setOnFocusTarget(lastFocus::set);
		controller.setOnStatusMessage((m, id) -> { });
	}

	private void seedSlot(int slot, int itemId, boolean buy, int qty)
	{
		java.util.List<OfferRecord> existing = new java.util.ArrayList<>(store.export());
		existing.add(OfferRecord.newOffer(4000L + slot, slot, itemId, "Item", buy, qty, 100, 1L));
		store.importRecords(existing);
	}

	@Test
	public void seedsExitFromInventoryWhenNoLiveSlots()
	{
		// No live GE offers, but the player holds 7 of item 561 collected from a prior flip.
		controller.setHeldSellItemIdsSupplier(() -> List.of(561));
		inventory = 7;

		controller.start(ExitTradesMode.INSTANT);

		assertTrue("queue seeded from inventory, not empty", controller.isActive());
		controller.surfaceCurrent();

		FocusedFlip f = lastFocus.get();
		assertNotNull("prompt fires on the first attempt (AC1)", f);
		assertTrue(f.isSelling());
		assertEquals(561, f.getItemId());
		assertEquals(950, f.getCurrentStepPrice());
		assertEquals(7, f.getCurrentStepQuantity());
		assertEquals(ExitPhase.CANCELLED_HOLDING, controller.getTargets().get(0).getPhase());
	}

	@Test
	public void doesNotDoubleSeedHeldItemAlreadyInSlot()
	{
		seedSlot(0, 561, false, 1000); // a live sell for 561 already owns slot 0
		controller.setHeldSellItemIdsSupplier(() -> List.of(561));
		inventory = 7;

		controller.start(ExitTradesMode.INSTANT);

		assertEquals("held item already represented by a slot is not double-added",
			1, controller.getTargets().size());
	}

	@Test
	public void doesNotSeedHeldItemWithNoInventory()
	{
		// Collected set still lists 561, but nothing is actually held (already sold/moved).
		controller.setHeldSellItemIdsSupplier(() -> List.of(561));
		inventory = 0;

		controller.start(ExitTradesMode.INSTANT);

		assertFalse("no phantom target for a zero-quantity held item", controller.isActive());
	}

	@Test
	public void regularModeDoesNotSeedInventory()
	{
		// REGULAR is latched sell-only with no queue; inventory seeding must not build one.
		controller.setHeldSellItemIdsSupplier(() -> List.of(561));
		inventory = 7;

		controller.start(ExitTradesMode.REGULAR);

		assertTrue(controller.isActive()); // active for buy-suppression
		assertTrue("REGULAR builds no queue", controller.getTargets().isEmpty());
	}
}
