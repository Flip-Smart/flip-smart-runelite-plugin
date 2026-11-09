package com.flipsmart;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.runelite.api.Client;
import net.runelite.api.GameState;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coins are reported from both worlds; the request's world header (pinned when the request is
 * made) files a Hardcore reading under Hardcore, so it never overwrites main capital.
 */
public class HardcoreCapitalIsolationTest
{
	private FlipSmartPlugin plugin;
	private FlipSmartApiClient apiClient;
	private PlayerSession session;

	@Before
	public void setUp() throws Exception
	{
		plugin = new FlipSmartPlugin();
		apiClient = mock(FlipSmartApiClient.class);
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		session = new PlayerSession();
		session.setRsn("TestPlayer");
		session.onLoggedIn();
		session.setCashStack(5_000_000L);
		inject("apiClient", apiClient);
		inject("client", client);
		inject("session", session);
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = FlipSmartPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void pushFinalCapital() throws Exception
	{
		Method m = FlipSmartPlugin.class.getDeclaredMethod("pushFinalCapitalSnapshot");
		m.setAccessible(true);
		m.invoke(plugin);
	}

	@Test
	public void mainWorldCoinsAreReported() throws Exception
	{
		pushFinalCapital();
		verify(apiClient).pushRsnCapitalAsync("TestPlayer", 5_000_000L);
	}

	@Test
	public void hardcoreCoinsAreReportedForHardcoresOwnCapital() throws Exception
	{
		session.setHardcoreWorld(true);
		pushFinalCapital();
		verify(apiClient).pushRsnCapitalAsync("TestPlayer", 5_000_000L);
	}

	@Test
	public void theSyncPollReportsCoinsInBothWorlds()
	{
		assertEquals(Long.valueOf(5_000_000L), plugin.reportedInventoryGp());
		session.setHardcoreWorld(true);
		assertEquals(Long.valueOf(5_000_000L), plugin.reportedInventoryGp());
	}
}
