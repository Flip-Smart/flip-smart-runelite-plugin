package com.flipsmart.plugin;

import com.flipsmart.BankSnapshotService;
import com.flipsmart.FlipSmartConfig;
import com.flipsmart.FlipSmartPlugin;
import com.flipsmart.FlipSmartApiClient;
import com.flipsmart.GEHistoryService;
import com.flipsmart.GeOfferDescriptionService;
import com.flipsmart.GrandExchangeTracker;
import com.flipsmart.OfflineSyncService;
import com.flipsmart.PlayerSession;
import com.flipsmart.WebhookSyncService;
import com.flipsmart.trading.OfferStore;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.chat.ChatMessageManager;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The GE History read latch has to be cleared on every transition that clears
 * {@code offlineSyncCompleted}, not only on a full logout.
 *
 * <p>{@code HOPPING} and {@code CONNECTION_LOST} reset the session's sync flag so the
 * offline sync runs again on the following {@code LOGGED_IN}, but
 * {@code GEHistoryService.reset()} used to be reachable only from {@code LOGIN_SCREEN}.
 * The latch therefore survived, and {@code registerOfflineFill} short-circuits on it — so
 * once a player had opened the History tab at any point in a session, every subsequent
 * world hop and reconnect dropped its offline fills with no prompt and no scrape.</p>
 */
public class EventRouterHopResetTest
{
	private GEHistoryService geHistoryService;
	private EventRouter router;

	/** A visible History list holding one "Bought:" row (4151 x5 @ 2,000). */
	private Widget visibleHistoryList()
	{
		Widget header = mock(Widget.class);
		when(header.getText()).thenReturn("Bought:");
		Widget item = mock(Widget.class);
		when(item.getItemId()).thenReturn(4151);
		when(item.getItemQuantity()).thenReturn(5);
		Widget price = mock(Widget.class);
		when(price.getText()).thenReturn("<col=ffb83f>10,000 coins</col><br>= 2,000 each");

		Widget list = mock(Widget.class);
		when(list.isHidden()).thenReturn(false);
		when(list.getDynamicChildren()).thenReturn(new Widget[]{header, item, price});
		return list;
	}

	@Before
	public void setUp()
	{
		Client client = mock(Client.class);
		// Built before the outer stubbing call: Mockito rejects a nested when() that lands
		// inside an in-progress one.
		Widget historyList = visibleHistoryList();
		when(client.getWidget(InterfaceID.GE_HISTORY, 3)).thenReturn(historyList);

		geHistoryService = new GEHistoryService(client, mock(FlipSmartApiClient.class),
			mock(PlayerSession.class), mock(ChatMessageManager.class), new OfferStore());

		router = new EventRouter(
			mock(FlipSmartPlugin.class),
			client,
			mock(FlipSmartConfig.class),
			mock(PlayerSession.class),
			mock(WebhookSyncService.class),
			mock(OfflineSyncService.class),
			mock(BankSnapshotService.class),
			geHistoryService,
			mock(GeOfferDescriptionService.class),
			mock(GrandExchangeTracker.class),
			new OfferStore());
	}

	private static GameStateChanged state(GameState gameState)
	{
		GameStateChanged event = new GameStateChanged();
		event.setGameState(gameState);
		return event;
	}

	/** Read the History tab so the latch is set, exactly as a real session would. */
	private void latchHistoryRead()
	{
		geHistoryService.onHistoryWidgetLoaded();
		geHistoryService.onGameTick();
		geHistoryService.onGameTick();
		geHistoryService.registerOfflineFill(4151);
		assertFalse("precondition: a completed read latches the flag",
			geHistoryService.hasUnverifiedOfflineFills());
	}

	@Test
	public void worldHopClearsHistoryLatchSoLaterOfflineFillsRegister()
	{
		latchHistoryRead();

		router.onGameStateChanged(state(GameState.HOPPING));
		geHistoryService.registerOfflineFill(4151);

		assertTrue(geHistoryService.hasUnverifiedOfflineFills());
	}

	@Test
	public void connectionLostClearsHistoryLatchSoLaterOfflineFillsRegister()
	{
		latchHistoryRead();

		router.onGameStateChanged(state(GameState.CONNECTION_LOST));
		geHistoryService.registerOfflineFill(4151);

		assertTrue(geHistoryService.hasUnverifiedOfflineFills());
	}
}
