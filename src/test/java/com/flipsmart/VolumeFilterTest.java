package com.flipsmart;
import com.flipsmart.domain.flip.FlipRecommendation;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The minimum-volume floor of the shared recommendation predicate
 * ({@link FocusedFlip#passesRecommendationFilters}) — used by both Flip Finder's
 * list and the Flip Assist queue. Each recommendation here clears the profit floor
 * (minProfit 0), so daily volume is the only discriminator.
 */
public class VolumeFilterTest
{
	private static final int NO_OFFSET = 0;
	private static final int NO_MIN_PROFIT = 0;

	/** A comfortably-profitable recommendation with the given daily volume. */
	private static FlipRecommendation withDailyVolume(int dailyVolume)
	{
		FlipRecommendation r = new FlipRecommendation();
		r.setItemId(1);
		r.setRecommendedBuyPrice(100);
		r.setRecommendedSellPrice(2000);
		r.setRecommendedQuantity(1);
		r.setDailyVolume(dailyVolume);
		return r;
	}

	private static boolean passesVolume(int dailyVolume, int minVolume)
	{
		return FocusedFlip.passesRecommendationFilters(
			withDailyVolume(dailyVolume), NO_OFFSET, NO_MIN_PROFIT, minVolume);
	}

	@Test
	public void volumeAtOrAboveThresholdPasses()
	{
		assertTrue(passesVolume(1000, 500));
		assertTrue(passesVolume(500, 500));
	}

	@Test
	public void volumeBelowThresholdFails()
	{
		assertFalse(passesVolume(499, 500));
	}

	@Test
	public void zeroThresholdPassesEverything()
	{
		assertTrue(passesVolume(0, 0));
		assertTrue(passesVolume(1, 0));
	}
}
