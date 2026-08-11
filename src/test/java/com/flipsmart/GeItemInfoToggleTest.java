package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The "Show Item Info" setting (#1222), which lets a player keep the native GE
 * offer screen instead of our breakeven/tax/profit description.
 *
 * <p>Turning it off is implemented as "stop writing" rather than "undo what we
 * wrote": Jagex's own script repaints the description on the next panel build,
 * so the native text returns without us restoring it by hand.</p>
 */
public class GeItemInfoToggleTest
{
	private static final int CANNONBALL_ID = 2;
	private static final String NATIVE_TEXT = "Cannonball<br>A heavy metal ball.";

	private Client client;
	private FlipSmartConfig config;
	private Object[] objectStack;
	private GeOfferDescriptionService service;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		config = mock(FlipSmartConfig.class);

		FlipSmartApiClient apiClient = mock(FlipSmartApiClient.class);
		when(apiClient.getDailyVolumeAsync(anyInt())).thenReturn(CompletableFuture.completedFuture(null));

		objectStack = new Object[]{NATIVE_TEXT};
		when(client.getObjectStack()).thenReturn(objectStack);
		when(client.getObjectStackSize()).thenReturn(1);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(CANNONBALL_ID);

		service = new GeOfferDescriptionService(
			client,
			mock(ClientThread.class),
			apiClient,
			mock(FlipSmartPlugin.class),
			mock(ItemManager.class),
			mock(FlipAssistOverlay.class),
			mock(ConfigManager.class),
			config);
	}

	private static ScriptCallbackEvent buyExamine()
	{
		ScriptCallbackEvent event = mock(ScriptCallbackEvent.class);
		when(event.getEventName()).thenReturn(GeOfferDescriptionService.EVENT_BUY_EXAMINE);
		return event;
	}

	// -----------------------------------------------------------------
	// AC2 — on (default) behaves exactly as before
	// -----------------------------------------------------------------

	@Test
	public void ourDescriptionReplacesTheNativeTextWhenTheSettingIsOn()
	{
		when(config.showGeItemInfo()).thenReturn(true);

		assertEquals("the buy-examine callback is ours to handle", true,
			service.onScriptCallbackEvent(buyExamine()));
		assertNotEquals("the native examine text is replaced", NATIVE_TEXT, objectStack[0]);
	}

	@Test
	public void theNativeFeeAndGraphicAreHiddenWhenTheSettingIsOn()
	{
		when(config.showGeItemInfo()).thenReturn(true);
		Widget fee = mock(Widget.class);
		when(client.getWidget(InterfaceID.GeOffers.SETUP_FEE)).thenReturn(fee);

		service.onSetupBuildScriptPostFired();

		verify(fee).setHidden(true);
	}

	// -----------------------------------------------------------------
	// AC3 — off leaves the GE screen untouched
	// -----------------------------------------------------------------

	@Test
	public void theNativeExamineTextSurvivesWhenTheSettingIsOff()
	{
		when(config.showGeItemInfo()).thenReturn(false);

		assertEquals("the callback is declined so the game keeps its own text", false,
			service.onScriptCallbackEvent(buyExamine()));
		assertEquals("the native examine text is left in place", NATIVE_TEXT, objectStack[0]);
	}

	@Test
	public void theNativeFeeAndGraphicStayVisibleWhenTheSettingIsOff()
	{
		when(config.showGeItemInfo()).thenReturn(false);
		Widget fee = mock(Widget.class);
		when(client.getWidget(InterfaceID.GeOffers.SETUP_FEE)).thenReturn(fee);

		service.onSetupBuildScriptPostFired();

		verify(fee, never()).setHidden(true);
	}

	@Test
	public void theOfferStatusPanelIsNotTouchedWhenTheSettingIsOff()
	{
		when(config.showGeItemInfo()).thenReturn(false);

		service.onBeforeRender(mock(BeforeRender.class));

		// Reading no widget at all is the strongest available proof that the
		// per-frame description write never starts.
		verify(client, never()).getWidget(anyInt());
	}
}
