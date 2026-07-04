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
		BufferedImage icon = PanelFormat.drawGearIcon(Color.WHITE);
		assertNotNull(icon);
		assertEquals(14, icon.getWidth());
		assertEquals(14, icon.getHeight());
	}
}
