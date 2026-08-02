package com.flipsmart.ui.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.image.BufferedImage;
import org.junit.Test;

/**
 * The refresh glyph is now a captured asset rather than Graphics2D output. These assert the
 * same properties the drawing tests did, so a missing or wrong-sized asset still fails here.
 */
public class RefreshIconTest
{
	private static boolean hasOpaquePixel(BufferedImage icon)
	{
		for (int x = 0; x < icon.getWidth(); x++)
		{
			for (int y = 0; y < icon.getHeight(); y++)
			{
				if (((icon.getRGB(x, y) >>> 24) & 0xFF) > 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void loadsFourteenByFourteenIcon()
	{
		BufferedImage icon = PanelFormat.icon("refresh");
		assertNotNull("refresh.png must be present on the classpath", icon);
		assertEquals(14, icon.getWidth());
		assertEquals(14, icon.getHeight());
	}

	@Test
	public void assetHasVisiblePixels()
	{
		assertTrue("expected the refresh glyph to have visible pixels", hasOpaquePixel(PanelFormat.icon("refresh")));
	}

	/** The hover variant is a distinct asset, not a re-tint at runtime. */
	@Test
	public void hoverVariantIsADistinctAsset()
	{
		BufferedImage base = PanelFormat.icon("refresh");
		BufferedImage hover = PanelFormat.icon("refresh_hover");
		assertNotNull(hover);
		boolean identical = true;
		for (int x = 0; x < base.getWidth() && identical; x++)
		{
			for (int y = 0; y < base.getHeight(); y++)
			{
				if (base.getRGB(x, y) != hover.getRGB(x, y))
				{
					identical = false;
					break;
				}
			}
		}
		assertFalse("hover variant must differ from the base icon", identical);
	}
}
