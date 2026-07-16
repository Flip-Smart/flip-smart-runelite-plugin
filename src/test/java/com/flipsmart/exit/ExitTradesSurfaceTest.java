package com.flipsmart.exit;

import com.flipsmart.FocusedFlip;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExitTradesSurfaceTest
{
	private OfferStore store;
	private ExitTradesController controller;
	private final AtomicReference<FocusedFlip> lastFocus = new AtomicReference<>();
	private final AtomicReference<String> lastStatus = new AtomicReference<>();

	@Before
	public void setUp()
	{
		store = new OfferStore();
		controller = new ExitTradesController(store);
		controller.setBuyBasisSupplier(itemId -> 1000);
		controller.setWikiPriceSupplier(itemId -> new WikiPrice(1100, 950));
		controller.setInventoryQtySupplier(itemId -> 5);
		controller.setOnFocusTarget(lastFocus::set);
		controller.setOnStatusMessage((m, id) -> lastStatus.set(m));
	}

	private void seed(int slot, int itemId, boolean buy, int qty)
	{
		java.util.List<OfferRecord> existing = new java.util.ArrayList<>(store.export());
		existing.add(OfferRecord.newOffer(4000L + slot, slot, itemId, "Item", buy, qty, 100, 1L));
		store.importRecords(existing);
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
}
