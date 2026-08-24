package com.flipsmart.plugin;

import com.flipsmart.BankSnapshotService;
import com.flipsmart.FlipSmartConfig;
import com.flipsmart.FlipSmartPlugin;
import com.flipsmart.GEHistoryService;
import com.flipsmart.GeOfferDescriptionService;
import com.flipsmart.GrandExchangeTracker;
import com.flipsmart.OfflineSyncService;
import com.flipsmart.PlayerSession;
import com.flipsmart.WebhookSyncService;
import com.flipsmart.trading.OfferStore;

import net.runelite.api.Client;
import net.runelite.client.events.ConfigChanged;

import org.junit.Before;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Enable Flip Finder" (the {@code showFlipFinder} config key) must add or remove the
 * sidebar panel live. It used to be read only in {@code startUp()}, so toggling it did
 * nothing until the plugin was disabled/re-enabled or the client restarted — a user who
 * turned it off to troubleshoot lost the panel with no config-only way back. Issue #1317.
 */
public class EventRouterConfigToggleTest
{
	private static final String GROUP = "flipsmart";

	private FlipSmartPlugin plugin;
	private FlipSmartConfig config;
	private EventRouter router;

	@Before
	public void setUp()
	{
		plugin = mock(FlipSmartPlugin.class);
		config = mock(FlipSmartConfig.class);
		router = new EventRouter(
			plugin,
			mock(Client.class),
			config,
			mock(PlayerSession.class),
			mock(WebhookSyncService.class),
			mock(OfflineSyncService.class),
			mock(BankSnapshotService.class),
			mock(GEHistoryService.class),
			mock(GeOfferDescriptionService.class),
			mock(GrandExchangeTracker.class),
			new OfferStore());
	}

	private static ConfigChanged change(String group, String key)
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(group);
		event.setKey(key);
		return event;
	}

	@Test
	public void enablingShowFlipFinderAddsPanelLive()
	{
		when(config.showFlipFinder()).thenReturn(true);

		router.onConfigChanged(change(GROUP, "showFlipFinder"));

		verify(plugin).showFlipFinderPanel();
		verify(plugin, never()).hideFlipFinderPanel();
	}

	@Test
	public void disablingShowFlipFinderRemovesPanelLive()
	{
		when(config.showFlipFinder()).thenReturn(false);

		router.onConfigChanged(change(GROUP, "showFlipFinder"));

		verify(plugin).hideFlipFinderPanel();
		verify(plugin, never()).showFlipFinderPanel();
	}

	@Test
	public void unrelatedKeyLeavesPanelVisibilityUntouched()
	{
		router.onConfigChanged(change(GROUP, "flipFinderLimit"));

		verify(plugin, never()).showFlipFinderPanel();
		verify(plugin, never()).hideFlipFinderPanel();
	}

	@Test
	public void otherConfigGroupIgnored()
	{
		router.onConfigChanged(change("grandexchange", "showFlipFinder"));

		verify(plugin, never()).showFlipFinderPanel();
		verify(plugin, never()).hideFlipFinderPanel();
	}
}
