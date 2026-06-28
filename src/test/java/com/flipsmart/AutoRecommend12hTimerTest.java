package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 12h overnight timer cadence:
 *  - sells re-check on the backend's 30-min cadence (not the 5-min default that a relist
 *    would otherwise re-arm, nagging the player every few minutes);
 *  - a buy already past the initial-check delay is re-checked immediately on login, which
 *    is what surfaces a 12h buy's 8h exit right after logging back in.
 */
public class AutoRecommend12hTimerTest
{
	private static final long THIRTY_MIN_MS = 30 * 60 * 1000L;

	@Test
	public void twelveHourSellsUseThirtyMinuteInitialCadence()
	{
		assertEquals(THIRTY_MIN_MS,
			AutoRecommendService.sellInitialCheckDelayMs(FlipSmartConfig.FlipTimeframe.TWELVE_HOURS));
	}

	@Test
	public void shorterTimeframeSellsKeepFiveMinuteInitial()
	{
		assertEquals(AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS,
			AutoRecommendService.sellInitialCheckDelayMs(FlipSmartConfig.FlipTimeframe.ACTIVE));
		assertEquals(AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS,
			AutoRecommendService.sellInitialCheckDelayMs(FlipSmartConfig.FlipTimeframe.FOUR_HOURS));
	}

	@Test
	public void offerPastInitialDelayChecksImmediatelyOnLogin()
	{
		long now = 1_000_000_000L;
		long agedOffer = AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS + 60_000L; // older than the initial delay
		assertEquals(now - 1, AutoRecommendService.loginCheckDeadlineMs(agedOffer, now));
	}

	@Test
	public void freshOfferWaitsOutRemainingInitialDelayOnLogin()
	{
		long now = 1_000_000_000L;
		long youngOffer = 60_000L; // 1 min old, under the 5-min initial delay
		long deadline = AutoRecommendService.loginCheckDeadlineMs(youngOffer, now);
		assertEquals(now + (AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS - youngOffer), deadline);
		assertTrue(deadline > now);
	}
}
