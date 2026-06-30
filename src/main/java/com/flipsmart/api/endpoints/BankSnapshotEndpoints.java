package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.BankItem;
import com.flipsmart.api.dto.BankItemId;
import com.flipsmart.api.dto.BankSnapshotResponse;
import com.flipsmart.api.dto.BankSnapshotStatusResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.flipsmart.api.ApiHttpTransport.JSON;
import static com.flipsmart.api.ApiHttpTransport.urlEncode;

/**
 * Bank snapshot status and creation endpoints.
 */
public class BankSnapshotEndpoints
{
	private static final String JSON_KEY_ITEM_ID = "item_id";
	private static final String JSON_KEY_QUANTITY = "quantity";

	private final ApiHttpTransport transport;

	public BankSnapshotEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
	}

	/**
	 * Check if a bank snapshot can be taken (rate limit check)
	 */
	public CompletableFuture<BankSnapshotStatusResponse> checkBankSnapshotStatusAsync(String rsn)
	{
		String apiUrl = transport.getApiUrl();
		String url = String.format("%s/bank/snapshot/status?rsn=%s", apiUrl, urlEncode(rsn));

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			transport.parse(jsonData, BankSnapshotStatusResponse.class));
	}

	/**
	 * Create a bank snapshot with bank items, equipped gear, inventory, and GE offers.
	 *
	 * Bank items carry plugin-supplied prices; backend re-prices them when zero
	 * or when they're a known charged variant. Inventory and gear items are
	 * priced server-side, so only item_id + quantity are sent.
	 */
	public CompletableFuture<BankSnapshotResponse> createBankSnapshotAsync(
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

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			transport.parse(jsonData, BankSnapshotResponse.class));
	}
}
