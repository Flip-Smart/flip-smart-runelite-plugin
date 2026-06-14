package com.flipsmart;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegacyAdjustmentGuardTest
{
	@Test
	public void legacyDisabledInActiveWhenAdvisorOn()
	{
		assertFalse(AutoRecommendService.shouldRunLegacyAdjustment("active", true));
	}

	@Test
	public void legacyEnabledInActiveWhenAdvisorOff()
	{
		assertTrue(AutoRecommendService.shouldRunLegacyAdjustment("active", false));
	}

	@Test
	public void legacyEnabledInNonActiveRegardlessOfAdvisor()
	{
		assertTrue(AutoRecommendService.shouldRunLegacyAdjustment("2h", true));
		assertTrue(AutoRecommendService.shouldRunLegacyAdjustment("30m", true));
	}
}
