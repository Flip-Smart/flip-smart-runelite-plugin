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
	 * Check if webhook config has changed and sync if needed
	 */
	public void syncIfChanged()
	{
		String webhookUrl = config.discordWebhookUrl();
		boolean notifySale = config.notifySaleCompleted();
		boolean notifySuggestion = config.notifyFlipSuggestion();

		// Check if any config has changed
		boolean hasChanged = !equals(webhookUrl, lastSyncedWebhookUrl)
			|| notifySale != lastSyncedNotifySale
			|| notifySuggestion != lastSyncedNotifySuggestion;

		if (!hasChanged)
		{
			return; // Nothing changed
		}

		// If webhook URL is empty, delete webhook from backend
		if (webhookUrl == null || webhookUrl.trim().isEmpty())
		{
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
