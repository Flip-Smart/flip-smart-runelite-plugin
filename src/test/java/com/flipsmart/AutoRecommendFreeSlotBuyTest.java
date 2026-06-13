package com.flipsmart;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Covers {@link AutoRecommendService#firstAvailableBuyIndex} — the scan that lets a
 * freed GE slot (collect / unfilled-cancel) surface a buy even when {@code currentIndex}
 * has advanced past every still-valid item. Monotonic {@code currentIndex} was the
 * root cause of issue #725: once it reached the end, a freed slot found nothing to
 * focus until the next full queue refresh.
 */
public class AutoRecommendFreeSlotBuyTest
{
	// recommendedBuyPrice/SellPrice/quantity chosen so adjusted profit is clearly
	// positive (profitable) or negative (below threshold) under priceOffset 0.
	private static FlipRecommendation profitable(int itemId)
	{
		FlipRecommendation r = new FlipRecommendation();
		r.setItemId(itemId);
		r.setItemName("item-" + itemId);
		r.setRecommendedBuyPrice(100);
		r.setRecommendedSellPrice(200);
		r.setRecommendedQuantity(10);
		return r;
	}

	private static FlipRecommendation unprofitable(int itemId)
	{
		FlipRecommendation r = new FlipRecommendation();
		r.setItemId(itemId);
		r.setItemName("item-" + itemId);
		r.setRecommendedBuyPrice(200);
		r.setRecommendedSellPrice(100);
		r.setRecommendedQuantity(10);
		return r;
	}

	private static final Set<Integer> NONE_ACTIVE = Collections.emptySet();
	private static final int NO_EXCLUDE = -1;

	// The freed slot must find an available item even when it sits at index 0 and
	// the (monotonic) currentIndex has run off the end — the scan ignores currentIndex.
	@Test
	public void findsLowestAvailableIndex()
	{
		List<FlipRecommendation> queue = Arrays.asList(
			profitable(1001), profitable(2002), profitable(3003));

		int idx = AutoRecommendService.firstAvailableBuyIndex(queue, NONE_ACTIVE, NO_EXCLUDE, 0, 1);

		assertEquals(0, idx);
	}

	// Items already live on the GE are skipped — only a genuinely free item surfaces.
	@Test
	public void skipsItemsAlreadyOnTheGe()
	{
		List<FlipRecommendation> queue = Arrays.asList(
			profitable(1001), profitable(2002), profitable(3003));
		Set<Integer> active = new HashSet<>(Arrays.asList(1001, 2002));

		int idx = AutoRecommendService.firstAvailableBuyIndex(queue, active, NO_EXCLUDE, 0, 1);

		assertEquals("first non-GE item", 2, idx);
	}

	// The just-cancelled item is skipped so a cancel surfaces a different flip
	// instead of immediately re-recommending the one the player just dropped (#725).
	@Test
	public void skipsExcludedJustCancelledItem()
	{
		List<FlipRecommendation> queue = Arrays.asList(
			profitable(1001), profitable(2002), profitable(3003));

		int idx = AutoRecommendService.firstAvailableBuyIndex(queue, NONE_ACTIVE, 1001, 0, 1);

		assertEquals("skips the excluded item, picks the next", 1, idx);
	}

	// Items below the minimum-profit threshold are skipped.
	@Test
	public void skipsBelowMinProfit()
	{
		List<FlipRecommendation> queue = Arrays.asList(
			unprofitable(1001), profitable(2002));

		int idx = AutoRecommendService.firstAvailableBuyIndex(queue, NONE_ACTIVE, NO_EXCLUDE, 0, 1);

		assertEquals(1, idx);
	}

	// Nothing available -> -1, so the caller prompts collection instead of crashing (AC6 spirit).
	@Test
	public void returnsMinusOneWhenNothingAvailable()
	{
		List<FlipRecommendation> queue = Arrays.asList(
			profitable(1001), profitable(2002));
		Set<Integer> active = new HashSet<>(Arrays.asList(1001, 2002));

		int idx = AutoRecommendService.firstAvailableBuyIndex(queue, active, NO_EXCLUDE, 0, 1);

		assertEquals(-1, idx);
	}

	@Test
	public void returnsMinusOneForEmptyQueue()
	{
		int idx = AutoRecommendService.firstAvailableBuyIndex(
			Collections.emptyList(), NONE_ACTIVE, NO_EXCLUDE, 0, 1);

		assertEquals(-1, idx);
	}
}
