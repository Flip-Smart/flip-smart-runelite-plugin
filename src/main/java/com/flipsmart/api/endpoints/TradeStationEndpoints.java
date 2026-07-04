package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.flipsmart.api.ApiHttpTransport.JSON;

/**
 * Trade Station slot-push endpoint (warms the web "Import from RuneLite" cache).
 */
@Slf4j
public class TradeStationEndpoints
{
	private final ApiHttpTransport transport;

	public TradeStationEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
	}

	/**
	 * Push the current open GE slot item IDs to the backend cache so the web
	 * Trade Station's "Import from RuneLite" button can read them. Best-effort —
	 * failures are swallowed by the caller because cache warmth is not critical
	 * to plugin operation.
	 */
	public CompletableFuture<Boolean> pushTradeStationSlotsAsync(String rsn, List<Integer> itemIds)
	{
		String url = String.format("%s/trade-station/runelite-slots", transport.getApiUrl());

		JsonObject body = new JsonObject();
		body.addProperty("rsn", rsn);
		JsonArray arr = new JsonArray();
		for (Integer id : itemIds)
		{
			arr.add(id);
		}
		body.add("item_ids", arr);

		RequestBody rb = RequestBody.create(JSON, body.toString());
		Request.Builder requestBuilder = new Request.Builder().url(url).post(rb);

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
			.exceptionally(e ->
			{
				log.debug("pushTradeStationSlotsAsync failed: {}", e.getMessage());
				return false;
			});
	}
}
