package com.flipsmart;

import com.flipsmart.ui.panel.PanelFormat;

import org.junit.Test;

import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class GearIconTest
{
	@Test
	public void loadsNonNullFixedSizeIcon()
	{
		BufferedImage icon = PanelFormat.icon("gear");
		assertNotNull("gear.png must be present on the classpath", icon);
		assertEquals(12, icon.getWidth());
		assertEquals(12, icon.getHeight());
	}
}
