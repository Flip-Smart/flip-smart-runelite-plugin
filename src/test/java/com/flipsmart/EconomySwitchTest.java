package com.flipsmart;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.EnumSet;
import javax.swing.JLabel;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hop between a main world and a Hardcore World swaps economies. It must be handled like an
 * account switch: the outgoing economy's state saved under its own keys and released, the price
 * caches dropped, and the next login preloading the incoming economy.
 */
public class EconomySwitchTest
{
	private FlipSmartPlugin plugin;
	private PlayerSession session;
	private Client client;
	private FlipSmartApiClient apiClient;
	private OfflineSyncService offlineSync;
	private ConfigManager configManager;

	@Before
	public void setUp() throws Exception
	{
		plugin = new FlipSmartPlugin();
		session = new PlayerSession();
		session.setRsn("Zezima");
		client = mock(Client.class);
		apiClient = mock(FlipSmartApiClient.class);
		offlineSync = mock(OfflineSyncService.class);
		configManager = mock(ConfigManager.class);
		inject("client", client);
		inject("session", session);
		inject("apiClient", apiClient);
		inject("offlineSyncService", offlineSync);
		inject("configManager", configManager);
		inject("gson", new Gson());
		inject("geSlotDecorator", mock(GeSlotWidgetDecorator.class));
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = FlipSmartPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void world(WorldType... types)
	{
		EnumSet<WorldType> set = EnumSet.noneOf(WorldType.class);
		for (WorldType t : types)
		{
			set.add(t);
		}
		when(client.getWorldType()).thenReturn(set);
	}

	@Test
	public void hopToHardcoreReleasesMainStateUnderMainKeysThenSwapsEconomy()
	{
		session.onLoggedIn();
		world(WorldType.MEMBERS, WorldType.HARDCORE_MAIN);

		plugin.updateMembersWorldCache();

		InOrder order = inOrder(configManager, offlineSync, apiClient);
		order.verify(configManager).unsetConfiguration("flipsmart", "autoRecommendState_Zezima");
		order.verify(configManager).unsetConfiguration("flipsmart", "v9FlipState_Zezima");
		order.verify(offlineSync).releaseForLogout();
		order.verify(offlineSync).forgetEconomyState();
		order.verify(apiClient).setHardcoreWorld(true);
		order.verify(apiClient).clearCache();
		assertTrue(plugin.isHardcoreWorld());
	}

	@Test
	public void hopBackToMainReleasesHardcoreStateUnderHardcoreKeys()
	{
		session.setHardcoreWorld(true);
		session.onLoggedIn();
		world(WorldType.MEMBERS);

		plugin.updateMembersWorldCache();

		verify(configManager).unsetConfiguration("flipsmart", "autoRecommendState_Zezima_hc");
		verify(configManager).unsetConfiguration("flipsmart", "v9FlipState_Zezima_hc");
		verify(configManager, never()).unsetConfiguration("flipsmart", "autoRecommendState_Zezima");
		verify(apiClient).setHardcoreWorld(false);
		assertFalse(plugin.isHardcoreWorld());
	}

	@Test
	public void economyChangeAtTheLoginScreenSkipsTheReleaseLogoutAlreadyDid()
	{
		world(WorldType.HARDCORE_MAIN);

		plugin.updateMembersWorldCache();

		verify(offlineSync, never()).releaseForLogout();
		verify(offlineSync).forgetEconomyState();
		verify(apiClient).clearCache();
		assertTrue(plugin.isHardcoreWorld());
	}

	@Test
	public void sameEconomyHopLeavesStateAndCachesAlone()
	{
		session.onLoggedIn();
		world(WorldType.MEMBERS);

		plugin.updateMembersWorldCache();

		verify(offlineSync, never()).releaseForLogout();
		verify(offlineSync, never()).forgetEconomyState();
		verify(apiClient, never()).clearCache();
		verify(apiClient, never()).setHardcoreWorld(anyBoolean());
		assertFalse(plugin.isHardcoreWorld());
	}

	@Test
	public void hardcoreBadgeFollowsTheCurrentEconomy()
	{
		FlipSmartPlugin p = mock(FlipSmartPlugin.class);
		JLabel badge = new JLabel();

		when(p.isHardcoreWorld()).thenReturn(true);
		FlipFinderPanel.syncHardcoreBadge(badge, p);
		assertTrue(badge.isVisible());

		when(p.isHardcoreWorld()).thenReturn(false);
		FlipFinderPanel.syncHardcoreBadge(badge, p);
		assertFalse(badge.isVisible());
	}
}
