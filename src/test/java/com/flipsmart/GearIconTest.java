package com.flipsmart;

import com.flipsmart.ui.panel.PanelFormat;

import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class GearIconTest
{
	@Test
	public void drawsNonNullFixedSizeIcon()
	{
		BufferedImage icon = PanelFormat.drawGearIcon(Color.WHITE, 12);
		assertNotNull(icon);
		assertEquals(12, icon.getWidth());
		assertEquals(12, icon.getHeight());
	}
}
