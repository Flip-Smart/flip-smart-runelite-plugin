package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.BlocklistsResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.concurrent.CompletableFuture;

import static com.flipsmart.api.ApiHttpTransport.JSON;

/**
 * Blocklist read and item-add endpoints.
 */
@Slf4j
public class BlocklistEndpoints
{
	private static final String JSON_KEY_ITEM_ID = "item_id";

	private final ApiHttpTransport transport;
	private final Gson gson;

	public BlocklistEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
		this.gson = transport.getGson();
	}

	/**
	 * Fetch user's blocklists from the API asynchronously.
	 * Blocklists are used to hide specific items from flip recommendations.
	 */
	public CompletableFuture<BlocklistsResponse> getBlocklistsAsync()
	{
		String apiUrl = transport.getApiUrl();
		String url = String.format("%s/blocklists", apiUrl);

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, BlocklistsResponse.class));
	}

	/**
	 * Add an item to a blocklist asynchronously.
	 * Blocked items will be excluded from flip recommendations.
	 */
	public CompletableFuture<Boolean> addItemToBlocklistAsync(int blocklistId, int itemId, String reason)
	{
		String apiUrl = transport.getApiUrl();
		String url = String.format("%s/blocklists/%d/items", apiUrl, blocklistId);

		JsonObject jsonBody = new JsonObject();
		jsonBody.addProperty(JSON_KEY_ITEM_ID, itemId);
		if (reason != null && !reason.isEmpty())
		{
			jsonBody.addProperty("reason", reason);
		}

		RequestBody body = RequestBody.create(JSON, jsonBody.toString());

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			// If we got here, the request succeeded
			log.debug("Added item {} to blocklist {}", itemId, blocklistId);
			return true;
		}).exceptionally(e ->
		{
			log.debug("Failed to add item to blocklist: {}", e.getMessage());
			return false;
		});
	}

	/**
	 * Add an item to a blocklist asynchronously (without reason).
	 */
	public CompletableFuture<Boolean> addItemToBlocklistAsync(int blocklistId, int itemId)
	{
		return addItemToBlocklistAsync(blocklistId, itemId, null);
	}
}
