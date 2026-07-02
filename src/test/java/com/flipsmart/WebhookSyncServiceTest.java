package com.flipsmart;

import com.google.gson.JsonObject;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class WebhookSyncServiceTest
{
	private FlipSmartConfig config;
	private FlipSmartApiClient apiClient;
	private ConfigManager configManager;
	private WebhookSyncService service;

	@Before
	public void setUp()
	{
		config = mock(FlipSmartConfig.class);
		apiClient = mock(FlipSmartApiClient.class);
		configManager = mock(ConfigManager.class);

		when(apiClient.isAuthenticated()).thenReturn(true);
		when(config.discordWebhookUrl()).thenReturn("");
		when(config.notifySaleCompleted()).thenReturn(false);

		service = new WebhookSyncService(config, apiClient, configManager);
	}

	private void stubFetchReturning(String url, boolean notifySale, boolean notifySuggestion)
	{
		doAnswer(inv -> {
			JsonObject webhookConfig = new JsonObject();
			webhookConfig.addProperty("webhook_url", url);
			webhookConfig.addProperty("notify_sale_completed", notifySale);
			webhookConfig.addProperty("notify_flip_suggestion", notifySuggestion);
			Consumer<JsonObject> onSuccess = inv.getArgument(0);
			onSuccess.accept(webhookConfig);
			return null;
		}).when(apiClient).fetchWebhookConfigAsync(any(), any(), any());
	}

	@Test
	public void pullFromBackendFetchesOnceWhenAuthenticated()
	{
		stubFetchReturning("https://discord.com/api/webhooks/1/a", true, false);
		service.pullFromBackend();
		verify(apiClient, times(1)).fetchWebhookConfigAsync(any(), any(), any());
	}

	@Test
	public void pullFromBackendAppliesBackendPrefsToConfig()
	{
		stubFetchReturning("https://discord.com/api/webhooks/1/a", true, false);
		service.pullFromBackend();
		verify(configManager).setConfiguration("flipsmart", "notifySaleCompleted", "true");
		verify(configManager).setConfiguration("flipsmart", "notifyFlipSuggestion", "false");
	}

	@Test
	public void pullFromBackendNoApiCallWhenNotAuthenticated()
	{
		when(apiClient.isAuthenticated()).thenReturn(false);
		service.pullFromBackend();
		verify(apiClient, never()).fetchWebhookConfigAsync(any(), any(), any());
	}

	@Test
	public void syncIfChangedPushesNewUrlWhenAuthenticated()
	{
		when(config.discordWebhookUrl()).thenReturn("https://discord.com/api/webhooks/2/b");
		when(config.notifySaleCompleted()).thenReturn(true);

		service.syncIfChanged();

		verify(apiClient, times(1)).updateWebhookAsync(
			eq("https://discord.com/api/webhooks/2/b"), eq(true), eq(false), any(), any());
	}

	@Test
	public void syncIfChangedNoApiCallWhenNotAuthenticated()
	{
		when(apiClient.isAuthenticated()).thenReturn(false);
		when(config.discordWebhookUrl()).thenReturn("https://discord.com/api/webhooks/2/b");

		service.syncIfChanged();

		verify(apiClient, never()).updateWebhookAsync(anyString(), anyBoolean(), anyBoolean(), any(), any());
		verify(apiClient, never()).deleteWebhookAsync(any(), any());
	}

	@Test
	public void syncIfChangedNoApiCallWhenNothingChanged()
	{
		service.syncIfChanged();
		verify(apiClient, never()).updateWebhookAsync(anyString(), anyBoolean(), anyBoolean(), any(), any());
		verify(apiClient, never()).deleteWebhookAsync(any(), any());
	}

	@Test
	public void syncIfChangedSkipsPushWhenUrlMatchesPulledBackendValue()
	{
		String url = "https://discord.com/api/webhooks/1/a";
		stubFetchReturning(url, false, false);
		service.pullFromBackend();

		when(config.discordWebhookUrl()).thenReturn(url);
		service.syncIfChanged();

		verify(apiClient, never()).updateWebhookAsync(anyString(), anyBoolean(), anyBoolean(), any(), any());
	}

	@Test
	public void syncIfChangedDeletesWhenUrlClearedAfterSuccessfulSync()
	{
		when(config.discordWebhookUrl()).thenReturn("https://discord.com/api/webhooks/2/b");
		doAnswer(inv -> {
			Runnable onSuccess = inv.getArgument(3);
			onSuccess.run();
			return null;
		}).when(apiClient).updateWebhookAsync(anyString(), anyBoolean(), anyBoolean(), any(), any());
		service.syncIfChanged();

		when(config.discordWebhookUrl()).thenReturn("");
		service.syncIfChanged();

		verify(apiClient, times(1)).deleteWebhookAsync(any(), any());
	}

	@Test
	public void serviceIsInertWithoutExplicitTrigger()
	{
		verifyNoInteractions(apiClient);
	}

	@Test
	public void repeatedPullsEachRequireExplicitInvocation()
	{
		stubFetchReturning("https://discord.com/api/webhooks/1/a", false, false);
		service.pullFromBackend();
		service.pullFromBackend();
		verify(apiClient, times(2)).fetchWebhookConfigAsync(any(), any(), any());
	}

	@Test
	public void hasNoPeriodicSyncEntryPoint()
	{
		try
		{
			WebhookSyncService.class.getMethod("pullAndSync");
			throw new AssertionError("pullAndSync should not exist; webhook config is pulled on login only");
		}
		catch (NoSuchMethodException expected)
		{
			// The periodic-refresh entry point was removed intentionally.
		}
	}
}
