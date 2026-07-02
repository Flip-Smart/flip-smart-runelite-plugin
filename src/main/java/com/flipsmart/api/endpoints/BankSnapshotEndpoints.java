package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.BankItem;
import com.flipsmart.api.dto.BankItemId;
import com.flipsmart.api.dto.BankSnapshotResponse;
import com.flipsmart.api.dto.BankSnapshotResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.flipsmart.api.ApiHttpTransport.JSON;

/**
 * Bank snapshot creation endpoint.
 */
public class BankSnapshotEndpoints
{
	private static final String JSON_KEY_ITEM_ID = "item_id";
	private static final String JSON_KEY_QUANTITY = "quantity";
	private static final String RATE_LIMIT_ERROR = "Error 429";

	private final ApiHttpTransport transport;

	public BankSnapshotEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
	}

	/**
	 * Create a bank snapshot with bank items, equipped gear, inventory, and GE offers.
	 * The server enforces its own rate limit, answering 429 when a snapshot was
	 * already taken inside the window; that outcome is reported via
	 * {@link BankSnapshotResult#isRateLimited()}.
	 *
	 * Bank items carry plugin-supplied prices; backend re-prices them when zero
	 * or when they're a known charged variant. Inventory and gear items are
	 * priced server-side, so only item_id + quantity are sent.
	 */
	public CompletableFuture<BankSnapshotResult> createBankSnapshotAsync(
		String rsn,
		List<BankItem> items,
		List<BankItemId> inventoryItems,
		List<BankItemId> gearItems,
		long geOffersValue)
	{
		String apiUrl = transport.getApiUrl();
		String url = String.format("%s/bank/snapshot", apiUrl);

		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("rsn", rsn);
		requestBody.addProperty("ge_offers_value", geOffersValue);

		JsonArray itemsArray = new JsonArray();
		for (BankItem item : items)
		{
			JsonObject itemObj = new JsonObject();
			itemObj.addProperty(JSON_KEY_ITEM_ID, item.itemId);
			itemObj.addProperty(JSON_KEY_QUANTITY, item.quantity);
			itemObj.addProperty("value_per_item", item.valuePerItem);
			itemsArray.add(itemObj);
		}
		requestBody.add("items", itemsArray);

		JsonArray invArray = new JsonArray();
		for (BankItemId inv : inventoryItems)
		{
			JsonObject obj = new JsonObject();
			obj.addProperty(JSON_KEY_ITEM_ID, inv.itemId);
			obj.addProperty(JSON_KEY_QUANTITY, inv.quantity);
			invArray.add(obj);
		}
		requestBody.add("inventory_items", invArray);

		JsonArray gearArray = new JsonArray();
		for (BankItemId gear : gearItems)
		{
			JsonObject obj = new JsonObject();
			obj.addProperty(JSON_KEY_ITEM_ID, gear.itemId);
			obj.addProperty(JSON_KEY_QUANTITY, gear.quantity);
			gearArray.add(obj);
		}
		requestBody.add("gear_items", gearArray);

		RequestBody body = RequestBody.create(JSON, requestBody.toString());
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);

		// The error handler runs before the future completes with null, so the
		// flag is visible to the thenApply below.
		AtomicBoolean rateLimited = new AtomicBoolean(false);
		return transport.executeAuthenticatedAsync(requestBuilder,
			jsonData -> transport.parse(jsonData, BankSnapshotResponse.class),
			error -> rateLimited.set(RATE_LIMIT_ERROR.equals(error)))
			.thenApply(response ->
			{
				if (response != null)
				{
					return BankSnapshotResult.success(response);
				}
				return rateLimited.get() ? BankSnapshotResult.rateLimited() : BankSnapshotResult.failure();
			});
	}
}
