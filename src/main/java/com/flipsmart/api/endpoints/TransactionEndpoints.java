package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.HistoryBackfillEntry;
import com.flipsmart.api.dto.TransactionRequest;
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
 * Transaction recording and history backfill endpoints.
 */
@Slf4j
public class TransactionEndpoints
{
	private static final String JSON_KEY_ITEM_ID = "item_id";
	private static final String JSON_KEY_QUANTITY = "quantity";
	private static final String JSON_KEY_PRICE_PER_ITEM = "price_per_item";

	private final ApiHttpTransport transport;
	private final Gson gson;

	public TransactionEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
		this.gson = transport.getGson();
	}

	/**
	 * Record a Grand Exchange transaction asynchronously
	 */
	public CompletableFuture<Void> recordTransactionAsync(TransactionRequest request)
	{
		String apiUrl = transport.getApiUrl();
		String url = String.format("%s/transactions", apiUrl);

		// Create JSON body
		JsonObject jsonBody = new JsonObject();
		jsonBody.addProperty(JSON_KEY_ITEM_ID, request.itemId);
		jsonBody.addProperty("item_name", request.itemName);
		jsonBody.addProperty("is_buy", request.isBuy);
		jsonBody.addProperty(JSON_KEY_QUANTITY, request.quantity);
		jsonBody.addProperty(JSON_KEY_PRICE_PER_ITEM, request.pricePerItem);
		if (request.geSlot != null)
		{
			jsonBody.addProperty("ge_slot", request.geSlot);
		}
		if (request.recommendedSellPrice != null)
		{
			jsonBody.addProperty("recommended_sell_price", request.recommendedSellPrice);
		}
		if (request.rsn != null && !request.rsn.isEmpty())
		{
			jsonBody.addProperty("rsn", request.rsn);
		}
		if (request.totalQuantity != null && request.totalQuantity > 0)
		{
			jsonBody.addProperty("total_quantity", request.totalQuantity);
		}
		if (request.idempotencyKey != null)
		{
			jsonBody.addProperty("idempotency_key", request.idempotencyKey);
		}

		RequestBody body = RequestBody.create(JSON, jsonBody.toString());

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
			log.debug("Transaction recorded for {}: {}", request.rsn, responseObj.get("message").getAsString());
			return null;
		}).thenApply(v -> null);
	}

	/**
	 * Record a Grand Exchange transaction asynchronously (simplified overload)
	 * Used for recording offline transactions detected on login.
	 */
	public CompletableFuture<Void> recordTransactionAsync(int itemId, String itemName, String transactionType,
			int quantity, int pricePerItem, String rsn)
	{
		boolean isBuy = "BUY".equalsIgnoreCase(transactionType);
		TransactionRequest request = TransactionRequest
			.builder(itemId, itemName, isBuy, quantity, pricePerItem)
			.rsn(rsn)
			.build();

		return recordTransactionAsync(request);
	}

	/**
	 * Post every visible GE History row in a single batch. The server reconciles
	 * each row against the player's recent transactions via sum-and-delta dedup,
	 * inserting only the missing quantity per row (or skipping if real-time
	 * already captured the trade).
	 */
	public CompletableFuture<Void> recordHistoryBackfillBatchAsync(String rsn, List<HistoryBackfillEntry> entries)
	{
		String apiUrl = transport.getApiUrl();
		String url = String.format("%s/transactions/history-backfill-batch", apiUrl);

		JsonObject body = new JsonObject();
		body.addProperty("rsn", rsn);
		JsonArray arr = new JsonArray();
		for (HistoryBackfillEntry e : entries)
		{
			JsonObject o = new JsonObject();
			o.addProperty(JSON_KEY_ITEM_ID, e.itemId);
			if (e.itemName != null) o.addProperty("item_name", e.itemName);
			o.addProperty("is_buy", e.isBuy);
			o.addProperty(JSON_KEY_QUANTITY, e.quantity);
			o.addProperty(JSON_KEY_PRICE_PER_ITEM, e.pricePerItem);
			arr.add(o);
		}
		body.add("entries", arr);

		RequestBody rb = RequestBody.create(JSON, body.toString());
		Request.Builder requestBuilder = new Request.Builder().url(url).post(rb);

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			JsonObject resp = gson.fromJson(jsonData, JsonObject.class);
			log.info("History backfill batch: inserted={} deduped={}",
				resp.has("inserted") ? resp.get("inserted").getAsInt() : 0,
				resp.has("deduped") ? resp.get("deduped").getAsInt() : 0);
			return null;
		}).thenApply(v -> null);
	}
}
