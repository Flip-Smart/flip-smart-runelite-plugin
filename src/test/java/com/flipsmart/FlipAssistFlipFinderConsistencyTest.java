package com.flipsmart;
import com.flipsmart.domain.flip.FlipRecommendation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #935 — Flip Assist must only surface items that Flip Finder would display.
 * Both features consume the same backend recommendation list; the plugin's
 * minimum-profit and minimum-volume config filters must therefore apply
 * identically to the Flip Finder display and to the Flip Assist queue. Prior to
 * this fix the min-volume filter was never applied to the queue (start() or
 * refresh) and the min-profit filter was missing from start(), so sub-threshold
 * items leaked into Flip Assist only.
 */
public class FlipAssistFlipFinderConsistencyTest
{
	private static final int OFFSET = 0;
	private static final int MIN_PROFIT = 500;
	private static final int MIN_VOLUME = 1000;
	private static final Set<Integer> NONE_ACTIVE = Collections.emptySet();

	private static FlipRecommendation rec(int itemId, int buy, int sell, int qty, int dailyVolume)
	{
		FlipRecommendation r = new FlipRecommendation();
		r.setItemId(itemId);
		r.setItemName("Item " + itemId);
		r.setRecommendedBuyPrice(buy);
		r.setRecommendedSellPrice(sell);
		r.setRecommendedQuantity(qty);
		r.setDailyVolume(dailyVolume);
		r.setVolumePerHour(dailyVolume / 24.0);
		return r;
	}

	private static List<Integer> ids(List<FlipRecommendation> recs)
	{
		return recs.stream().map(FlipRecommendation::getItemId).collect(Collectors.toList());
	}

	// The core #935 leak: a high-margin item that fails only the volume floor must
	// be filtered out of the Flip Assist queue, exactly as Flip Finder hides it.
	@Test
	public void excludesItemBelowMinimumVolume()
	{
		FlipRecommendation lowVolume = rec(1001, 100, 2000, 10, 100);
		FlipRecommendation ok = rec(2002, 100, 2000, 10, 5000);

		List<FlipRecommendation> filtered = AutoRecommendService.filterSurfaceable(
			Arrays.asList(lowVolume, ok), NONE_ACTIVE, OFFSET, MIN_PROFIT, MIN_VOLUME);

		assertFalse("below-min-volume item must not surface in Flip Assist", ids(filtered).contains(1001));
		assertTrue(ids(filtered).contains(2002));
	}

	// Below-min-profit items must be excluded on the start() path too — previously
	// only refreshQueue() applied the profit floor.
	@Test
	public void excludesItemBelowMinimumProfit()
	{
		FlipRecommendation lowProfit = rec(1001, 100, 101, 1, 5000);
		FlipRecommendation ok = rec(2002, 100, 2000, 10, 5000);

		List<FlipRecommendation> filtered = AutoRecommendService.filterSurfaceable(
			Arrays.asList(lowProfit, ok), NONE_ACTIVE, OFFSET, MIN_PROFIT, MIN_VOLUME);

		assertFalse("below-min-profit item must not surface", ids(filtered).contains(1001));
		assertTrue(ids(filtered).contains(2002));
	}

	// Items already active on the GE stay excluded from the queue (unchanged).
	@Test
	public void excludesActiveItems()
	{
		FlipRecommendation active = rec(1001, 100, 2000, 10, 5000);
		FlipRecommendation ok = rec(2002, 100, 2000, 10, 5000);

		List<FlipRecommendation> filtered = AutoRecommendService.filterSurfaceable(
			Arrays.asList(active, ok), Collections.singleton(1001), OFFSET, MIN_PROFIT, MIN_VOLUME);

		assertFalse(ids(filtered).contains(1001));
		assertTrue(ids(filtered).contains(2002));
	}

	// The shared predicate must match Flip Finder's shouldDisplay semantics exactly:
	// both the profit floor AND the volume floor are required.
	@Test
	public void sharedPredicateRequiresBothProfitAndVolume()
	{
		assertTrue(FocusedFlip.passesRecommendationFilters(
			rec(1, 100, 2000, 10, 5000), OFFSET, MIN_PROFIT, MIN_VOLUME));
		assertFalse("volume floor enforced", FocusedFlip.passesRecommendationFilters(
			rec(1, 100, 2000, 10, 100), OFFSET, MIN_PROFIT, MIN_VOLUME));
		assertFalse("profit floor enforced", FocusedFlip.passesRecommendationFilters(
			rec(1, 100, 101, 1, 5000), OFFSET, MIN_PROFIT, MIN_VOLUME));
	}
}
