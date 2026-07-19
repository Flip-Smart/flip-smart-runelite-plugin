package com.flipsmart.api.endpoints;

import com.flipsmart.FlipSmartApiClient;
import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.DumpEvent;
import com.flipsmart.api.dto.DumpsResponse;
import com.flipsmart.api.dto.WikiPrice;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Request;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Market-data endpoints: wiki real-time prices, per-item daily volume and dumps.
 * Owns the wiki-price and daily-volume caches plus their in-flight dedup.
 */
@Slf4j
public class MarketDataEndpoints
{
	private static final long WIKI_PRICE_CACHE_DURATION_MS = 60_000; // 1 minute cache

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
	 * Fetch all wiki prices from the API and update the cache.
	 * This is rate-limited to once per minute.
	 */
	public void fetchWikiPrices()
	{
		long now = System.currentTimeMillis();
		if (now - lastWikiPriceFetch.get() < WIKI_PRICE_CACHE_DURATION_MS)
		{
			return;
		}

		if (!wikiPriceFetchInProgress.compareAndSet(false, true))
		{
			return;
		}

		String url = String.format("%s/plugin/prices", transport.getApiUrl());
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		CompletableFuture<Void> future = transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			parseWikiPriceResponse(jsonData);
			lastWikiPriceFetch.set(System.currentTimeMillis());
			return null;
		});

		future.whenComplete((result, ex) -> wikiPriceFetchInProgress.set(false));
	}

	/**
	 * Parse wiki price API response and update cache
	 */
	private void parseWikiPriceResponse(String json)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
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
		return System.currentTimeMillis() - lastWikiPriceFetch.get() > WIKI_PRICE_CACHE_DURATION_MS;
	}

	/**
	 * Fetch market dumps from the API asynchronously
	 */
	public void getDumpsAsync(String sortBy, int minProfit, int limit,
	                          Consumer<DumpEvent[]> onSuccess,
	                          Consumer<String> onError)
	{
		transport.ensureAuthenticatedAsync().thenAccept(authSuccess ->
		{
			if (!authSuccess)
			{
				if (onError != null)
				{
					onError.accept("Authentication required");
				}
				return;
			}

			// Build query parameters
			HttpUrl.Builder urlBuilder = HttpUrl.parse(transport.getApiUrl() + "/dumps").newBuilder();
			if (sortBy != null && !sortBy.isEmpty())
			{
				urlBuilder.addQueryParameter("sort_by", sortBy);
			}
			if (minProfit > 0)
			{
				urlBuilder.addQueryParameter("min_profit", String.valueOf(minProfit));
			}
			if (limit > 0)
			{
				urlBuilder.addQueryParameter("limit", String.valueOf(limit));
			}

			Request request = transport.withAuthGet(urlBuilder.build());

			transport.executeAsync(request,
				body ->
				{
					DumpsResponse response = gson.fromJson(body, DumpsResponse.class);
					if (onSuccess != null)
					{
						onSuccess.accept(response != null ? response.dumps : new DumpEvent[0]);
					}
					return null;
				},
				onError,
				true // Retry on 401
			);
		});
	}

	/**
	 * Fetch market dumps with default parameters (recency sort, no min profit, limit 50)
	 */
	public void getDumpsAsync(Consumer<DumpEvent[]> onSuccess, Consumer<String> onError)
	{
		getDumpsAsync("recency", 0, 50, onSuccess, onError);
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
