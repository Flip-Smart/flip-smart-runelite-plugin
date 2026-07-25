package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlipOnlyFavoritesGateTest
{
	@Test
	public void favoritesOnlyRequiresBothToggleAndPremium()
	{
		assertTrue(FlipFinderPanel.resolveFavoritesOnly(true, true));
		assertFalse(FlipFinderPanel.resolveFavoritesOnly(true, false)); // free tier never sends it
		assertFalse(FlipFinderPanel.resolveFavoritesOnly(false, true));
		assertFalse(FlipFinderPanel.resolveFavoritesOnly(false, false));
	}
}
