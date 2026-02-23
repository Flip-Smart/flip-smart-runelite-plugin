package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Service for syncing webhook configuration from plugin to backend.
 * When webhook URL or preferences change in the plugin config, they are
 * synced to the backend so both the plugin and Discord bot can access them.
 */
@Slf4j
@Singleton
public class WebhookSyncService
{
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

	/**
	 * Pull webhook config from backend and update plugin config if needed.
	 * Called on startup to ensure parity between web dashboard and plugin.
	 */
	public void pullFromBackend()
	{
		apiClient.fetchWebhookConfigAsync(
			webhookConfig -> {
				String backendUrl = webhookConfig.has("webhook_url")
					? webhookConfig.get("webhook_url").getAsString() : "";
				boolean enabled = webhookConfig.has("enabled")
					&& webhookConfig.get("enabled").getAsBoolean();
				boolean notifySale = webhookConfig.has("notify_sale_completed")
					&& webhookConfig.get("notify_sale_completed").getAsBoolean();
				boolean notifySuggestion = webhookConfig.has("notify_flip_suggestion")
					&& webhookConfig.get("notify_flip_suggestion").getAsBoolean();

				// Sync backend config to plugin
				String currentUrl = config.discordWebhookUrl();
				if (!backendUrl.isEmpty() && !backendUrl.equals(currentUrl))
				{
					configManager.setConfiguration(
						"flipsmart",
						"discordWebhookUrl",
						backendUrl
					);
					log.info("Synced webhook URL from backend to plugin");
				}
				configManager.setConfiguration(
					"flipsmart",
					"notifySaleCompleted",
					String.valueOf(notifySale)
				);
				configManager.setConfiguration(
					"flipsmart",
					"notifyFlipSuggestion",
					String.valueOf(notifySuggestion)
				);

				// Track what the backend has
				lastPulledBackendUrl = backendUrl;

				// Update cached state
				lastSyncedWebhookUrl = backendUrl.isEmpty() ? currentUrl : backendUrl;
				lastSyncedNotifySale = notifySale;
				lastSyncedNotifySuggestion = notifySuggestion;
			},
			() -> {
				// No webhook on backend
				log.debug("No webhook configured on backend");
			},
			error -> {
				log.warn("Failed to pull webhook config from backend: {}", error);
			}
		);
	}

	/**
	 * Pull config from backend, then send heartbeat if webhook is configured.
	 * Serialized to avoid concurrent auth refresh race conditions.
	 */
	public void pullAndHeartbeat()
	{
		apiClient.fetchWebhookConfigAsync(
			webhookConfig -> {
				// Process pull response (same as pullFromBackend)
				String backendUrl = webhookConfig.has("webhook_url")
					? webhookConfig.get("webhook_url").getAsString() : "";
				boolean notifySale = webhookConfig.has("notify_sale_completed")
					&& webhookConfig.get("notify_sale_completed").getAsBoolean();

				String currentUrl = config.discordWebhookUrl();
				if (!backendUrl.isEmpty() && !backendUrl.equals(currentUrl))
				{
					configManager.setConfiguration("flipsmart", "discordWebhookUrl", backendUrl);
				}
				configManager.setConfiguration("flipsmart", "notifySaleCompleted", String.valueOf(notifySale));

				lastPulledBackendUrl = backendUrl;
				lastSyncedWebhookUrl = backendUrl.isEmpty() ? currentUrl : backendUrl;
				lastSyncedNotifySale = notifySale;

				// Now send heartbeat (sequential, no concurrent auth refresh)
				if (backendUrl != null && !backendUrl.isEmpty())
				{
					apiClient.webhookHeartbeatAsync();
				}
			},
			() -> {
				log.debug("No webhook configured on backend");
			},
			error -> {
				log.debug("Failed to pull webhook config: {}", error);
			}
		);
	}

	/**
	 * Check if webhook config has changed and sync if needed
	 */
	public void syncIfChanged()
	{
		String webhookUrl = config.discordWebhookUrl();
		boolean notifySale = config.notifySaleCompleted();
		boolean notifySuggestion = false;

		// Check if any config has changed from what we last synced
		boolean hasChanged = !equals(webhookUrl, lastSyncedWebhookUrl)
			|| notifySale != lastSyncedNotifySale
			|| notifySuggestion != lastSyncedNotifySuggestion;

		// Skip if the URL matches what we just pulled from backend (no real change)
		if (hasChanged && equals(webhookUrl, lastPulledBackendUrl)
			&& notifySale == lastSyncedNotifySale
			&& notifySuggestion == lastSyncedNotifySuggestion)
		{
			lastSyncedWebhookUrl = webhookUrl;
			return;
		}

		if (!hasChanged)
		{
			return; // Nothing changed
		}

		// If webhook URL is empty, delete webhook from backend
		if (webhookUrl == null || webhookUrl.trim().isEmpty())
		{
			log.info("Webhook URL cleared in plugin config (lastSynced={})", lastSyncedWebhookUrl);
			if (lastSyncedWebhookUrl != null && !lastSyncedWebhookUrl.trim().isEmpty())
			{
				// Was previously configured, now being removed
				deleteWebhook();
			}
			return;
		}

		// Sync to backend
		syncToBackend(webhookUrl, notifySale, notifySuggestion);
	}

	/**
	 * Sync webhook config to backend
	 */
	private void syncToBackend(String webhookUrl, boolean notifySale, boolean notifySuggestion)
	{
		log.debug("Syncing webhook config to backend");

		apiClient.updateWebhookAsync(
			webhookUrl,
			notifySale,
			notifySuggestion,
			() -> {
				log.info("Webhook config synced to backend successfully");
				// Update cached values
				lastSyncedWebhookUrl = webhookUrl;
				lastSyncedNotifySale = notifySale;
				lastSyncedNotifySuggestion = notifySuggestion;
			},
			error -> {
				log.warn("Failed to sync webhook config to backend: {}", error);
			}
		);
	}

	/**
	 * Delete webhook from backend
	 */
	private void deleteWebhook()
	{
		log.debug("Deleting webhook from backend");

		apiClient.deleteWebhookAsync(
			() -> {
				log.info("Webhook deleted from backend successfully");
				// Clear cached values
				lastSyncedWebhookUrl = null;
				lastSyncedNotifySale = false;
				lastSyncedNotifySuggestion = false;
			},
			error -> {
				log.warn("Failed to delete webhook from backend: {}", error);
			}
		);
	}

	/**
	 * Helper to compare strings (handles nulls)
	 */
	private boolean equals(String a, String b)
	{
		if (a == null && b == null)
		{
			return true;
		}
		if (a == null || b == null)
		{
			return false;
		}
		return a.equals(b);
	}
}
