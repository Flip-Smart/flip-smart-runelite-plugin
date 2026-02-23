package com.flipsmart;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for sending Discord webhook notifications for trade events.
 * Rate limited to max 1 webhook per 5 seconds with a queue.
 */
@Slf4j
@Singleton
public class WebhookNotificationService
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final long RATE_LIMIT_MS = 5000; // 5 seconds between webhooks

	// Color constants for Discord embeds
	private static final int COLOR_SALE_COMPLETED = 0x2ECC71; // Green
	private static final int COLOR_DUMP = 0xE74C3C; // Red
	private static final int COLOR_SUGGESTION = 0x3498DB; // Blue

	private final OkHttpClient httpClient;
	private final FlipSmartConfig config;
	private final Gson gson;

	private final Queue<WebhookPayload> webhookQueue = new LinkedList<>();
	private long lastWebhookSentAt = 0;
	private ScheduledExecutorService executor;

	@Inject
	public WebhookNotificationService(
		OkHttpClient httpClient,
		FlipSmartConfig config,
		Gson gson
	)
	{
		this.httpClient = httpClient;
		this.config = config;
		this.gson = gson;
		startQueueProcessor();
	}

	/**
	 * Start the queue processor that sends webhooks with rate limiting
	 */
	private void startQueueProcessor()
	{
		if (executor == null || executor.isShutdown())
		{
			executor = Executors.newSingleThreadScheduledExecutor();
			executor.scheduleAtFixedRate(this::processQueue, 1, 1, TimeUnit.SECONDS);
			log.debug("Started webhook queue processor");
		}
	}

	/**
	 * Stop the queue processor
	 */
	public void shutdown()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
			log.debug("Stopped webhook queue processor");
		}
	}

	/**
	 * Process queued webhooks respecting rate limits
	 */
	private void processQueue()
	{
		try
		{
			long currentTime = System.currentTimeMillis();

			// Check if enough time has passed since last webhook
			if (currentTime - lastWebhookSentAt < RATE_LIMIT_MS)
			{
				return; // Still in cooldown
			}

			// Get next webhook from queue
			WebhookPayload payload = webhookQueue.poll();
			if (payload == null)
			{
				return; // Queue is empty
			}

			// Send the webhook
			sendWebhookNow(payload);
			lastWebhookSentAt = currentTime;
		}
		catch (Exception e)
		{
			log.error("Error processing webhook queue", e);
		}
	}

	/**
	 * Send a sale completion notification
	 */
	public void sendSaleNotification(String itemName, int itemId, int quantity, int buyPrice, int sellPrice, int profit)
	{
		if (!config.notifySaleCompleted())
		{
			return; // Notifications disabled for this event type
		}

		String webhookUrl = config.discordWebhookUrl();
		if (webhookUrl == null || webhookUrl.trim().isEmpty())
		{
			return; // No webhook configured
		}

		// Calculate ROI
		double roi = (buyPrice > 0) ? (profit / (double) (buyPrice * quantity) * 100.0) : 0.0;

		// Build embed
		JsonObject embed = new JsonObject();
		embed.addProperty("title", "Flip Completed");
		embed.addProperty("color", profit > 0 ? COLOR_SALE_COMPLETED : COLOR_DUMP);
		embed.addProperty("timestamp", Instant.now().toString());

		// Add thumbnail
		JsonObject thumbnail = new JsonObject();
		thumbnail.addProperty("url", "https://api.flipsm.art/items/" + itemId + "/icon");
		embed.add("thumbnail", thumbnail);

		// Add fields
		JsonArray fields = new JsonArray();
		fields.add(createField("Item", itemName, true));
		fields.add(createField("Quantity", String.format("%,d", quantity), true));
		fields.add(createField("Buy Price", String.format("%,d gp each", buyPrice), true));
		fields.add(createField("Sell Price", String.format("%,d gp each", sellPrice), true));
		fields.add(createField("Profit", String.format("%,d gp", profit), true));
		fields.add(createField("ROI", String.format("%.2f%%", roi), true));
		embed.add("fields", fields);

		// Add footer
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "FlipSmart");
		embed.add("footer", footer);

		// Queue webhook
		queueWebhook(webhookUrl, embed);

		log.debug("Queued sale notification for {} (profit: {} gp)", itemName, profit);
	}

	/**
	 * Create a Discord embed field
	 */
	private JsonObject createField(String name, String value, boolean inline)
	{
		JsonObject field = new JsonObject();
		field.addProperty("name", name);
		field.addProperty("value", value);
		field.addProperty("inline", inline);
		return field;
	}

	/**
	 * Queue a webhook for sending (rate limited)
	 */
	private void queueWebhook(String webhookUrl, JsonObject embed)
	{
		WebhookPayload payload = new WebhookPayload(webhookUrl, embed);
		webhookQueue.offer(payload);
		log.debug("Webhook queued (queue size: {})", webhookQueue.size());
	}

	/**
	 * Send a webhook immediately (called by queue processor)
	 */
	private void sendWebhookNow(WebhookPayload payload)
	{
		// Build webhook body
		JsonObject body = new JsonObject();
		JsonArray embeds = new JsonArray();
		embeds.add(payload.embed);
		body.add("embeds", embeds);

		String json = gson.toJson(body);

		Request request = new Request.Builder()
			.url(payload.webhookUrl)
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to send Discord webhook: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (ResponseBody responseBody = response.body())
				{
					if (response.isSuccessful())
					{
						log.debug("Discord webhook sent successfully");
					}
					else
					{
						log.warn("Discord webhook failed with HTTP {}", response.code());
					}
				}
			}
		});
	}

	/**
	 * Internal class for webhook queue payload
	 */
	private static class WebhookPayload
	{
		final String webhookUrl;
		final JsonObject embed;

		WebhookPayload(String webhookUrl, JsonObject embed)
		{
			this.webhookUrl = webhookUrl;
			this.embed = embed;
		}
	}
}
