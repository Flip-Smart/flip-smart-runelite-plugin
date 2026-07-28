package com.flipsmart.exit;

import com.flipsmart.FocusedFlip;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * #1114: Exit Trades started with items already collected into inventory (no live GE slot)
 * must still seed a sell queue. Before the fix, {@code start()} scanned GE slots only, so an
 * all-slots-empty exit produced an empty queue that silently no-opped.
 */
public class ExitTradesInventorySeedTest
{
	private static final int NATURE_RUNE = 561;
	private static final int WHIP = 4151;

	private OfferStore store;
	private ExitTradesController controller;
	private final Map<Integer, Integer> inventory = new HashMap<>();
	private Collection<Integer> heldSellItemIds = Collections.emptyList();
	private final AtomicReference<FocusedFlip> lastFocus = new AtomicReference<>();

	@Before
	public void setUp()
	{
		store = new OfferStore();
		controller = new ExitTradesController(store);
		controller.setBuyBasisSupplier(itemId -> 1000);
		controller.setWikiPriceSupplier(itemId -> new WikiPrice(1100, 950));
		controller.setInventoryQtySupplier(itemId -> inventory.getOrDefault(itemId, 0));
		controller.setItemNameSupplier(itemId -> "Item " + itemId);
		controller.setHeldSellItemIdsSupplier(() -> heldSellItemIds);
		controller.setOnFocusTarget(lastFocus::set);
	}

	private void placeSlot(int slot, int itemId, boolean buy, int qty)
	{
		java.util.List<OfferRecord> existing = new java.util.ArrayList<>(store.export());
		existing.add(OfferRecord.newOffer(5000L + slot, slot, itemId, "Item", buy, qty, 100, 1L));
		store.importRecords(existing);
	}

	@Test
	public void seedsHeldInventoryWhenNoSlotsOccupied()
	{
		inventory.put(NATURE_RUNE, 500);
		heldSellItemIds = Collections.singletonList(NATURE_RUNE);

		controller.start(ExitTradesMode.INSTANT);

		assertTrue("queue should be active from inventory alone", controller.isActive());
		assertEquals(1, controller.getTargets().size());
		ExitSlotTarget t = controller.getTargets().get(0);
		assertEquals(NATURE_RUNE, t.getItemId());
		assertFalse(t.isBuy());
		assertTrue("inventory-held target carries no live slot", t.getSlot() < 0);
		assertEquals(ExitPhase.CANCELLED_HOLDING, t.getPhase());
		assertEquals(500, t.getHeldQuantity());
	}

	@Test
	public void inventorySeedSkipsItemsAlreadyCoveredBySlot()
	{
		placeSlot(0, NATURE_RUNE, false, 1000); // live sell offer for nature rune
		inventory.put(NATURE_RUNE, 1000);
		inventory.put(WHIP, 10);
		heldSellItemIds = Arrays.asList(NATURE_RUNE, WHIP);

		controller.start(ExitTradesMode.INSTANT);

		assertEquals("nature rune counted once (slot), whip added from inventory", 2, controller.getTargets().size());
		assertEquals(0, controller.getTargets().get(0).getSlot());
		assertEquals(NATURE_RUNE, controller.getTargets().get(0).getItemId());
		assertTrue(controller.getTargets().get(1).getSlot() < 0);
		assertEquals(WHIP, controller.getTargets().get(1).getItemId());
	}

	@Test
	public void inventorySeedSkipsZeroQtyItems()
	{
		inventory.put(NATURE_RUNE, 0); // nothing actually held
		heldSellItemIds = Collections.singletonList(NATURE_RUNE);

		controller.start(ExitTradesMode.INSTANT);

		assertFalse(controller.isActive());
		assertEquals(0, controller.getTargets().size());
	}

	@Test
	public void regularModeDoesNotSeedInventory()
	{
		inventory.put(NATURE_RUNE, 500);
		heldSellItemIds = Collections.singletonList(NATURE_RUNE);

		controller.start(ExitTradesMode.REGULAR);

		assertTrue(controller.isActive());          // still suppresses buys
		assertEquals(0, controller.getTargets().size()); // but no queue — delegates to normal flow
	}

	@Test
	public void seededInventoryItemSurfacesInstantSellFocus()
	{
		inventory.put(NATURE_RUNE, 500);
		heldSellItemIds = Collections.singletonList(NATURE_RUNE);

		controller.start(ExitTradesMode.INSTANT);
		controller.surfaceCurrent();

		FocusedFlip f = lastFocus.get();
		assertNotNull("inventory-held item must surface a sell prompt immediately", f);
		assertTrue(f.isSelling());
		assertEquals(NATURE_RUNE, f.getItemId());
		assertEquals(950, f.getCurrentStepPrice()); // instant = low wiki price
		assertEquals(500, f.getCurrentStepQuantity());
	}

	@Test
	public void seededInventoryAdvancesToDoneWhenReListed()
	{
		inventory.put(NATURE_RUNE, 500);
		heldSellItemIds = Collections.singletonList(NATURE_RUNE);
		controller.start(ExitTradesMode.INSTANT);
		controller.surfaceCurrent();

		// Player re-lists the held stock as a fresh sell (lands in some slot).
		OfferRecord freshSell = OfferRecord.newOffer(9000L, 2, NATURE_RUNE, "Item", false, 500, 950, 2L);
		assertTrue(freshSell.getState() == OfferState.NEW);
		assertTrue(controller.onOfferChanged(freshSell));
		assertEquals(ExitPhase.DONE, controller.getTargets().get(0).getPhase());
	}
}
