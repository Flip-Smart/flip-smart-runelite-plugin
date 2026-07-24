package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FavoritesTabIndexTest
{
	@Test
	public void tabOrderIsRecommendedActiveCompleted()
	{
		assertEquals(0, FlipFinderPanel.TAB_RECOMMENDED);
		assertEquals(1, FlipFinderPanel.TAB_ACTIVE_FLIPS);
		assertEquals(2, FlipFinderPanel.TAB_COMPLETED);
	}
}
