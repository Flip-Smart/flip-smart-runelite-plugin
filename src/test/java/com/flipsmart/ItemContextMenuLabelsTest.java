package com.flipsmart;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ItemContextMenuLabelsTest
{
	@Test
	public void favoriteLabelReflectsState()
	{
		assertEquals("Add to favorites", FlipFinderPanel.favoriteMenuLabel(false));
		assertEquals("Remove from favorites", FlipFinderPanel.favoriteMenuLabel(true));
	}

	@Test
	public void sharedCoreOrderForNonFavorited()
	{
		assertEquals(
			List.of("Add to favorites", "Block this item", "View Item Graph"),
			FlipFinderPanel.itemContextMenuLabels(false, false));
	}

	@Test
	public void favoritedShowsRemove()
	{
		assertEquals(
			List.of("Remove from favorites", "Block this item", "View Item Graph"),
			FlipFinderPanel.itemContextMenuLabels(true, false));
	}

	@Test
	public void activeFlipsAppendsDismiss()
	{
		assertEquals(
			List.of("Add to favorites", "Block this item", "View Item Graph", "Dismiss from Active Flips"),
			FlipFinderPanel.itemContextMenuLabels(false, true));
	}
}
