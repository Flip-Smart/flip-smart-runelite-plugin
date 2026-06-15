package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.function.Consumer;

import static com.flipsmart.api.ApiHttpTransport.JSON;

/**
 * Discord webhook configuration endpoints (update / fetch / delete).
 */
@Slf4j
public class WebhookEndpoints
{
	private static final String WEBHOOK_BASE_PATH = "/profile/webhook";

	private final ApiHttpTransport transport;
	private final Gson gson;

	public WebhookEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
		this.gson = transport.getGson();
	}

	/**
	 * Execute a simple webhook API call with standard success/error callback wiring.
	 */
	private void executeWebhookCall(Request request, String action, Runnable onSuccess, Consumer<String> onError)
	{
		transport.executeAsync(
			request,
			jsonData -> {
				log.debug("Webhook {} succeeded", action);
				if (onSuccess != null)
				{
					onSuccess.run();
				}
				return null;
			},
			error -> {
				log.debug("Webhook {} failed: {}", action, error);
				if (onError != null)
				{
					onError.accept(error);
				}
			},
			true
		);
	}

	/**
	 * Update (or create) user's webhook configuration asynchronously.
	 */
	public void updateWebhookAsync(
		String webhookUrl,
		boolean notifySale,
		boolean notifySuggestion,
		Runnable onSuccess,
		Consumer<String> onError
	)
	{
		JsonObject jsonBody = new JsonObject();
		jsonBody.addProperty("webhook_url", webhookUrl);
		jsonBody.addProperty("notify_sale_completed", notifySale);
		jsonBody.addProperty("notify_flip_suggestion", notifySuggestion);
		jsonBody.addProperty("enabled", true);

		Request request = new Request.Builder()
			.url(String.format("%s%s", transport.getApiUrl(), WEBHOOK_BASE_PATH))
			.put(RequestBody.create(JSON, jsonBody.toString()))
			.build();

		executeWebhookCall(request, "update", onSuccess, onError);
	}

	/**
	 * Fetch user's full webhook configuration (including unmasked URL) from the backend.
	 */
	public void fetchWebhookConfigAsync(
		Consumer<JsonObject> onSuccess,
		Runnable onNotFound,
		Consumer<String> onError
	)
	{
		Request request = new Request.Builder()
			.url(String.format("%s%s/url", transport.getApiUrl(), WEBHOOK_BASE_PATH))
			.get()
			.build();

		transport.executeAsync(
			request,
			jsonData -> {
				log.debug("Webhook fetch succeeded");
				JsonObject webhookConfig = gson.fromJson(jsonData, JsonObject.class);
				if (onSuccess != null)
				{
					onSuccess.accept(webhookConfig);
				}
				return null;
			},
			error -> {
				if (error != null && error.contains("404"))
				{
					log.debug("No webhook configured on backend");
					if (onNotFound != null)
					{
						onNotFound.run();
					}
				}
				else
				{
					log.debug("Webhook fetch failed: {}", error);
					if (onError != null)
					{
						onError.accept(error);
					}
				}
			},
			true
		);
	}

	/**
	 * Delete user's webhook configuration asynchronously.
	 */
	public void deleteWebhookAsync(Runnable onSuccess, Consumer<String> onError)
	{
		Request request = new Request.Builder()
			.url(String.format("%s%s", transport.getApiUrl(), WEBHOOK_BASE_PATH))
			.delete()
			.build();

		executeWebhookCall(request, "delete", onSuccess, onError);
	}
}
