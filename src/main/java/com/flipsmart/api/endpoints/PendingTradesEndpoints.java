package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.concurrent.CompletableFuture;

import static com.flipsmart.api.ApiHttpTransport.JSON;

/**
 * Reports a single GE slot's current offer state, powering the admin-only
 * Pending Trades per Item panel on the Item Graphs page (issue #92).
 */
@Slf4j
public class PendingTradesEndpoints
{
	private final ApiHttpTransport transport;

	public PendingTradesEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
	}

	/**
	 * Report a single GE slot's current offer state to the backend. Best-effort
	 * — failures are swallowed by the caller.
	 *
	 * @param state one of "pending", "executed", "cancelled", "empty"
	 */
	public CompletableFuture<Boolean> reportPendingTradeOfferAsync(
		String rsn, int slot, int itemId, boolean isBuy, int price, int quantity, int quantityFilled, String state)
	{
		String url = String.format("%s/pending-trades/report", transport.getApiUrl());

		JsonObject body = new JsonObject();
		body.addProperty("rsn", rsn);
		body.addProperty("slot", slot);
		body.addProperty("item_id", itemId);
		body.addProperty("is_buy", isBuy);
		body.addProperty("price", price);
		body.addProperty("quantity", quantity);
		body.addProperty("quantity_filled", quantityFilled);
		body.addProperty("state", state);

		RequestBody rb = RequestBody.create(JSON, body.toString());
		Request.Builder requestBuilder = new Request.Builder().url(url).post(rb);

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
			.exceptionally(e ->
			{
				log.debug("reportPendingTradeOfferAsync failed: {}", e.getMessage());
				return false;
			});
	}
}
