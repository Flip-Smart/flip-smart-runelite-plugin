package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MotdServiceTest
{
	private Client client;
	private FlipSmartConfig config;
	private FlipSmartApiClient apiClient;
	private ChatMessageManager chatMessageManager;
	private ClientThread clientThread;
	private ConfigManager configManager;
	private MotdService service;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		config = mock(FlipSmartConfig.class);
		apiClient = mock(FlipSmartApiClient.class);
		chatMessageManager = mock(ChatMessageManager.class);
		clientThread = mock(ClientThread.class);
		configManager = mock(ConfigManager.class);

		// Run lambda passed to clientThread.invokeLater synchronously.
		doAnswer(inv -> {
			Runnable r = inv.getArgument(0);
			r.run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		when(config.motdEnabled()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(configManager.getConfiguration("flipsmart", "motd.lastShownVersion")).thenReturn(null);

		service = new MotdService(client, config, apiClient, chatMessageManager, clientThread, configManager);
	}

	private FlipSmartApiClient.MotdResponse buildResponse(String pluginMessage, boolean enabled, String version)
	{
		FlipSmartApiClient.MotdChannelData plugin = mock(FlipSmartApiClient.MotdChannelData.class);
		when(plugin.getMessage()).thenReturn(pluginMessage);
		when(plugin.isEnabled()).thenReturn(enabled);
		when(plugin.getVersion()).thenReturn(version);

		FlipSmartApiClient.MotdChannelData web = mock(FlipSmartApiClient.MotdChannelData.class);
		FlipSmartApiClient.MotdResponse resp = mock(FlipSmartApiClient.MotdResponse.class);
		when(resp.getWeb()).thenReturn(web);
		when(resp.getPlugin()).thenReturn(plugin);
		return resp;
	}

	@Test
	public void postsChatWhenNewVersionAndLoggedIn()
	{
		service.handleResponse(buildResponse("Hello world", true, "v1"));
		verify(chatMessageManager, times(1)).queue(any());
		verify(configManager, times(1))
			.setConfiguration(eq("flipsmart"), eq("motd.lastShownVersion"), eq("v1"));
	}

	@Test
	public void doesNotPostTwiceForSameVersion()
	{
		service.handleResponse(buildResponse("Hi", true, "v1"));
		// Simulate that the persisted version is now v1 — subsequent reads return it.
		when(configManager.getConfiguration("flipsmart", "motd.lastShownVersion")).thenReturn("v1");
		service.handleResponse(buildResponse("Hi", true, "v1"));
		verify(chatMessageManager, times(1)).queue(any());
	}

	@Test
	public void postsAgainOnNewVersion()
	{
		service.handleResponse(buildResponse("Hi", true, "v1"));
		when(configManager.getConfiguration("flipsmart", "motd.lastShownVersion")).thenReturn("v1");
		service.handleResponse(buildResponse("Hi updated", true, "v2"));
		verify(chatMessageManager, times(2)).queue(any());
	}

	@Test
	public void noOpWhenChannelDisabled()
	{
		service.handleResponse(buildResponse("Hi", false, "v1"));
		verify(chatMessageManager, never()).queue(any());
	}

	@Test
	public void noOpWhenMessageEmpty()
	{
		service.handleResponse(buildResponse("", true, "v1"));
		verify(chatMessageManager, never()).queue(any());
	}

	@Test
	public void noOpWhenConfigToggleOff()
	{
		when(config.motdEnabled()).thenReturn(false);
		service.handleResponse(buildResponse("Hi", true, "v1"));
		verify(chatMessageManager, never()).queue(any());
	}

	@Test
	public void deferredWhenNotLoggedIn()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		service.handleResponse(buildResponse("Hi", true, "v1"));
		verify(chatMessageManager, never()).queue(any());
		verify(configManager, never()).setConfiguration(any(), any(), (String) any());
	}

	@Test
	public void onLoginPostsEveryTimeRegardlessOfPersistedVersion()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		service.handleResponse(buildResponse("Welcome back", true, "v1"));
		verify(chatMessageManager, never()).queue(any());

		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		service.onLogin();
		verify(chatMessageManager, times(1)).queue(any());

		// A second login event with the same persisted version SHOULD now re-post.
		when(configManager.getConfiguration("flipsmart", "motd.lastShownVersion")).thenReturn("v1");
		service.onLogin();
		verify(chatMessageManager, times(2)).queue(any());
	}

	@Test
	public void onLoginDoesNotUpdatePersistedVersion()
	{
		service.handleResponse(buildResponse("Hi", true, "v1"));
		// First poll already wrote v1 to lastShownVersion. Reset the mock so the
		// counter here only reflects the upcoming onLogin() call.
		org.mockito.Mockito.clearInvocations(configManager);
		when(configManager.getConfiguration("flipsmart", "motd.lastShownVersion")).thenReturn("v1");
		service.onLogin();
		verify(configManager, never()).setConfiguration(any(), any(), (String) any());
	}

	@Test
	public void onLoginNoOpWhenChannelDisabled()
	{
		service.handleResponse(buildResponse("Hi", false, "v1"));
		service.onLogin();
		verify(chatMessageManager, never()).queue(any());
	}

	@Test
	public void onLoginNoOpWhenConfigToggleOff()
	{
		when(config.motdEnabled()).thenReturn(false);
		service.handleResponse(buildResponse("Hi", true, "v1"));
		service.onLogin();
		verify(chatMessageManager, never()).queue(any());
	}
}
