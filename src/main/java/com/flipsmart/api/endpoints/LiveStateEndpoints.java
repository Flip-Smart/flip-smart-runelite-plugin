package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.LiveStateSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.concurrent.CompletableFuture;

import static com.flipsmart.api.ApiHttpTransport.JSON;

/**
 * Live player-state push endpoint (lets the backend apply the same
 * "still on the GE" predicate the plugin uses client-side).
 */
@Slf4j
public class LiveStateEndpoints
{
	private final ApiHttpTransport transport;

	public LiveStateEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
	}

	public CompletableFuture<Boolean> pushLiveStateAsync(String rsn, String capturedAtIso, LiveStateSnapshot snapshot)
	{
		String url = String.format("%s/plugin/live-state", transport.getApiUrl());

		JsonObject body = new JsonObject();
		body.addProperty("rsn", rsn);
		body.addProperty("captured_at", capturedAtIso);

		JsonArray slots = new JsonArray();
		for (LiveStateSnapshot.SlotState s : snapshot.getSlots())
		{
			JsonObject o = new JsonObject();
			o.addProperty("slot", s.slot);
			o.addProperty("item_id", s.itemId);
			o.addProperty("item_name", s.itemName);
			o.addProperty("is_buy", s.isBuy);
			o.addProperty("state", s.state);
			o.addProperty("total_quantity", s.totalQuantity);
			o.addProperty("filled_quantity", s.filledQuantity);
			o.addProperty("price", s.price);
			slots.add(o);
		}
		body.add("slots", slots);
		body.add("inventory_item_ids", toArray(snapshot.getInventoryItemIds()));
		body.add("collected_item_ids", toArray(snapshot.getCollectedItemIds()));

		RequestBody rb = RequestBody.create(JSON, body.toString());
		Request.Builder requestBuilder = new Request.Builder().url(url).post(rb);

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
			.exceptionally(e ->
			{
				if (log.isDebugEnabled())
				{
					log.debug("pushLiveStateAsync failed: {}", e.getMessage());
				}
				return false;
			});
	}

	private static JsonArray toArray(Iterable<Integer> ids)
	{
		JsonArray arr = new JsonArray();
		for (Integer id : ids)
		{
			arr.add(id);
		}
		return arr;
	}
}
