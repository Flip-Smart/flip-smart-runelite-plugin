package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FavoritesTabIndexTest
{
	@Test
	public void tabOrderIsRecommendedFavoritesActiveCompleted()
	{
		assertEquals(0, FlipFinderPanel.TAB_RECOMMENDED);
		assertEquals(1, FlipFinderPanel.TAB_FAVORITES);
		assertEquals(2, FlipFinderPanel.TAB_ACTIVE_FLIPS);
		assertEquals(3, FlipFinderPanel.TAB_COMPLETED);
	}
}
