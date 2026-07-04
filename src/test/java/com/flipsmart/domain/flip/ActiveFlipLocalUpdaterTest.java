package com.flipsmart.domain.flip;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActiveFlipLocalUpdaterTest
{
	private static final long NOW_MS = 1_000_000L;
	private static final String NOW_ISO = "2026-07-02T00:00:00Z";

	private static OfferRecord record(boolean buy, int itemId, int total, int price, int filled, long spent)
	{
		return OfferRecord.newOffer(1L, 0, itemId, "item" + itemId, buy, total, price, NOW_MS)
			.withFill(filled, spent, filled >= total ? OfferState.FILLED : OfferState.PARTIAL_FILL, NOW_MS);
	}

	private static ActiveFlip flip(int itemId)
	{
		ActiveFlip f = new ActiveFlip();
		f.setItemId(itemId);
		f.setItemName("item" + itemId);
		return f;
	}

	@Test
	public void collectedBuyWithFillsAddsFlip()
	{
		List<ActiveFlip> flips = new ArrayList<>();

		boolean changed = ActiveFlipLocalUpdater.applyCollect(flips, record(true, 42, 10, 1_100, 5, 5_000L), NOW_ISO);

		assertTrue(changed);
		assertEquals(1, flips.size());
		ActiveFlip added = flips.get(0);
		assertEquals(42, added.getItemId());
		assertEquals("item42", added.getItemName());
		assertEquals(5, added.getTotalQuantity());
		assertEquals(5, added.getOriginalQuantity());
		assertEquals(1_000, added.getAverageBuyPrice());
		assertEquals(5_000, added.getTotalInvested());
		assertEquals("buy", added.getPhase());
		assertEquals(NOW_ISO, added.getFirstBuyTime());
		assertEquals(NOW_ISO, added.getLastBuyTime());
	}

	@Test
	public void collectedEmptyBuyAddsNothing()
	{
		List<ActiveFlip> flips = new ArrayList<>();

		boolean changed = ActiveFlipLocalUpdater.applyCollect(flips, record(true, 42, 10, 1_000, 0, 0L), NOW_ISO);

		assertFalse(changed);
		assertTrue(flips.isEmpty());
	}

	@Test
	public void collectedBuyForExistingItemDoesNotDuplicate()
	{
		List<ActiveFlip> flips = new ArrayList<>();
		flips.add(flip(42));

		boolean changed = ActiveFlipLocalUpdater.applyCollect(flips, record(true, 42, 10, 1_000, 5, 5_000L), NOW_ISO);

		assertFalse(changed);
		assertEquals(1, flips.size());
	}

	@Test
	public void fullyFilledCollectedSellRemovesFlip()
	{
		List<ActiveFlip> flips = new ArrayList<>();
		flips.add(flip(42));
		flips.add(flip(43));

		boolean changed = ActiveFlipLocalUpdater.applyCollect(flips, record(false, 42, 10, 1_200, 10, 12_000L), NOW_ISO);

		assertTrue(changed);
		assertEquals(1, flips.size());
		assertEquals(43, flips.get(0).getItemId());
	}

	@Test
	public void partiallyFilledCollectedSellLeavesFlip()
	{
		List<ActiveFlip> flips = new ArrayList<>();
		flips.add(flip(42));

		boolean changed = ActiveFlipLocalUpdater.applyCollect(flips, record(false, 42, 10, 1_200, 4, 4_800L), NOW_ISO);

		assertFalse(changed);
		assertEquals(1, flips.size());
	}

	@Test
	public void averagePriceFallsBackToOfferPriceWhenSpentUnknown()
	{
		List<ActiveFlip> flips = new ArrayList<>();

		boolean changed = ActiveFlipLocalUpdater.applyCollect(flips, record(true, 42, 10, 1_000, 5, 0L), NOW_ISO);

		assertTrue(changed);
		assertEquals(1_000, flips.get(0).getAverageBuyPrice());
		assertEquals(5_000, flips.get(0).getTotalInvested());
	}
}
