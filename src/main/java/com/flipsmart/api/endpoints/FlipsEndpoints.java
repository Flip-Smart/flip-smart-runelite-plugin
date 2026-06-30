package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.FlipAdjustmentRequest;
import com.flipsmart.api.dto.FlipAdjustmentResponse;
import com.flipsmart.api.dto.FlipFinderResponse;
import com.flipsmart.api.dto.TimeframeFlipFinderResponse;
import com.flipsmart.domain.flip.FlipAnalysis;
import com.google.gson.JsonObject;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.flipsmart.api.ApiHttpTransport.JSON;
import static com.flipsmart.api.ApiHttpTransport.urlEncode;

/**
 * Item analysis, flip recommendations and flip-adjustment endpoints.
 * Owns the per-item analysis cache.
 */
public class FlipsEndpoints
{
	private static final String JSON_KEY_ITEM_ID = "item_id";
	private static final long CACHE_DURATION_MS = 180_000; // 3 minute cache

	private final ApiHttpTransport transport;

	// Cache to avoid spamming the API
	private final Map<Integer, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();

	public FlipsEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
	}

	/**
	 * Fetch item analysis from the API asynchronously
	 */
	public CompletableFuture<FlipAnalysis> getItemAnalysisAsync(int itemId)
	{
		// Check cache first
		CachedAnalysis cached = analysisCache.get(itemId);
		if (cached != null && !cached.isExpired())
		{
			return CompletableFuture.completedFuture(cached.getAnalysis());
		}

		String apiUrl = transport.getApiUrl();
		String url = String.format("%s/analysis/%d?timeframe=1h", apiUrl, itemId);

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			FlipAnalysis analysis = transport.parse(jsonData, FlipAnalysis.class);
			removeExpiredCacheEntries();
			analysisCache.put(itemId, new CachedAnalysis(analysis));
			return analysis;
		});
	}

	/**
	 * Fetch flip recommendations from the API asynchronously.
	 */
	public CompletableFuture<FlipFinderResponse> getFlipRecommendationsAsync(
		Integer cashStack, String flipStyle, int limit, Integer randomSeed, String timeframe, String rsn,
		Integer filledSlots, boolean isMembersWorld)
	{
		String apiUrl = transport.getApiUrl();

		// Build URL with query parameters
		StringBuilder urlBuilder = new StringBuilder(128);
		urlBuilder.append(String.format("%s/flip-finder?limit=%d&flip_style=%s", apiUrl, limit, flipStyle));

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

		String url = urlBuilder.toString();
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			transport.parse(jsonData, FlipFinderResponse.class));
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
		analysisCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
	}

	/**
	 * Inner class to store cached analysis with timestamp
	 */
	private static class CachedAnalysis
	{
		private final FlipAnalysis analysis;
		private final long timestamp;

		CachedAnalysis(FlipAnalysis analysis)
		{
			this.analysis = analysis;
			this.timestamp = System.currentTimeMillis();
		}

		FlipAnalysis getAnalysis()
		{
			return analysis;
		}

		boolean isExpired()
		{
			return System.currentTimeMillis() - timestamp > CACHE_DURATION_MS;
		}
	}
}
