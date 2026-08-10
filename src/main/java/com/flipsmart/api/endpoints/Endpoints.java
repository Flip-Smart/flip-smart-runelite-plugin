package com.flipsmart.api.endpoints;

import com.flipsmart.FlipSmartApiClient;
import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.Dtos.ActiveFlipsResponse;
import com.flipsmart.api.dto.Dtos.BankItem;
import com.flipsmart.api.dto.Dtos.BankItemId;
import com.flipsmart.api.dto.Dtos.BankSnapshotResponse;
import com.flipsmart.api.dto.Dtos.BankSnapshotResult;
import com.flipsmart.api.dto.Dtos.BlocklistsResponse;
import com.flipsmart.api.dto.Dtos.CompletedFlipsResponse;
import com.flipsmart.api.dto.Dtos.FavoritesResponse;
import com.flipsmart.api.dto.Dtos.FlipAdjustmentRequest;
import com.flipsmart.api.dto.Dtos.FlipAdjustmentResponse;
import com.flipsmart.api.dto.Dtos.FlipFinderResponse;
import com.flipsmart.api.dto.Dtos.FlipStatisticsResponse;
import com.flipsmart.api.dto.Dtos.HistoryBackfillEntry;
import com.flipsmart.api.dto.Dtos.OfferAdviceBatchResponse;
import com.flipsmart.api.dto.Dtos.OfferAdviceRequest;
import com.flipsmart.api.dto.Dtos.PluginSyncResponse;
import com.flipsmart.api.dto.Dtos.SellPriceCheckRequest;
import com.flipsmart.api.dto.Dtos.SellPriceCheckResponse;
import com.flipsmart.api.dto.Dtos.TransactionRequest;
import com.flipsmart.api.dto.Dtos.WikiPrice;
import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.flip.FlipAnalysis;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import static com.flipsmart.api.ApiHttpTransport.JSON;
import static com.flipsmart.api.ApiHttpTransport.urlEncode;

/**
 * The API endpoint surface, grouped into one container.
 *
 * <p>Each group was a separate file paying for its own package declaration and
 * import block, which the plugin-hub token budget counts. Nesting them removes
 * that overhead. Groups stay {@code public static} so call sites can import them
 * directly and construction is unchanged.
 */
public final class Endpoints
{
	private static final String JSON_KEY_ITEM_ID = "item_id";
	private static final String JSON_KEY_RSN = "rsn";

	private Endpoints()
	{
	}

	/**
	 * Active-flip lifecycle, completed-flip and flip-statistics endpoints.
	 */
	@Slf4j
	public static class ActiveFlipEndpoints
	{
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
			return getScoped("/transactions/active-flips", "", rsn, ActiveFlipsResponse.class);
		}

		/**
		 * GET {@code path}, optionally scoped to a single RSN, decoding the body into
		 * {@code type}. {@code query} is the leading query string without the rsn pair
		 * ("limit=50", or empty when the path takes no other parameters).
		 */
		private <T> CompletableFuture<T> getScoped(String path, String query, String rsn, Class<T> type)
		{
			StringBuilder url = new StringBuilder(transport.getApiUrl()).append(path);
			String separator = "?";
			if (!query.isEmpty())
			{
				url.append(separator).append(query);
				separator = "&";
			}
			if (rsn != null && !rsn.isEmpty())
			{
				url.append(separator).append("rsn=").append(urlEncode(rsn));
			}

			Request.Builder requestBuilder = new Request.Builder()
				.url(url.toString())
				.get();

			return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
				gson.fromJson(jsonData, type));
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
			requestBody.addProperty(JSON_KEY_RSN, rsn);

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
			return getScoped("/flips/completed", "limit=" + limit, rsn, CompletedFlipsResponse.class);
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
			return getScoped("/flips/statistics", "days=" + days, rsn, FlipStatisticsResponse.class);
		}
	}

	@Slf4j
	public static class ActiveFlipsSnapshotEndpoints
	{
		private final ApiHttpTransport transport;
		private final Gson gson;

		public ActiveFlipsSnapshotEndpoints(ApiHttpTransport transport)
		{
			this.transport = transport;
			this.gson = transport.getGson();
		}

		public CompletableFuture<Boolean> pushActiveFlipsSnapshotAsync(String rsn, List<ActiveFlip> flips)
		{
			String url = String.format("%s/plugin/active-flips-snapshot", transport.getApiUrl());

			JsonObject body = new JsonObject();
			body.addProperty(JSON_KEY_RSN, rsn);
			body.addProperty("captured_at", Instant.now().toString());
			// ActiveFlip carries @SerializedName on every field, so it serialises
			// straight into the snake_case shape the backend expects.
			body.add("flips", gson.toJsonTree(flips));

			RequestBody rb = RequestBody.create(JSON, body.toString());
			Request.Builder requestBuilder = new Request.Builder().url(url).post(rb);

			return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
				.exceptionally(e ->
				{
					if (log.isDebugEnabled())
					{
						log.debug("pushActiveFlipsSnapshotAsync failed: {}", e.getMessage());
					}
					return false;
				});
		}
	}

	/**
	 * Bank snapshot creation endpoint.
	 */
	public static class BankSnapshotEndpoints
	{
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
			requestBody.addProperty(JSON_KEY_RSN, rsn);
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

			requestBody.add("inventory_items", toItemIdArray(inventoryItems));
			requestBody.add("gear_items", toItemIdArray(gearItems));

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
					return rateLimited.get() ? BankSnapshotResult.rateLimitedResult() : BankSnapshotResult.failure();
				});
		}

		private static JsonArray toItemIdArray(List<BankItemId> ids)
		{
			JsonArray array = new JsonArray();
			for (BankItemId id : ids)
			{
				JsonObject obj = new JsonObject();
				obj.addProperty(JSON_KEY_ITEM_ID, id.itemId);
				obj.addProperty(JSON_KEY_QUANTITY, id.quantity);
				array.add(obj);
			}
			return array;
		}
	}

	/**
	 * Blocklist read and item-add endpoints.
	 */
	@Slf4j
	public static class BlocklistEndpoints
	{

		private final ApiHttpTransport transport;

		public BlocklistEndpoints(ApiHttpTransport transport)
		{
			this.transport = transport;
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
				transport.parse(jsonData, BlocklistsResponse.class));
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

	/**
	 * Favorites endpoints: list the enriched favorites, and star/un-star an item.
	 * Toggles are idempotent server-side, so callers may re-issue them safely.
	 */
	public static class FavoritesEndpoints
	{
		private final ApiHttpTransport transport;

		public FavoritesEndpoints(ApiHttpTransport transport)
		{
			this.transport = transport;
		}

		static String favoritesListPath(String apiUrl)
		{
			return apiUrl + "/plugin/favorites";
		}

		static String favoritePath(String apiUrl, int itemId)
		{
			return apiUrl + "/plugin/favorites/" + itemId;
		}

		public CompletableFuture<FavoritesResponse> getFavoritesAsync()
		{
			Request.Builder requestBuilder = new Request.Builder()
				.url(favoritesListPath(transport.getApiUrl()))
				.get();
			return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
				transport.parse(jsonData, FavoritesResponse.class));
		}

		public CompletableFuture<Boolean> addFavoriteAsync(int itemId)
		{
			Request.Builder requestBuilder = new Request.Builder()
				.url(favoritePath(transport.getApiUrl(), itemId))
				.post(RequestBody.create(JSON, "{}"));
			return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
				.exceptionally(e -> false);
		}

		public CompletableFuture<Boolean> removeFavoriteAsync(int itemId)
		{
			Request.Builder requestBuilder = new Request.Builder()
				.url(favoritePath(transport.getApiUrl(), itemId))
				.delete();
			return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
				.exceptionally(e -> false);
		}
	}

	/**
	 * Item analysis, flip recommendations and flip-adjustment endpoints.
	 * Analysis responses are cached for CACHE_DURATION_MS. The Active Flips cards read their
	 * live prices from here, so they invalidate per item before refetching rather than wait
	 * out this TTL.
	 */
	public static class FlipsEndpoints
	{
		static final long CACHE_DURATION_MS = 900_000;

		private final ApiHttpTransport transport;
		private final LongSupplier clock;

		// Cache to avoid spamming the API
		private final Map<Integer, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();
		private final Map<Integer, CompletableFuture<FlipAnalysis>> inFlightAnalysis = new ConcurrentHashMap<>();

		public FlipsEndpoints(ApiHttpTransport transport)
		{
			this(transport, System::currentTimeMillis);
		}

		FlipsEndpoints(ApiHttpTransport transport, LongSupplier clock)
		{
			this.transport = transport;
			this.clock = clock;
		}

		/**
		 * Fetch item analysis from the API asynchronously. Served from the per-item
		 * cache while fresh; concurrent requests for the same item share one call.
		 */
		public CompletableFuture<FlipAnalysis> getItemAnalysisAsync(int itemId)
		{
			CachedAnalysis cached = analysisCache.get(itemId);
			if (cached != null && !cached.isExpired(clock.getAsLong()))
			{
				return CompletableFuture.completedFuture(cached.getAnalysis());
			}

			CompletableFuture<FlipAnalysis> future = inFlightAnalysis.computeIfAbsent(itemId, this::fetchItemAnalysis);
			future.whenComplete((analysis, error) -> inFlightAnalysis.remove(itemId, future));
			return future;
		}

		private CompletableFuture<FlipAnalysis> fetchItemAnalysis(int itemId)
		{
			String apiUrl = transport.getApiUrl();
			String url = String.format("%s/analysis/%d?timeframe=1h", apiUrl, itemId);

			Request.Builder requestBuilder = new Request.Builder()
				.url(url)
				.get();

			return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			{
				FlipAnalysis analysis = transport.parse(jsonData, FlipAnalysis.class);
				if (analysis != null)
				{
					removeExpiredCacheEntries();
					analysisCache.put(itemId, new CachedAnalysis(analysis, clock.getAsLong()));
				}
				return analysis;
			});
		}

		/**
		 * Fetch flip recommendations from the API asynchronously.
		 */
		public CompletableFuture<FlipFinderResponse> getFlipRecommendationsAsync(
			Integer cashStack, String flipStyle, int limit, Integer randomSeed, String timeframe, String rsn,
			Integer filledSlots, boolean isMembersWorld, int minProfit, int minVolume, boolean favoritesOnly)
		{
			String apiUrl = transport.getApiUrl();

			// Build URL with query parameters
			StringBuilder urlBuilder = new StringBuilder(128);
			urlBuilder.append(String.format("%s/flip-finder?limit=%d&flip_style=%s", apiUrl, limit, flipStyle));
			appendSharedQueryParams(urlBuilder, cashStack, randomSeed, timeframe, rsn, filledSlots, isMembersWorld);
			appendFilterParams(urlBuilder, minProfit, minVolume);
			appendFavoritesOnly(urlBuilder, favoritesOnly);

			String url = urlBuilder.toString();
			Request.Builder requestBuilder = new Request.Builder()
				.url(url)
				.get();

			return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
				transport.parse(jsonData, FlipFinderResponse.class));
		}

		/**
		 * Query parameters shared by {@link #getFlipRecommendationsAsync} and
		 * {@link #getPluginSyncAsync} — the two callers differ only in path and
		 * response type, so keep the param-building in one place to avoid drift.
		 */
		private void appendSharedQueryParams(StringBuilder urlBuilder, Integer cashStack, Integer randomSeed,
			String timeframe, String rsn, Integer filledSlots, boolean isMembersWorld)
		{
			if (cashStack != null)
			{
				urlBuilder.append(String.format("&cash_stack=%d", cashStack));
			}

			if (randomSeed != null)
			{
				urlBuilder.append(String.format("&random_seed=%d", randomSeed));
			}

			if (timeframe != null)
			{
				urlBuilder.append(String.format("&timeframe=%s", timeframe));
			}

			if (rsn != null && !rsn.isEmpty())
			{
				urlBuilder.append(String.format("&rsn=%s", urlEncode(rsn)));
			}

			if (filledSlots != null)
			{
				urlBuilder.append(String.format("&filled_slots=%d", filledSlots));
			}

			if (!isMembersWorld)
			{
				urlBuilder.append("&is_members_world=false");
			}
		}

		/**
		 * Append the user's Min Profit / Min Volume filters so the backend selects the pool
		 * from the full item universe under these thresholds. Omitted when unset (≤ 0) so
		 * default-config requests keep the same URL — and cache key — as before.
		 */
		static void appendFilterParams(StringBuilder urlBuilder, int minProfit, int minVolume)
		{
			if (minProfit > 0)
			{
				urlBuilder.append(String.format("&min_profit=%d", minProfit));
			}
			if (minVolume > 0)
			{
				urlBuilder.append(String.format("&min_volume=%d", minVolume));
			}
		}

		static void appendFavoritesOnly(StringBuilder urlBuilder, boolean favoritesOnly)
		{
			if (favoritesOnly)
			{
				urlBuilder.append("&favorites_only=true");
			}
		}

		/**
		 * Append the player's real inventory coins for the web "Capital Active" card.
		 * Unlike the filter params above, zero is sent rather than omitted: a player with
		 * everything deployed into offers genuinely holds none, and dropping that would
		 * leave the stored figure overstated indefinitely. Null alone means "unknown".
		 * Kept off {@code appendSharedQueryParams} because /flip-finder has no use for it.
		 */
		static void appendInventoryGp(StringBuilder urlBuilder, Integer inventoryGp)
		{
			if (inventoryGp != null)
			{
				urlBuilder.append(String.format("&inventory_gp=%d", inventoryGp));
			}
		}

		/**
		 * Fetch the bundled 2-minute poll ({@code GET /plugin/sync}) in one round-trip:
		 * recommendations, active flips, completed flips, statistics and entitlements.
		 * Query parameters mirror {@link #getFlipRecommendationsAsync} so the same
		 * panel inputs drive both.
		 */
		public CompletableFuture<PluginSyncResponse> getPluginSyncAsync(
			Integer cashStack, Integer inventoryGp, String flipStyle, int limit, Integer randomSeed, String timeframe,
			String rsn, Integer filledSlots, boolean isMembersWorld, int minProfit, int minVolume, boolean favoritesOnly)
		{
			String apiUrl = transport.getApiUrl();

			StringBuilder urlBuilder = new StringBuilder(128);
			urlBuilder.append(String.format("%s/plugin/sync?limit=%d&flip_style=%s", apiUrl, limit, flipStyle));
			appendSharedQueryParams(urlBuilder, cashStack, randomSeed, timeframe, rsn, filledSlots, isMembersWorld);
			appendFilterParams(urlBuilder, minProfit, minVolume);
			appendInventoryGp(urlBuilder, inventoryGp);
			appendFavoritesOnly(urlBuilder, favoritesOnly);

			Request.Builder requestBuilder = new Request.Builder()
				.url(urlBuilder.toString())
				.get();

			return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
				transport.parse(jsonData, PluginSyncResponse.class));
		}

		/**
		 * Flush the last-known inventory coins as the player logs out, so the web
		 * "Capital Active" card does not sit on a reading up to a poll-interval old
		 * for the whole time they are offline. Recurring reporting rides
		 * {@link #getPluginSyncAsync} instead, so this fires once per session.
		 * Best-effort: failures are swallowed rather than delaying logout.
		 */
		public CompletableFuture<Boolean> pushRsnCapitalAsync(String rsn, Integer inventoryGp)
		{
			JsonObject body = new JsonObject();
			body.addProperty(JSON_KEY_RSN, rsn);
			body.addProperty("inventory_gp", inventoryGp);

			Request.Builder requestBuilder = new Request.Builder()
				.url(String.format("%s/rsn/capital", transport.getApiUrl()))
				.post(RequestBody.create(JSON, body.toString()));

			return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
				.exceptionally(e -> false);
		}

		/**
		 * Get a flip adjustment recommendation from the backend.
		 * Checks whether a stale offer should be adjusted based on volume, timeframe, and market conditions.
		 */
		public CompletableFuture<FlipAdjustmentResponse> getFlipAdjustmentAsync(FlipAdjustmentRequest req)
		{
			String apiUrl = transport.getApiUrl();
			String url = String.format("%s/flips/adjustment", apiUrl);

			JsonObject jsonBody = new JsonObject();
			jsonBody.addProperty(JSON_KEY_ITEM_ID, req.itemId);
			jsonBody.addProperty("is_buy_offer", req.isBuyOffer);
			jsonBody.addProperty("offer_price", req.offerPrice);
			jsonBody.addProperty("average_buy_price", req.averageBuyPrice);
			jsonBody.addProperty("minutes_since_offer", req.minutesSinceOffer);
			jsonBody.addProperty("adjustment_count", req.adjustmentCount);
			jsonBody.addProperty("quantity_filled", req.quantityFilled);
			jsonBody.addProperty("total_quantity", req.totalQuantity);
			if (req.timeframe != null)
			{
				jsonBody.addProperty("timeframe", req.timeframe);
			}
			if (req.rsn != null)
			{
				jsonBody.addProperty(JSON_KEY_RSN, req.rsn);
			}
			if (req.style != null)
			{
				jsonBody.addProperty("style", req.style);
			}

			RequestBody body = RequestBody.create(JSON, jsonBody.toString());

			Request.Builder requestBuilder = new Request.Builder()
				.url(url)
				.post(body);

			return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
				transport.parse(jsonData, FlipAdjustmentResponse.class));
		}

		/**
		 * Clear the analysis cache
		 */
		public void clearCache()
		{
			analysisCache.clear();
		}

		/**
		 * Remove a specific item from the cache
		 */
		public void invalidateCache(int itemId)
		{
			analysisCache.remove(itemId);
		}

		/**
		 * Removes expired entries from the cache
		 */
		private void removeExpiredCacheEntries()
		{
			long now = clock.getAsLong();
			analysisCache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
		}

		/**
		 * Inner class to store cached analysis with timestamp
		 */
		private static class CachedAnalysis
		{
			private final FlipAnalysis analysis;
			private final long timestamp;

			CachedAnalysis(FlipAnalysis analysis, long now)
			{
				this.analysis = analysis;
				this.timestamp = now;
			}

			FlipAnalysis getAnalysis()
			{
				return analysis;
			}

			boolean isExpired(long now)
			{
				return now - timestamp > CACHE_DURATION_MS;
			}
		}
	}

	/**
	 * Market-data endpoints: wiki real-time prices and per-item daily volume.
	 * Owns the wiki-price and daily-volume caches plus their in-flight dedup.
	 */
	@Slf4j
	public static class MarketDataEndpoints
	{
		// In-memory cache of per-item 24h daily volume — short TTL so price-volume swings
		// are picked up the next time the user opens a buy window, but long enough to
		// avoid hitting the API on every offer-screen rebuild as the player adjusts qty.
		private static final long DAILY_VOLUME_CACHE_TTL_MS = 300_000;  // 5 minutes
		// Shorter TTL applied when a fetch fails (404/5xx/connection error). The GE
		// offer description is rebuilt every render frame, so without a negative
		// cache a persistent error (e.g. the endpoint missing on a stale API build)
		// fires one request per frame. We back off for a minute, then retry.
		private static final long DAILY_VOLUME_ERROR_CACHE_TTL_MS = 60_000;  // 1 minute

		private final ApiHttpTransport transport;
		private final Gson gson;

		// Cache for wiki prices: itemId -> WikiPrice
		private final Map<Integer, WikiPrice> wikiPriceCache = new ConcurrentHashMap<>();
		private final AtomicLong lastWikiPriceFetch = new AtomicLong(0);
		private final AtomicBoolean wikiPriceFetchInProgress = new AtomicBoolean(false);

		private final Map<Integer, CachedDailyVolume> dailyVolumeCache = new ConcurrentHashMap<>();
		// In-flight fetches keyed by item id. onBeforeRender rebuilds the GE offer
		// description every render frame, so without deduping a cold cache opens one
		// connection per frame for the same item until the first response lands.
		private final Map<Integer, CompletableFuture<Integer>> inFlightDailyVolume = new ConcurrentHashMap<>();

		public MarketDataEndpoints(ApiHttpTransport transport)
		{
			this.transport = transport;
			this.gson = transport.getGson();
		}

		/**
		 * Get cached wiki price for an item. Returns null if not cached or expired.
		 * Call fetchWikiPrices() to populate the cache.
		 */
		public WikiPrice getWikiPrice(int itemId)
		{
			WikiPrice price = wikiPriceCache.get(itemId);
			if (price != null && !price.isExpired())
			{
				return price;
			}
			return null;
		}

		/**
		 * Last known price for this item even if it has aged out, or null if we have
		 * never held one. Callers that must compare a buy against insta-sell and a sell
		 * against insta-buy want this: a minute-old pair still answers that question,
		 * whereas a single blended price cannot answer it at all.
		 */
		public WikiPrice getLastKnownWikiPrice(int itemId)
		{
			return wikiPriceCache.get(itemId);
		}

		/**
		 * Fetch all wiki prices from the API and update the cache.
		 * This is rate-limited to once per minute.
		 */
		public void fetchWikiPrices()
		{
			long now = System.currentTimeMillis();
			if (now - lastWikiPriceFetch.get() < WikiPrice.WIKI_PRICE_REFRESH_INTERVAL_MS)
			{
				return;
			}

			if (!wikiPriceFetchInProgress.compareAndSet(false, true))
			{
				return;
			}

			lastWikiPriceFetch.set(now);

			String url = String.format("%s/plugin/prices", transport.getApiUrl());
			Request.Builder requestBuilder = new Request.Builder()
				.url(url)
				.get();

			CompletableFuture<Void> future = transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			{
				parseWikiPriceResponse(jsonData);
				return null;
			}, error -> log.warn("Failed to fetch wiki prices: {}", error));

			future.whenComplete((result, ex) -> wikiPriceFetchInProgress.set(false));
		}

		/**
		 * Parse wiki price API response and update cache
		 */
		private void parseWikiPriceResponse(String json)
		{
			JsonObject root = gson.fromJson(json, JsonObject.class);
			if (root == null)
			{
				return;
			}
			JsonObject data = root.getAsJsonObject("data");

			if (data == null)
			{
				return;
			}

			// Clear expired entries before adding new ones to prevent unbounded growth
			removeExpiredWikiPriceEntries();

			for (String key : data.keySet())
			{
				parseAndCacheItemPrice(key, data.getAsJsonObject(key));
			}
			log.debug("Updated wiki price cache with {} items", wikiPriceCache.size());
		}

		/**
		 * Removes expired entries from the wiki price cache to prevent memory leaks
		 */
		private void removeExpiredWikiPriceEntries()
		{
			wikiPriceCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
		}

		/**
		 * Parse and cache a single item's price data
		 */
		private void parseAndCacheItemPrice(String itemKey, JsonObject priceData)
		{
			try
			{
				int itemId = Integer.parseInt(itemKey);
				int high = getJsonIntOrZero(priceData, "high");
				int low = getJsonIntOrZero(priceData, "low");

				if (high > 0 || low > 0)
				{
					wikiPriceCache.put(itemId, new WikiPrice(high, low));
				}
			}
			catch (NumberFormatException ignored)
			{
				// Skip non-numeric keys
			}
		}

		/**
		 * Safely get an int value from JSON, returning 0 if null or missing
		 */
		private int getJsonIntOrZero(JsonObject obj, String key)
		{
			if (obj.has(key) && !obj.get(key).isJsonNull())
			{
				return obj.get(key).getAsInt();
			}
			return 0;
		}

		/**
		 * Check if wiki prices need to be refreshed
		 */
		public boolean needsWikiPriceRefresh()
		{
			return System.currentTimeMillis() - lastWikiPriceFetch.get() > WikiPrice.WIKI_PRICE_REFRESH_INTERVAL_MS;
		}



		/**
		 * Fetch the 24h daily trading volume for a single item.
		 */
		public CompletableFuture<Integer> getDailyVolumeAsync(int itemId)
		{
			CachedDailyVolume cached = dailyVolumeCache.get(itemId);
			if (cached != null && !cached.isExpired())
			{
				return CompletableFuture.completedFuture(cached.getVolume());
			}

			return FlipSmartApiClient.dedupeInFlight(inFlightDailyVolume, itemId, () -> fetchDailyVolume(itemId));
		}

		private CompletableFuture<Integer> fetchDailyVolume(int itemId)
		{
			String url = String.format("%s/items/%d/daily-volume", transport.getApiUrl(), itemId);
			Request.Builder requestBuilder = new Request.Builder()
				.url(url)
				.get();

			CompletableFuture<Integer> future = transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			{
				JsonObject obj = gson.fromJson(jsonData, JsonObject.class);
				Integer volume = (obj != null && obj.has("daily_volume") && !obj.get("daily_volume").isJsonNull())
					? obj.get("daily_volume").getAsInt()
					: null;
				dailyVolumeCache.put(itemId, new CachedDailyVolume(volume, DAILY_VOLUME_CACHE_TTL_MS));
				return volume;
			});

			// On any non-2xx response executeAsync completes the future with null
			// WITHOUT running the handler above, so nothing gets cached. Record a
			// short-lived negative entry in that case to throttle the render loop.
			future.whenComplete((volume, ex) ->
			{
				if (!dailyVolumeCache.containsKey(itemId))
				{
					dailyVolumeCache.put(itemId, new CachedDailyVolume(null, DAILY_VOLUME_ERROR_CACHE_TTL_MS));
				}
			});

			return future;
		}

		/**
		 * Non-blocking peek into the daily-volume cache. Returns a completed future
		 * with the cached value when fresh, or {@code null} when not cached / expired.
		 * Safe to call from the RuneLite client thread (no network I/O, no locks).
		 */
		public CompletableFuture<Integer> peekCachedDailyVolume(int itemId)
		{
			CachedDailyVolume cached = dailyVolumeCache.get(itemId);
			if (cached == null || cached.isExpired())
			{
				return null;
			}
			return CompletableFuture.completedFuture(cached.getVolume());
		}

		/**
		 * Synchronous, non-blocking read of the daily-volume cache. Returns the
		 * cached value when fresh, or {@code null} when not cached / expired —
		 * never triggers a network fetch. Mirrors {@link #getWikiPrice(int)} so
		 * timer threads can build snapshots without blocking on I/O.
		 */
		public Integer getCachedDailyVolume(int itemId)
		{
			CachedDailyVolume cached = dailyVolumeCache.get(itemId);
			if (cached == null || cached.isExpired())
			{
				return null;
			}
			return cached.getVolume();
		}

		private static class CachedDailyVolume
		{
			private final Integer volume;
			private final long fetchedAt;
			private final long ttlMs;

			CachedDailyVolume(Integer volume, long ttlMs)
			{
				this.volume = volume;
				this.fetchedAt = System.currentTimeMillis();
				this.ttlMs = ttlMs;
			}

			Integer getVolume() { return volume; }
			boolean isExpired() { return System.currentTimeMillis() - fetchedAt > ttlMs; }
		}
	}

	/**
	 * Batch offer-action and sell-price-check advice endpoints.
	 *
	 * The JSON body builders live as static helpers on {@link FlipSmartApiClient}
	 * to preserve their existing package-visible test surface; this group delegates
	 * to them so the wire format stays identical.
	 */
	public static class OfferActionEndpoints
	{
		private final ApiHttpTransport transport;

		public OfferActionEndpoints(ApiHttpTransport transport)
		{
			this.transport = transport;
		}

		public CompletableFuture<OfferAdviceBatchResponse> postOfferActionsBatchAsync(List<OfferAdviceRequest> reqs)
		{
			String url = String.format("%s/flip-finder/active/offer-actions", transport.getApiUrl());
			RequestBody body = RequestBody.create(JSON, FlipSmartApiClient.buildOfferActionsBody(reqs).toString());
			Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
			return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
				transport.parse(jsonData, OfferAdviceBatchResponse.class));
		}

		public CompletableFuture<SellPriceCheckResponse> postSellPriceCheckAsync(SellPriceCheckRequest req)
		{
			String url = String.format("%s/flip-finder/active/sell-price-check", transport.getApiUrl());
			RequestBody body = RequestBody.create(JSON, FlipSmartApiClient.buildSellPriceCheckBody(req).toString());
			Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
			return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
				transport.parse(jsonData, SellPriceCheckResponse.class));
		}
	}

	/**
	 * Trade Station slot-push endpoint (warms the web "Import from RuneLite" cache).
	 */
	@Slf4j
	public static class TradeStationEndpoints
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
			body.addProperty(JSON_KEY_RSN, rsn);
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

	/**
	 * Transaction recording and history backfill endpoints.
	 */
	@Slf4j
	public static class TransactionEndpoints
	{
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
				jsonBody.addProperty(JSON_KEY_RSN, request.rsn);
			}
			if (request.totalQuantity != null && request.totalQuantity > 0)
			{
				jsonBody.addProperty("total_quantity", request.totalQuantity);
			}
			if (request.idempotencyKey != null)
			{
				jsonBody.addProperty("idempotency_key", request.idempotencyKey);
			}
			if (request.offerId != null)
			{
				jsonBody.addProperty("offer_id", request.offerId);
			}
			if (request.roundTripId != null)
			{
				jsonBody.addProperty("round_trip_id", request.roundTripId);
			}
			if (request.slotGeneration != null)
			{
				jsonBody.addProperty("slot_generation", request.slotGeneration);
			}

			RequestBody body = RequestBody.create(JSON, jsonBody.toString());

			Request.Builder requestBuilder = new Request.Builder()
				.url(url)
				.post(body);

			return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			{
				JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
				if (log.isDebugEnabled())
				{
					log.debug("Transaction recorded for {}: {}", request.rsn, responseObj.get("message").getAsString());
				}
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
			body.addProperty(JSON_KEY_RSN, rsn);
			JsonArray arr = new JsonArray();
			for (HistoryBackfillEntry e : entries)
			{
				JsonObject o = new JsonObject();
				o.addProperty(JSON_KEY_ITEM_ID, e.itemId);
				if (e.itemName != null) o.addProperty("item_name", e.itemName);
				o.addProperty("is_buy", e.isBuy);
				o.addProperty(JSON_KEY_QUANTITY, e.quantity);
				o.addProperty(JSON_KEY_PRICE_PER_ITEM, e.pricePerItem);
				if (e.offerId != null) o.addProperty("offer_id", e.offerId);
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

	/**
	 * Discord webhook configuration endpoints (update / fetch / delete).
	 */
	@Slf4j
	public static class WebhookEndpoints
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

}
