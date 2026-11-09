package com.flipsmart;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.runelite.api.Client;
import net.runelite.api.GameState;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Coins on a Hardcore character are never reported as the player's main-game capital. */
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
	public void hardcoreCoinsAreNotReported() throws Exception
	{
		session.setHardcoreWorld(true);
		pushFinalCapital();
		verify(apiClient, never()).pushRsnCapitalAsync(anyString(), anyLong());
	}

	@Test
	public void theSyncPollReportsNoCoinsFromHardcore()
	{
		assertEquals(Long.valueOf(5_000_000L), plugin.reportedInventoryGp());
		session.setHardcoreWorld(true);
		assertNull(plugin.reportedInventoryGp());
	}
}
