package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VolumeFilterTest
{
	@Test
	public void volumeAtOrAboveThresholdPasses()
	{
		assertTrue(FlipFinderPanel.passesVolumeFilter(1000, 500));
		assertTrue(FlipFinderPanel.passesVolumeFilter(500, 500));
	}

	@Test
	public void volumeBelowThresholdFails()
	{
		assertFalse(FlipFinderPanel.passesVolumeFilter(499, 500));
	}

	@Test
	public void zeroThresholdPassesEverything()
	{
		assertTrue(FlipFinderPanel.passesVolumeFilter(0, 0));
		assertTrue(FlipFinderPanel.passesVolumeFilter(1, 0));
	}
}
