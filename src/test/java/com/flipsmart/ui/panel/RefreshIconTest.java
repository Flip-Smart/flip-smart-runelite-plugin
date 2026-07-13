package com.flipsmart.ui.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class RefreshIconTest
{
	@Test
	public void drawsFourteenBySfourteenIcon()
	{
		BufferedImage icon = PanelFormat.drawRefreshIcon(new Color(120, 200, 255));
		assertNotNull(icon);
		assertEquals(14, icon.getWidth());
		assertEquals(14, icon.getHeight());
	}

	@Test
	public void drawsAtLeastOneVisiblePixel()
	{
		BufferedImage icon = PanelFormat.drawRefreshIcon(new Color(120, 200, 255));
		boolean anyOpaque = false;
		for (int x = 0; x < 14 && !anyOpaque; x++)
		{
			for (int y = 0; y < 14; y++)
			{
				if (((icon.getRGB(x, y) >>> 24) & 0xFF) > 0)
				{
					anyOpaque = true;
					break;
				}
			}
		}
		assertTrue("expected the refresh glyph to draw visible pixels", anyOpaque);
	}
}
