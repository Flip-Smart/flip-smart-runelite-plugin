package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Exchange Viewer's suppression logic (Flip-Smart/flip-smart#915).
 * <p>
 * The overlay now shows regardless of world location and instead suppresses only
 * while a trade/inventory window is open on screen: the Grand Exchange, its
 * collection box, the bank, or the bank deposit box.
 */
public class GrandExchangeOverlayVisibilityTest
{
	private final Client client = mock(Client.class);

	/** Stub {@code getWidget(groupId, 0)} to return a root widget with the given hidden state. */
	private void openInterface(int groupId, boolean hidden)
	{
		Widget root = mock(Widget.class);
		when(root.isHidden()).thenReturn(hidden);
		when(client.getWidget(groupId, 0)).thenReturn(root);
	}

	@Test
	public void notSuppressedWhenNoTradeWindowOpen()
	{
		// Un-stubbed getWidget calls return null (nothing open).
		assertFalse(GrandExchangeOverlay.shouldSuppressOverlay(client));
	}

	@Test
	public void suppressedWhenGrandExchangeOpen()
	{
		openInterface(InterfaceID.GE_OFFERS, false);
		assertTrue(GrandExchangeOverlay.shouldSuppressOverlay(client));
	}

	@Test
	public void suppressedWhenCollectionBoxOpen()
	{
		openInterface(InterfaceID.GE_COLLECT, false);
		assertTrue(GrandExchangeOverlay.shouldSuppressOverlay(client));
	}

	@Test
	public void suppressedWhenBankOpen()
	{
		openInterface(InterfaceID.BANKMAIN, false);
		assertTrue(GrandExchangeOverlay.shouldSuppressOverlay(client));
	}

	@Test
	public void suppressedWhenDepositBoxOpen()
	{
		openInterface(InterfaceID.BANK_DEPOSITBOX, false);
		assertTrue(GrandExchangeOverlay.shouldSuppressOverlay(client));
	}

	@Test
	public void notSuppressedWhenWindowWidgetPresentButHidden()
	{
		openInterface(InterfaceID.GE_OFFERS, true);
		openInterface(InterfaceID.BANKMAIN, true);
		assertFalse(GrandExchangeOverlay.shouldSuppressOverlay(client));
	}
}
