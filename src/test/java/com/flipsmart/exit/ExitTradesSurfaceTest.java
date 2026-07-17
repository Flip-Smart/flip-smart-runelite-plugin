package com.flipsmart.exit;

import com.flipsmart.FocusedFlip;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExitTradesSurfaceTest
{
	private OfferStore store;
	private ExitTradesController controller;
	private final AtomicReference<FocusedFlip> lastFocus = new AtomicReference<>();
	private final AtomicReference<String> lastStatus = new AtomicReference<>();
	private final AtomicBoolean completed = new AtomicBoolean(false);
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
		controller.setOnFocusTarget(lastFocus::set);
		controller.setOnStatusMessage((m, id) -> lastStatus.set(m));
		controller.setOnComplete(() -> completed.set(true));
	}

	private void seed(int slot, int itemId, boolean buy, int qty)
	{
		java.util.List<OfferRecord> existing = new java.util.ArrayList<>(store.export());
		existing.add(OfferRecord.newOffer(4000L + slot, slot, itemId, "Item", buy, qty, 100, 1L));
		store.importRecords(existing);
	}

	private void clearStore()
	{
		store.importRecords(new java.util.ArrayList<>());
	}

	@Test
	public void sellTargetSurfacesInstantSellFocus()
	{
		seed(0, 561, false, 1000);
		controller.start(ExitTradesMode.INSTANT);
		controller.surfaceCurrent();
		FocusedFlip f = lastFocus.get();
		assertNotNull(f);
		assertTrue(f.isSelling());
		assertEquals(950, f.getCurrentStepPrice());
		assertEquals(1000, f.getCurrentStepQuantity());
	}

	@Test
	public void buyTargetPromptsCancelFirst()
	{
		seed(2, 4151, true, 10);
		controller.start(ExitTradesMode.INSTANT);
		controller.surfaceCurrent();
		assertTrue(lastStatus.get().toLowerCase().contains("cancel"));
	}

	@Test
	public void staleTargetIsSkippedWhenNoOfferAndNoStock()
	{
		seed(0, 561, false, 1000);
		controller.start(ExitTradesMode.INSTANT);
		// The sell for 561 sold/was collected on its own before we reach it: no live offer, none held.
		clearStore();
		inventory = 0;
		controller.surfaceCurrent();
		assertEquals(ExitPhase.DONE, controller.getTargets().get(0).getPhase());
		assertTrue(completed.get()); // nothing else pending -> run complete
	}

	@Test
	public void onSellScreenScopesPromptToThatItem()
	{
		seed(0, 561, false, 1000);
		seed(1, 999, false, 500);
		controller.start(ExitTradesMode.INSTANT);
		controller.surfaceCurrent(); // pointer surfaces slot 0 (item 561)

		// Player opens slot 1's sell screen (item 999) out of order.
		assertTrue(controller.onSellScreenOpened(999));
		FocusedFlip f = lastFocus.get();
		assertNotNull(f);
		assertEquals(999, f.getItemId());
		assertEquals(950, f.getCurrentStepPrice());
		assertEquals(500, f.getCurrentStepQuantity());
	}

	@Test
	public void onSellScreenIgnoresNonTargetItem()
	{
		seed(0, 561, false, 1000);
		controller.start(ExitTradesMode.INSTANT);
		assertFalse(controller.onSellScreenOpened(12345));
	}

	@Test
	public void collectedBuySurfacesHeldStockSell()
	{
		seed(0, 4151, true, 10);
		controller.start(ExitTradesMode.INSTANT);
		// Buy filled and was collected: slot empty, but 5 sit in inventory to sell.
		clearStore();
		inventory = 5;
		controller.surfaceCurrent();
		assertEquals(ExitPhase.CANCELLED_HOLDING, controller.getTargets().get(0).getPhase());
		FocusedFlip f = lastFocus.get();
		assertNotNull(f);
		assertTrue(f.isSelling());
		assertEquals(4151, f.getItemId());
		assertEquals(950, f.getCurrentStepPrice());
		assertEquals(5, f.getCurrentStepQuantity());
	}
}
