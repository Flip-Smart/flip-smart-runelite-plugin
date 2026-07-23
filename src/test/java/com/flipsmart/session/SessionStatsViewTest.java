package com.flipsmart.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class SessionStatsViewTest
{
	@Test
	public void defaultsToExpanded()
	{
		SessionStatsView view = new SessionStatsView();
		assertFalse(view.isCollapsed());
		assertTrue(view.getComponent().isVisible());
	}

	@Test
	public void restoringCollapsedStateDoesNotNotifyListener()
	{
		SessionStatsView view = new SessionStatsView();
		AtomicReference<Boolean> notified = new AtomicReference<>(null);
		view.setToggleListener(notified::set);

		view.setCollapsed(true);

		assertTrue(view.isCollapsed());
		assertNull("programmatic restore must not fire the persist callback", notified.get());
	}

	@Test
	public void toggleFlipsStateAndReportsIt()
	{
		SessionStatsView view = new SessionStatsView();
		AtomicReference<Boolean> notified = new AtomicReference<>(null);
		view.setToggleListener(notified::set);

		clickHeader(view);
		assertTrue(view.isCollapsed());
		assertEquals(Boolean.TRUE, notified.get());

		clickHeader(view);
		assertFalse(view.isCollapsed());
		assertEquals(Boolean.FALSE, notified.get());
	}

	/** Drive the header's mouse listener the way a user click would. */
	private static void clickHeader(SessionStatsView view)
	{
		java.awt.Container root = (java.awt.Container) view.getComponent();
		java.awt.Component header = root.getComponent(0);
		java.awt.event.MouseListener[] listeners =
			((javax.swing.JComponent) header).getMouseListeners();
		java.awt.event.MouseEvent press = new java.awt.event.MouseEvent(
			header, java.awt.event.MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false);
		for (java.awt.event.MouseListener listener : listeners)
		{
			listener.mousePressed(press);
		}
	}
}
