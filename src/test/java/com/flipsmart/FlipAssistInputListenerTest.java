package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.awt.Canvas;
import java.awt.event.KeyEvent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class FlipAssistInputListenerTest
{
	@Mock private Client client;
	@Mock private ClientThread clientThread;
	@Mock private FlipSmartConfig config;
	@Mock private FlipAssistOverlay overlay;
	@Mock private FocusedFlip focusedFlip;

	private FlipAssistInputListener listener;
	private final Canvas source = new Canvas();

	@Before
	public void setUp()
	{
		MockitoAnnotations.openMocks(this);
		listener = new FlipAssistInputListener(client, clientThread, config, overlay);
		when(config.enableFlipAssistant()).thenReturn(true);
		when(config.flipAssistHotkey()).thenReturn(new Keybind(KeyEvent.VK_E, 0));
		when(overlay.getFocusedFlip()).thenReturn(focusedFlip);
		when(focusedFlip.getItemName()).thenReturn("Rune arrows");
	}

	private KeyEvent hotkeyPress()
	{
		// A synthetic KeyEvent never gets an extendedKeyCode (only the native peer
		// sets it), but Keybind.matches keys off getExtendedKeyCode() — so override it.
		return new KeyEvent(source, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_E, 'e')
		{
			@Override
			public int getExtendedKeyCode()
			{
				return KeyEvent.VK_E;
			}
		};
	}

	// Regression for #1348/#616: the hotkey character must reach the GE item-search box
	// so item names are typed intact. The player has typed a prefix of the focused item
	// ("Run" of "Rune arrows"); the next hotkey letter "E" must NOT be swallowed.
	@Test
	public void hotkeyNotConsumedOnItemSearchScreen()
	{
		listener.updateInputType(FlipAssistInputListener.INPUT_TYPE_GE_ITEM_SEARCH);
		KeyEvent e = hotkeyPress();
		listener.keyPressed(e);
		assertFalse("hotkey char must pass through to the GE search box", e.isConsumed());
	}

	// The hotkey is still intercepted on the numeric price/quantity entry screen,
	// which is the only screen it should act on.
	@Test
	public void hotkeyConsumedOnNumericInputScreen()
	{
		listener.updateInputType(7); // INPUT_TYPE_NUMERIC
		KeyEvent e = hotkeyPress();
		listener.keyPressed(e);
		assertTrue("hotkey must be consumed on the price/quantity screen", e.isConsumed());
	}
}
