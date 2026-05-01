package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

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
		when(apiClient.getMotdAsync()).thenReturn(CompletableFuture.completedFuture(null));

		service = new MotdService(client, config, apiClient, chatMessageManager, clientThread, configManager);
	}

	private FlipSmartApiClient.MotdResponse buildResponse(String pluginMessage, boolean enabled, String version)
	{
		return buildResponse(pluginMessage, enabled, version, "normal");
	}

	private FlipSmartApiClient.MotdResponse buildResponse(String pluginMessage, boolean enabled, String version, String severity)
	{
		FlipSmartApiClient.MotdChannelData plugin = mock(FlipSmartApiClient.MotdChannelData.class);
		when(plugin.getMessage()).thenReturn(pluginMessage);
		when(plugin.isEnabled()).thenReturn(enabled);
		when(plugin.getVersion()).thenReturn(version);
		when(plugin.getSeverity()).thenReturn(severity);

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
		FlipSmartApiClient.MotdResponse resp = buildResponse("Welcome back", true, "v1");

		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		service.handleResponse(resp);
		verify(chatMessageManager, never()).queue(any());

		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(apiClient.getMotdAsync()).thenReturn(CompletableFuture.completedFuture(resp));
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
		FlipSmartApiClient.MotdResponse resp = buildResponse("Hi", true, "v1");
		service.handleResponse(resp);
		org.mockito.Mockito.clearInvocations(configManager);
		when(configManager.getConfiguration("flipsmart", "motd.lastShownVersion")).thenReturn("v1");
		when(apiClient.getMotdAsync()).thenReturn(CompletableFuture.completedFuture(resp));
		service.onLogin();
		verify(configManager, never()).setConfiguration(any(), any(), (String) any());
	}

	@Test
	public void onLoginNoOpWhenChannelDisabled()
	{
		FlipSmartApiClient.MotdResponse resp = buildResponse("Hi", false, "v1");
		service.handleResponse(resp);
		when(apiClient.getMotdAsync()).thenReturn(CompletableFuture.completedFuture(resp));
		service.onLogin();
		verify(chatMessageManager, never()).queue(any());
	}

	@Test
	public void onLoginNoOpWhenConfigToggleOff()
	{
		when(config.motdEnabled()).thenReturn(false);
		FlipSmartApiClient.MotdResponse resp = buildResponse("Hi", true, "v1");
		service.handleResponse(resp);
		when(apiClient.getMotdAsync()).thenReturn(CompletableFuture.completedFuture(resp));
		service.onLogin();
		verify(chatMessageManager, never()).queue(any());
	}

	@Test
	public void onLoginUsesFreshFetchEvenWhenCachedSaysEnabled()
	{
		FlipSmartApiClient.MotdResponse cachedEnabled = buildResponse("Old hi", true, "v1");
		service.handleResponse(cachedEnabled);
		org.mockito.Mockito.clearInvocations(chatMessageManager);

		FlipSmartApiClient.MotdResponse freshDisabled = buildResponse("Old hi", false, "v1");
		when(apiClient.getMotdAsync()).thenReturn(CompletableFuture.completedFuture(freshDisabled));

		service.onLogin();
		verify(chatMessageManager, never()).queue(any());
	}

	@Test
	public void postsSuccessfullyForNormalSeverity()
	{
		service.handleResponse(buildResponse("Normal msg", true, "v1", "normal"));
		verify(chatMessageManager, times(1)).queue(any());
	}

	@Test
	public void postsSuccessfullyForWarningSeverity()
	{
		service.handleResponse(buildResponse("Warning msg", true, "v1", "warning"));
		verify(chatMessageManager, times(1)).queue(any());
	}

	@Test
	public void postsSuccessfullyForAlertSeverity()
	{
		service.handleResponse(buildResponse("Alert msg", true, "v1", "alert"));
		verify(chatMessageManager, times(1)).queue(any());
	}

	@Test
	public void postsSuccessfullyForUnknownSeverityFallingBackToNormal()
	{
		service.handleResponse(buildResponse("Unknown msg", true, "v1", "neon"));
		verify(chatMessageManager, times(1)).queue(any());
	}
}
