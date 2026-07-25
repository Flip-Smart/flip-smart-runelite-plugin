package com.flipsmart;

import com.flipsmart.ui.panel.PanelFormat;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FavoritesStarStateTest
{
	@Test
	public void drawStarIconReturnsSizedImageForBothStates()
	{
		BufferedImage filled = PanelFormat.drawStarIcon(true, Color.ORANGE);
		BufferedImage empty = PanelFormat.drawStarIcon(false, Color.LIGHT_GRAY);
		assertNotNull(filled);
		assertNotNull(empty);
		assertEquals(16, filled.getWidth());
		assertEquals(16, filled.getHeight());
	}

	@Test
	public void isFavoriteReflectsSetMembership()
	{
		Set<Integer> favs = new HashSet<>();
		favs.add(4151);
		assertTrue(com.flipsmart.FlipFinderPanel.isFavorite(favs, 4151));
		assertFalse(com.flipsmart.FlipFinderPanel.isFavorite(favs, 561));
	}
}
