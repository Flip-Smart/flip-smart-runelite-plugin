package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AutoRecommendCancelCooldownTest
{
	private static final long THIRTY_MIN = 30 * 60 * 1000L;
	private static final long FIVE_MIN = 5 * 60 * 1000L;

	@Test
	public void promptedCancelOnActiveHoldsThirtyMinutes()
	{
		assertEquals(THIRTY_MIN, AutoRecommendService.cancelCooldownMs(FlipSmartConfig.FlipTimeframe.ACTIVE, true));
	}

	@Test
	public void manualCancelOnActiveHoldsFiveMinutes()
	{
		assertEquals(FIVE_MIN, AutoRecommendService.cancelCooldownMs(FlipSmartConfig.FlipTimeframe.ACTIVE, false));
	}

	@Test
	public void otherTimeframesGetNoCooldown()
	{
		for (FlipSmartConfig.FlipTimeframe tf : FlipSmartConfig.FlipTimeframe.values())
		{
			if (tf == FlipSmartConfig.FlipTimeframe.ACTIVE)
			{
				continue;
			}
			assertEquals(tf.name(), 0L, AutoRecommendService.cancelCooldownMs(tf, true));
			assertEquals(tf.name(), 0L, AutoRecommendService.cancelCooldownMs(tf, false));
		}
	}
}
