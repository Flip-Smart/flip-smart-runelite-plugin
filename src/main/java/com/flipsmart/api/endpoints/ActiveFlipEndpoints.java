package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.ActiveFlipsResponse;
import com.flipsmart.api.dto.CompletedFlipsResponse;
import com.flipsmart.api.dto.FlipStatisticsResponse;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.flipsmart.api.ApiHttpTransport.JSON;
import static com.flipsmart.api.ApiHttpTransport.urlEncode;

/**
 * Active-flip lifecycle, completed-flip and flip-statistics endpoints.
 */
@Slf4j
public class ActiveFlipEndpoints
{
	private static final String JSON_KEY_ITEM_ID = "item_id";
	private static final String JSON_KEY_PRICE_PER_ITEM = "price_per_item";

	private final ApiHttpTransport transport;
	private final Gson gson;

	public ActiveFlipEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
		this.gson = transport.getGson();
	}

	/**
	 * Fetch active flips from the API asynchronously
	 * @param rsn Optional RSN to filter by (for multi-account support)
	 */
	public CompletableFuture<ActiveFlipsResponse> getActiveFlipsAsync(String rsn)
	{
		String apiUrl = transport.getApiUrl();
		String url;
		if (rsn != null && !rsn.isEmpty())
		{
			url = String.format("%s/transactions/active-flips?rsn=%s", apiUrl, urlEncode(rsn));
		}
		else
		{
			url = String.format("%s/transactions/active-flips", apiUrl);
		}

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, ActiveFlipsResponse.class));
	}

	/**
	 * Fetch active flips from the API asynchronously (all RSNs)
	 */
	public CompletableFuture<ActiveFlipsResponse> getActiveFlipsAsync()
	{
		return getActiveFlipsAsync(null);
	}

	/**
	 * Dismiss an active flip asynchronously
	 */
	public CompletableFuture<Boolean> dismissActiveFlipAsync(int itemId)
	{
		return dismissActiveFlipAsync(itemId, null);
	}

	/**
	 * Dismiss an active flip asynchronously with RSN support
	 */
	public CompletableFuture<Boolean> dismissActiveFlipAsync(int itemId, String rsn)
	{
		String apiUrl = transport.getApiUrl();
		String url;
		if (rsn != null && !rsn.isEmpty())
		{
			url = String.format("%s/transactions/active-flips/%d?rsn=%s", apiUrl, itemId, urlEncode(rsn));
		}
		else
		{
			url = String.format("%s/transactions/active-flips/%d", apiUrl, itemId);
		}

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.delete();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			log.debug("Successfully dismissed active flip for item {}", itemId);
			return true;
		}).exceptionally(e ->
		{
			log.warn("Failed to dismiss active flip: {}", e.getMessage());
			return false;
		});
	}

	/**
	 * Clean up stale active flips that are no longer being tracked.
	 */
	public CompletableFuture<Boolean> cleanupStaleFlipsAsync(Set<Integer> activeItemIds, String rsn)
	{
		String apiUrl = transport.getApiUrl();
		String url;
		if (rsn != null && !rsn.isEmpty())
		{
			url = String.format("%s/transactions/active-flips/cleanup?rsn=%s", apiUrl, urlEncode(rsn));
		}
		else
		{
			url = String.format("%s/transactions/active-flips/cleanup", apiUrl);
		}

		// Build the request body
		JsonObject requestBody = new JsonObject();
		JsonArray itemIdsArray = new JsonArray();
		for (Integer itemId : activeItemIds)
		{
			itemIdsArray.add(itemId);
		}
		requestBody.add("active_item_ids", itemIdsArray);

		RequestBody body = RequestBody.create(JSON, requestBody.toString());
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
			int itemsCleaned = responseObj.has("items_cleaned") ? responseObj.get("items_cleaned").getAsInt() : 0;
			if (itemsCleaned > 0)
			{
				log.debug("Cleaned up {} stale active flips", itemsCleaned);
			}
			else
			{
				log.debug("No stale flips to clean up");
			}
			return true;
		}).exceptionally(e ->
		{
			log.warn("Failed to cleanup stale flips: {}", e.getMessage());
			return false;
		});
	}

	/**
	 * Sync the filled quantity for an active flip when the plugin detects a mismatch.
	 */
	public CompletableFuture<Boolean> syncActiveFlipAsync(int itemId, String itemName, int filledQuantity,
			int orderQuantity, int pricePerItem, String rsn)
	{
		String apiUrl = transport.getApiUrl();
		String url = String.format("%s/transactions/active-flips/sync", apiUrl);

		JsonObject requestBody = new JsonObject();
		requestBody.addProperty(JSON_KEY_ITEM_ID, itemId);
		requestBody.addProperty("filled_quantity", filledQuantity);
		requestBody.addProperty("order_quantity", orderQuantity);
		requestBody.addProperty(JSON_KEY_PRICE_PER_ITEM, pricePerItem);
		requestBody.addProperty("rsn", rsn);

		RequestBody body = RequestBody.create(JSON, requestBody.toString());
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
			int previousQty = responseObj.has("previous_quantity") ? responseObj.get("previous_quantity").getAsInt() : 0;
			int newQty = responseObj.has("new_quantity") ? responseObj.get("new_quantity").getAsInt() : 0;
			if (previousQty != newQty)
			{
				log.debug("Synced active flip for {} ({}): {} -> {} items",
					itemName, itemId, previousQty, newQty);
			}
			return true;
		}).exceptionally(e ->
		{
			log.warn("Failed to sync active flip for {}: {}", itemId, e.getMessage());
			return false;
		});
	}

	/**
	 * Mark an active flip as in the 'sell' phase.
	 * Called when a sell order is placed for an item.
	 */
	public CompletableFuture<Boolean> markActiveFlipSellingAsync(int itemId, String rsn)
	{
		String apiUrl = transport.getApiUrl();
		String url = String.format("%s/transactions/active-flips/%d/mark-selling?rsn=%s", apiUrl, itemId, urlEncode(rsn));

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, ""));

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			log.debug("Marked active flip for item {} as selling", itemId);
			return true;
		}).exceptionally(e ->
		{
			log.debug("Failed to mark active flip as selling: {}", e.getMessage());
			return false;
		});
	}

	/**
	 * Fetch completed flips from the API asynchronously
	 */
	public CompletableFuture<CompletedFlipsResponse> getCompletedFlipsAsync(int limit, String rsn)
	{
		String apiUrl = transport.getApiUrl();
		String url;
		if (rsn != null && !rsn.isEmpty())
		{
			url = String.format("%s/flips/completed?limit=%d&rsn=%s", apiUrl, limit, urlEncode(rsn));
		}
		else
		{
			url = String.format("%s/flips/completed?limit=%d", apiUrl, limit);
		}

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, CompletedFlipsResponse.class));
	}

	/**
	 * Fetch completed flips from the API asynchronously (all RSNs)
	 */
	public CompletableFuture<CompletedFlipsResponse> getCompletedFlipsAsync(int limit)
	{
		return getCompletedFlipsAsync(limit, null);
	}

	/**
	 * Fetch aggregate flip statistics from the API.
	 */
	public CompletableFuture<FlipStatisticsResponse> getFlipStatisticsAsync(int days, String rsn)
	{
		String apiUrl = transport.getApiUrl();
		String url;
		if (rsn != null && !rsn.isEmpty())
		{
			url = String.format("%s/flips/statistics?days=%d&rsn=%s", apiUrl, days, urlEncode(rsn));
		}
		else
		{
			url = String.format("%s/flips/statistics?days=%d", apiUrl, days);
		}

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, FlipStatisticsResponse.class));
	}
}
