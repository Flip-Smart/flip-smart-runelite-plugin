package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.FlipAdjustmentRequest;
import com.flipsmart.api.dto.FlipAdjustmentResponse;
import com.flipsmart.api.dto.FlipFinderResponse;
import com.flipsmart.api.dto.PluginSyncResponse;
import com.flipsmart.api.dto.TimeframeFlipFinderResponse;
import com.flipsmart.domain.flip.FlipAnalysis;
import com.google.gson.JsonObject;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import static com.flipsmart.api.ApiHttpTransport.JSON;
import static com.flipsmart.api.ApiHttpTransport.urlEncode;

/**
 * Item analysis, flip recommendations and flip-adjustment endpoints.
 * Owns the per-item analysis cache. Analysis feeds slow-moving card display
 * fields (buy limit, liquidity, risk), so a long TTL is acceptable; live
 * prices on cards come from the active-flips payload, not from here.
 */
public class FlipsEndpoints
{
	private static final String JSON_KEY_ITEM_ID = "item_id";
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
		Integer filledSlots, boolean isMembersWorld, int minProfit, int minVolume)
	{
		String apiUrl = transport.getApiUrl();

		// Build URL with query parameters
		StringBuilder urlBuilder = new StringBuilder(128);
		urlBuilder.append(String.format("%s/flip-finder?limit=%d&flip_style=%s", apiUrl, limit, flipStyle));
		appendSharedQueryParams(urlBuilder, cashStack, randomSeed, timeframe, rsn, filledSlots, isMembersWorld);
		appendFilterParams(urlBuilder, minProfit, minVolume);

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

	/**
	 * Fetch the bundled 2-minute poll ({@code GET /plugin/sync}) in one round-trip:
	 * recommendations, active flips, completed flips, statistics and entitlements.
	 * Query parameters mirror {@link #getFlipRecommendationsAsync} so the same
	 * panel inputs drive both.
	 */
	public CompletableFuture<PluginSyncResponse> getPluginSyncAsync(
		Integer cashStack, String flipStyle, int limit, Integer randomSeed, String timeframe, String rsn,
		Integer filledSlots, boolean isMembersWorld, int minProfit, int minVolume)
	{
		String apiUrl = transport.getApiUrl();

		StringBuilder urlBuilder = new StringBuilder(128);
		urlBuilder.append(String.format("%s/plugin/sync?limit=%d&flip_style=%s", apiUrl, limit, flipStyle));
		appendSharedQueryParams(urlBuilder, cashStack, randomSeed, timeframe, rsn, filledSlots, isMembersWorld);
		appendFilterParams(urlBuilder, minProfit, minVolume);

		Request.Builder requestBuilder = new Request.Builder()
			.url(urlBuilder.toString())
			.get();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			transport.parse(jsonData, PluginSyncResponse.class));
	}

	/**
	 * Fetch timeframe-based flip recommendations from the API asynchronously.
	 */
	@Deprecated(since = "1.5.0", forRemoval = true)
	public CompletableFuture<TimeframeFlipFinderResponse> getTimeframeFlipRecommendationsAsync(
		String timeframe, Integer cashStack, int limit, Integer priceOffset)
	{
		String apiUrl = transport.getApiUrl();

		// Build URL with query parameters
		StringBuilder urlBuilder = new StringBuilder();
		urlBuilder.append(String.format("%s/flip-finder/timeframe?timeframe=%s&limit=%d", apiUrl, timeframe, limit));

		if (cashStack != null)
		{
			urlBuilder.append(String.format("&cash_stack=%d", cashStack));
		}

		if (priceOffset != null && priceOffset > 0)
		{
			urlBuilder.append(String.format("&price_offset=%d", priceOffset));
		}

		String url = urlBuilder.toString();
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			transport.parse(jsonData, TimeframeFlipFinderResponse.class));
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
			jsonBody.addProperty("rsn", req.rsn);
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
