package com.flipsmart;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;

/**
 * Service for syncing webhook configuration between plugin and backend.
 * When webhook URL or preferences change in the plugin config, they are
 * synced to the backend. On startup, backend config is pulled to the plugin.
 */
@Slf4j
@Singleton
public class WebhookSyncService
{
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String JSON_WEBHOOK_URL = "webhook_url";
	private static final String JSON_NOTIFY_SALE = "notify_sale_completed";
	private static final String JSON_NOTIFY_SUGGESTION = "notify_flip_suggestion";

	private final FlipSmartConfig config;
	private final FlipSmartApiClient apiClient;
	private final ConfigManager configManager;

	private String lastSyncedWebhookUrl = null;
	private boolean lastSyncedNotifySale = false;
	private boolean lastSyncedNotifySuggestion = false;
	private String lastPulledBackendUrl = null;

	@Inject
	public WebhookSyncService(
		FlipSmartConfig config,
		FlipSmartApiClient apiClient,
		ConfigManager configManager
	)
	{
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
	}

	private static String extractUrl(JsonObject webhookConfig)
	{
		return webhookConfig.has(JSON_WEBHOOK_URL)
			? webhookConfig.get(JSON_WEBHOOK_URL).getAsString() : "";
	}

	private static boolean extractNotifySale(JsonObject webhookConfig)
	{
		return webhookConfig.has(JSON_NOTIFY_SALE)
			&& webhookConfig.get(JSON_NOTIFY_SALE).getAsBoolean();
	}

	private static boolean extractNotifySuggestion(JsonObject webhookConfig)
	{
		return webhookConfig.has(JSON_NOTIFY_SUGGESTION)
			&& webhookConfig.get(JSON_NOTIFY_SUGGESTION).getAsBoolean();
	}

	/**
	 * Apply backend webhook config to the plugin's local config and update cached state.
	 */
	private void applyBackendConfig(String backendUrl, boolean notifySale, boolean notifySuggestion)
	{
		// Sync notification preferences to plugin config (not the URL -- always fetch from API)
		configManager.setConfiguration(CONFIG_GROUP, "notifySaleCompleted", String.valueOf(notifySale));
		configManager.setConfiguration(CONFIG_GROUP, "notifyFlipSuggestion", String.valueOf(notifySuggestion));

		lastPulledBackendUrl = backendUrl;
		lastSyncedWebhookUrl = backendUrl;
		lastSyncedNotifySale = notifySale;
		lastSyncedNotifySuggestion = notifySuggestion;
	}

	/**
	 * Pull webhook config from backend and update plugin config if needed.
	 * Called on startup to ensure parity between web dashboard and plugin.
	 */
	public void pullFromBackend()
	{
		apiClient.fetchWebhookConfigAsync(
			webhookConfig -> applyBackendConfig(
				extractUrl(webhookConfig),
				extractNotifySale(webhookConfig),
				extractNotifySuggestion(webhookConfig)
			),
			() -> log.debug("No webhook configured on backend"),
			error -> log.warn("Failed to pull webhook config from backend: {}", error)
		);
	}

	/**
	 * Pull config from backend. Called periodically to keep plugin in sync.
	 */
	public void pullAndSync()
	{
		pullFromBackend();
	}

	/**
	 * Check if webhook config has changed and sync if needed.
	 */
	public void syncIfChanged()
	{
		String webhookUrl = config.discordWebhookUrl();
		boolean notifySale = config.notifySaleCompleted();
		boolean notifySuggestion = false;

		boolean hasChanged = !Objects.equals(webhookUrl, lastSyncedWebhookUrl)
			|| notifySale != lastSyncedNotifySale
			|| notifySuggestion != lastSyncedNotifySuggestion;

		// Skip if the URL matches what we just pulled from backend (no real change)
		if (hasChanged && Objects.equals(webhookUrl, lastPulledBackendUrl)
			&& notifySale == lastSyncedNotifySale
			&& notifySuggestion == lastSyncedNotifySuggestion)
		{
			lastSyncedWebhookUrl = webhookUrl;
			return;
		}

		if (!hasChanged)
		{
			return;
		}

		// If webhook URL is empty, delete webhook from backend
		if (webhookUrl == null || webhookUrl.trim().isEmpty())
		{
			log.info("Webhook URL cleared in plugin config (lastSynced={})", lastSyncedWebhookUrl);
			if (lastSyncedWebhookUrl != null && !lastSyncedWebhookUrl.trim().isEmpty())
			{
				deleteWebhook();
			}
			return;
		}

		syncToBackend(webhookUrl, notifySale, notifySuggestion);
	}

	private void syncToBackend(String webhookUrl, boolean notifySale, boolean notifySuggestion)
	{
		log.debug("Syncing webhook config to backend");

		apiClient.updateWebhookAsync(
			webhookUrl,
			notifySale,
			notifySuggestion,
			() -> {
				log.info("Webhook config synced to backend successfully");
				lastSyncedWebhookUrl = webhookUrl;
				lastSyncedNotifySale = notifySale;
				lastSyncedNotifySuggestion = notifySuggestion;
			},
			error -> log.warn("Failed to sync webhook config to backend: {}", error)
		);
	}

	private void deleteWebhook()
	{
		log.debug("Deleting webhook from backend");

		apiClient.deleteWebhookAsync(
			() -> {
				log.info("Webhook deleted from backend successfully");
				lastSyncedWebhookUrl = null;
				lastSyncedNotifySale = false;
				lastSyncedNotifySuggestion = false;
			},
			error -> log.warn("Failed to delete webhook from backend: {}", error)
		);
	}
}
