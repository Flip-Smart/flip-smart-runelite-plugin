package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 12h buys must hold until the backend's 8h exit timer. The local short-inactivity
 * stale detector ({@code INACTIVITY_TIMEOUT_MS} = 15 min) would otherwise queue a
 * "consider cancelling" prompt far too early for an overnight trade.
 */
public class AutoRecommend12hBuyHoldTest
{
	@Test
	public void twelveHourBuysAreNotFlaggedStaleLocally()
	{
		assertFalse(AutoRecommendService.localBuyStaleDetectionEnabled(
			FlipSmartConfig.FlipTimeframe.TWELVE_HOURS));
	}

	@Test
	public void shorterTimeframesStillUseLocalStaleDetection()
	{
		assertTrue(AutoRecommendService.localBuyStaleDetectionEnabled(
			FlipSmartConfig.FlipTimeframe.ACTIVE));
		assertTrue(AutoRecommendService.localBuyStaleDetectionEnabled(
			FlipSmartConfig.FlipTimeframe.THIRTY_MINS));
		assertTrue(AutoRecommendService.localBuyStaleDetectionEnabled(
			FlipSmartConfig.FlipTimeframe.TWO_HOURS));
		assertTrue(AutoRecommendService.localBuyStaleDetectionEnabled(
			FlipSmartConfig.FlipTimeframe.FOUR_HOURS));
	}

	@Test
	public void twelveHourLadderPromptsBypassCompetitivenessGate()
	{
		// The backend is authoritative for 12h (rung readjusts / 8h exit); the local
		// green/red-border competitiveness check must not drop those prompts.
		assertFalse(AutoRecommendService.competitivenessGateApplies(
			FlipSmartConfig.FlipTimeframe.TWELVE_HOURS));
	}

	@Test
	public void shorterTimeframesKeepCompetitivenessGate()
	{
		assertTrue(AutoRecommendService.competitivenessGateApplies(
			FlipSmartConfig.FlipTimeframe.ACTIVE));
		assertTrue(AutoRecommendService.competitivenessGateApplies(
			FlipSmartConfig.FlipTimeframe.TWO_HOURS));
		assertTrue(AutoRecommendService.competitivenessGateApplies(
			FlipSmartConfig.FlipTimeframe.FOUR_HOURS));
	}
}
