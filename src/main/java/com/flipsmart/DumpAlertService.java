package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Service for polling market dumps and posting chat alerts
 */
@Slf4j
@Singleton
public class DumpAlertService
{
	private final Client client;
	private final FlipSmartConfig config;
	private final FlipSmartApiClient apiClient;
	private final ChatMessageManager chatMessageManager;

	// Track when we last alerted for each item (itemId -> timestamp)
	private final Map<Integer, Long> itemAlertCooldowns = new HashMap<>();

	private Timer pollingTimer;

	@Inject
	public DumpAlertService(
		Client client,
		FlipSmartConfig config,
		FlipSmartApiClient apiClient,
		ChatMessageManager chatMessageManager
	)
	{
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.chatMessageManager = chatMessageManager;
	}

	/**
	 * Start the dump alert service
	 */
	public void start()
	{
		if (!config.enableDumpAlerts())
		{
			log.debug("Dump alerts disabled in config, not starting");
			return;
		}

		// Stop any existing task
		stop();

		int intervalSeconds = Math.max(30, Math.min(300, config.dumpAlertInterval()));
		long intervalMs = intervalSeconds * 1000L;

		log.info("Starting dump alert service with {}s interval", intervalSeconds);

		pollingTimer = new Timer("DumpAlertTimer", true);

		pollingTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				try
				{
					checkForDumps();
				}
				catch (Exception e)
				{
					log.error("Error checking for dumps", e);
				}
			}
		}, 5000, intervalMs); // 5 second initial delay
	}

	/**
	 * Stop the dump alert service
	 */
	public void stop()
	{
		if (pollingTimer != null)
		{
			log.info("Stopping dump alert service");
			pollingTimer.cancel();
			pollingTimer = null;
		}
	}

	/**
	 * Restart the service (called when config changes)
	 */
	public void restart()
	{
		stop();
		start();
	}

	/**
	 * Check for new dumps and post alerts
	 */
	private void checkForDumps()
	{
		if (!config.enableDumpAlerts() || client.getGameState() == null)
		{
			return;
		}

		int minProfit = config.dumpAlertMinProfit();
		int maxCount = Math.max(1, Math.min(50, config.dumpAlertMaxCount()));
		int cooldownMinutes = Math.max(0, Math.min(1440, config.dumpAlertCooldownMinutes()));
		long cooldownMs = cooldownMinutes * 60 * 1000L;
		long currentTime = System.currentTimeMillis();

		log.debug("Checking for dumps (minProfit: {}, maxCount: {}, cooldown: {}m)",
			minProfit, maxCount, cooldownMinutes);

		// Cleanup expired cooldowns first
		itemAlertCooldowns.entrySet().removeIf(entry ->
			currentTime - entry.getValue() > cooldownMs);

		// Sort by profit or recency based on config
		String sortBy = config.dumpAlertSortByProfit() ? "profit" : "recency";

		apiClient.getDumpsAsync(
			sortBy,
			minProfit,
			50, // Fetch up to 50 recent dumps
			dumps ->
			{
				if (dumps == null || dumps.length == 0)
				{
					log.debug("No dumps returned from API");
					return;
				}

				log.debug("Received {} dumps from API", dumps.length);

				FlipSmartConfig.PriceAlertType alertType = config.priceAlertType();

				int alertCount = 0;
				for (DumpEvent dump : dumps)
				{
					// Stop once we've alerted on the max count
					if (alertCount >= maxCount)
					{
						break;
					}

					// Filter by price change type based on config
					String changeType = dump.getPriceChangeType();
					boolean isDump = "dump".equalsIgnoreCase(changeType);
					boolean isPump = "pump".equalsIgnoreCase(changeType);

					// Skip if it doesn't match the configured alert type
					if (alertType == FlipSmartConfig.PriceAlertType.DUMPS_ONLY && !isDump)
					{
						log.debug("Skipping {} - is a pump but alerts set to dumps only", dump.getItemName());
						continue;
					}
					if (alertType == FlipSmartConfig.PriceAlertType.PUMPS_ONLY && !isPump)
					{
						log.debug("Skipping {} - is a dump but alerts set to pumps only", dump.getItemName());
						continue;
					}

					int itemId = dump.getItemId();

					// Check if item is on cooldown
					if (itemAlertCooldowns.containsKey(itemId))
					{
						long lastAlertTime = itemAlertCooldowns.get(itemId);
						long timeSinceAlert = currentTime - lastAlertTime;

						if (timeSinceAlert < cooldownMs)
						{
							long minutesRemaining = (cooldownMs - timeSinceAlert) / 60000;
							log.debug("Skipping {} - on cooldown for {} more minutes",
								dump.getItemName(), minutesRemaining);
							continue;
						}
					}

					// Mark item as alerted with current timestamp
					itemAlertCooldowns.put(itemId, currentTime);

					// Post to game chat
					postDumpAlert(dump);
					alertCount++;
				}

				log.debug("Posted {} new dump alerts", alertCount);

				// Cleanup if map gets too large (shouldn't happen often with cooldown cleanup)
				if (itemAlertCooldowns.size() > 500)
				{
					log.debug("Cleaning up item cooldowns (size: {})", itemAlertCooldowns.size());
					// Remove oldest entries
					long cutoffTime = currentTime - cooldownMs;
					itemAlertCooldowns.entrySet().removeIf(entry -> entry.getValue() < cutoffTime);
				}
			},
			error -> log.warn("Failed to fetch dumps: {}", error)
		);
	}

	/**
	 * Post a dump alert to game chat
	 */
	private void postDumpAlert(DumpEvent dump)
	{
		String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Flip Smart] ")
			.append(ChatColorType.NORMAL)
			.append(dump.toChatMessage())
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());

		log.info("Posted dump alert: {} ({}% drop, {} profit)",
			dump.getItemName(),
			String.format("%.1f", dump.getPriceDropPercent()),
			dump.getEstimatedProfit() != null ? dump.getEstimatedProfit() + "gp" : "unknown");
	}

	/**
	 * Clear the item cooldown tracking (useful for testing or after long periods)
	 */
	public void clearItemCooldowns()
	{
		itemAlertCooldowns.clear();
		log.debug("Cleared item cooldowns");
	}
}
