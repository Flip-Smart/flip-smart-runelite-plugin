package com.flipsmart;
import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.endpoints.ActiveFlipEndpoints;
import com.flipsmart.api.endpoints.BankSnapshotEndpoints;
import com.flipsmart.api.endpoints.BlocklistEndpoints;
import com.flipsmart.api.endpoints.FlipsEndpoints;
import com.flipsmart.api.endpoints.MarketDataEndpoints;
import com.flipsmart.api.endpoints.MotdEndpoints;
import com.flipsmart.api.endpoints.OfferActionEndpoints;
import com.flipsmart.api.endpoints.TradeStationEndpoints;
import com.flipsmart.api.endpoints.TransactionEndpoints;
import com.flipsmart.api.endpoints.WebhookEndpoints;
import com.flipsmart.api.dto.ActiveFlipsResponse;
import com.flipsmart.api.dto.CompletedFlipsResponse;
import com.flipsmart.api.dto.FlipAdjustmentResponse;
import com.flipsmart.api.dto.OfferAdviceBatchResponse;
import com.flipsmart.api.dto.BankSnapshotResult;
import com.flipsmart.api.dto.TimeframeFlipFinderResponse;
import com.flipsmart.api.dto.FlipFinderResponse;
import com.flipsmart.api.dto.PluginSyncResponse;
import com.flipsmart.api.dto.BlocklistsResponse;
import com.flipsmart.domain.flip.FlipAnalysis;
import com.flipsmart.api.dto.DumpEvent;
import com.flipsmart.api.dto.FlipStatisticsResponse;
import com.flipsmart.api.dto.OfferAdviceRequest;
import com.flipsmart.api.dto.SellPriceCheckRequest;
import com.flipsmart.api.dto.SellPriceCheckResponse;
import com.flipsmart.api.dto.AuthResult;
import com.flipsmart.api.dto.DeviceAuthResponse;
import com.flipsmart.api.dto.DeviceStatusResponse;
import com.flipsmart.api.dto.MotdResponse;
import com.flipsmart.api.dto.TransactionRequest;
import com.flipsmart.api.dto.HistoryBackfillEntry;
import com.flipsmart.api.dto.BankItem;
import com.flipsmart.api.dto.BankItemId;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.api.dto.FlipAdjustmentRequest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Facade over the FlipSmart API. Delegates transport + auth/session to
 * {@link ApiHttpTransport} and each endpoint family to a cohesive group in
 * {@code com.flipsmart.api.endpoints}. Every public method here is a thin
 * delegation; consumers and tests see an unchanged surface.
 */
@Slf4j
@Singleton
public class FlipSmartApiClient
{
	private static final String JSON_KEY_ITEM_ID = "item_id";

	private final FlipSmartConfig config;
	private final ApiHttpTransport transport;
	private final FlipsEndpoints flips;
	private final TransactionEndpoints transactions;
	private final ActiveFlipEndpoints activeFlips;
	private final OfferActionEndpoints offerActions;
	private final MarketDataEndpoints marketData;
	private final BankSnapshotEndpoints bankSnapshots;
	private final BlocklistEndpoints blocklists;
	private final WebhookEndpoints webhooks;
	private final MotdEndpoints motd;
	private final TradeStationEndpoints tradeStation;

	@Inject
	public FlipSmartApiClient(FlipSmartConfig config, Gson gson, OkHttpClient okHttpClient)
	{
		// Use the injected Gson's builder to create a customized instance
		this.config = config;
		Gson customGson = gson.newBuilder().create();
		// Derive from the injected OkHttpClient as required by RuneLite — newBuilder()
		// shares its connection pool and dispatcher — adding a hard per-call timeout
		OkHttpClient timeoutClient = okHttpClient.newBuilder()
			.callTimeout(15, TimeUnit.SECONDS)
			.build();
		this.transport = new ApiHttpTransport(timeoutClient, customGson, config);

		this.flips = new FlipsEndpoints(transport);
		this.transactions = new TransactionEndpoints(transport);
		this.activeFlips = new ActiveFlipEndpoints(transport);
		this.offerActions = new OfferActionEndpoints(transport);
		this.marketData = new MarketDataEndpoints(transport);
		this.bankSnapshots = new BankSnapshotEndpoints(transport);
		this.blocklists = new BlocklistEndpoints(transport);
		this.webhooks = new WebhookEndpoints(transport);
		this.motd = new MotdEndpoints(transport);
		this.tradeStation = new TradeStationEndpoints(transport);
	}

	// ============================================================================
	// Authentication / session — delegate to transport
	// ============================================================================

	public CompletableFuture<AuthResult> loginAsync(String email, String password)
	{
		return transport.loginAsync(email, password);
	}

	public AuthResult login(String email, String password)
	{
		return transport.login(email, password);
	}

	public CompletableFuture<AuthResult> signupAsync(String email, String password)
	{
		return transport.signupAsync(email, password);
	}

	public AuthResult signup(String email, String password)
	{
		return transport.signup(email, password);
	}

	public CompletableFuture<Boolean> refreshAccessTokenAsync()
	{
		return transport.refreshAccessTokenAsync();
	}

	public void setRefreshToken(String token)
	{
		transport.setRefreshToken(token);
	}

	public String getRefreshToken()
	{
		return transport.getRefreshToken();
	}

	public CompletableFuture<DeviceAuthResponse> startDeviceAuthAsync()
	{
		return transport.startDeviceAuthAsync();
	}

	public CompletableFuture<DeviceStatusResponse> pollDeviceStatusAsync(String deviceCode)
	{
		return transport.pollDeviceStatusAsync(deviceCode);
	}

	public CompletableFuture<Boolean> fetchEntitlementsAsync(String rsn)
	{
		return transport.fetchEntitlementsAsync(rsn);
	}

	public void setAuthToken(String token)
	{
		transport.setAuthToken(token);
	}

	public CompletableFuture<Boolean> updateRSN(String rsn, String accountType)
	{
		return transport.updateRSN(rsn, accountType);
	}

	public void clearAuth()
	{
		transport.clearAuth();
	}

	public boolean isAuthenticated()
	{
		return transport.isAuthenticated();
	}

	public boolean isPremium()
	{
		return transport.isPremium();
	}

	public void setPremium(boolean premium)
	{
		transport.setPremium(premium);
	}

	public boolean isRsnBlocked()
	{
		return transport.isRsnBlocked();
	}

	public void setOnAuthFailure(Runnable callback)
	{
		transport.setOnAuthFailure(callback);
	}

	public void setOnRefreshTokenChanged(Consumer<String> callback)
	{
		transport.setOnRefreshTokenChanged(callback);
	}

	// ============================================================================
	// Flips / analysis / recommendations
	// ============================================================================

	public CompletableFuture<FlipAnalysis> getItemAnalysisAsync(int itemId)
	{
		return flips.getItemAnalysisAsync(itemId);
	}

	public CompletableFuture<FlipFinderResponse> getFlipRecommendationsAsync(
		Integer cashStack, String flipStyle, int limit, Integer randomSeed, String timeframe, String rsn,
		Integer filledSlots, boolean isMembersWorld)
	{
		return flips.getFlipRecommendationsAsync(cashStack, flipStyle, limit, randomSeed, timeframe, rsn,
			filledSlots, isMembersWorld, config.minimumProfit(), config.minimumVolume());
	}

	public CompletableFuture<PluginSyncResponse> getPluginSyncAsync(
		Integer cashStack, Integer inventoryGp, String flipStyle, int limit, Integer randomSeed, String timeframe,
		String rsn, Integer filledSlots, boolean isMembersWorld)
	{
		return flips.getPluginSyncAsync(cashStack, inventoryGp, flipStyle, limit, randomSeed, timeframe, rsn,
			filledSlots, isMembersWorld, config.minimumProfit(), config.minimumVolume());
	}

	public CompletableFuture<Boolean> pushRsnCapitalAsync(String rsn, Integer inventoryGp)
	{
		return flips.pushRsnCapitalAsync(rsn, inventoryGp);
	}

	@Deprecated(since = "1.5.0", forRemoval = true)
	@SuppressWarnings("removal")
	public CompletableFuture<TimeframeFlipFinderResponse> getTimeframeFlipRecommendationsAsync(
		String timeframe, Integer cashStack, int limit, Integer priceOffset)
	{
		return flips.getTimeframeFlipRecommendationsAsync(timeframe, cashStack, limit, priceOffset);
	}

	public CompletableFuture<FlipAdjustmentResponse> getFlipAdjustmentAsync(FlipAdjustmentRequest req)
	{
		return flips.getFlipAdjustmentAsync(req);
	}

	public void clearCache()
	{
		flips.clearCache();
	}

	public void invalidateCache(int itemId)
	{
		flips.invalidateCache(itemId);
	}

	// ============================================================================
	// Transactions
	// ============================================================================

	public CompletableFuture<Void> recordTransactionAsync(TransactionRequest request)
	{
		return transactions.recordTransactionAsync(request);
	}

	public CompletableFuture<Void> recordTransactionAsync(int itemId, String itemName, String transactionType,
			int quantity, int pricePerItem, String rsn)
	{
		return transactions.recordTransactionAsync(itemId, itemName, transactionType, quantity, pricePerItem, rsn);
	}

	public CompletableFuture<Void> recordHistoryBackfillBatchAsync(String rsn, java.util.List<HistoryBackfillEntry> entries)
	{
		return transactions.recordHistoryBackfillBatchAsync(rsn, entries);
	}

	// ============================================================================
	// Active flips / completed flips / statistics
	// ============================================================================

	public CompletableFuture<ActiveFlipsResponse> getActiveFlipsAsync(String rsn)
	{
		return activeFlips.getActiveFlipsAsync(rsn);
	}

	public CompletableFuture<ActiveFlipsResponse> getActiveFlipsAsync()
	{
		return activeFlips.getActiveFlipsAsync();
	}

	public CompletableFuture<Boolean> dismissActiveFlipAsync(int itemId)
	{
		return activeFlips.dismissActiveFlipAsync(itemId);
	}

	public CompletableFuture<Boolean> dismissActiveFlipAsync(int itemId, String rsn)
	{
		return activeFlips.dismissActiveFlipAsync(itemId, rsn);
	}

	public CompletableFuture<Boolean> cleanupStaleFlipsAsync(java.util.Set<Integer> activeItemIds, String rsn)
	{
		return activeFlips.cleanupStaleFlipsAsync(activeItemIds, rsn);
	}

	public CompletableFuture<Boolean> syncActiveFlipAsync(int itemId, String itemName, int filledQuantity,
			int orderQuantity, int pricePerItem, String rsn)
	{
		return activeFlips.syncActiveFlipAsync(itemId, itemName, filledQuantity, orderQuantity, pricePerItem, rsn);
	}

	public CompletableFuture<Boolean> markActiveFlipSellingAsync(int itemId, String rsn)
	{
		return activeFlips.markActiveFlipSellingAsync(itemId, rsn);
	}

	public CompletableFuture<CompletedFlipsResponse> getCompletedFlipsAsync(int limit, String rsn)
	{
		return activeFlips.getCompletedFlipsAsync(limit, rsn);
	}

	public CompletableFuture<CompletedFlipsResponse> getCompletedFlipsAsync(int limit)
	{
		return activeFlips.getCompletedFlipsAsync(limit);
	}

	public CompletableFuture<FlipStatisticsResponse> getFlipStatisticsAsync(int days, String rsn)
	{
		return activeFlips.getFlipStatisticsAsync(days, rsn);
	}

	// ============================================================================
	// Bank snapshots
	// ============================================================================

	public CompletableFuture<BankSnapshotResult> createBankSnapshotAsync(
		String rsn,
		java.util.List<BankItem> items,
		java.util.List<BankItemId> inventoryItems,
		java.util.List<BankItemId> gearItems,
		long geOffersValue)
	{
		return bankSnapshots.createBankSnapshotAsync(rsn, items, inventoryItems, gearItems, geOffersValue);
	}

	// ============================================================================
	// Market data: wiki prices, daily volume, dumps
	// ============================================================================

	public WikiPrice getWikiPrice(int itemId)
	{
		return marketData.getWikiPrice(itemId);
	}

	public void fetchWikiPrices()
	{
		marketData.fetchWikiPrices();
	}

	public boolean needsWikiPriceRefresh()
	{
		return marketData.needsWikiPriceRefresh();
	}

	public void getDumpsAsync(String sortBy, int minProfit, int limit,
	                          Consumer<DumpEvent[]> onSuccess,
	                          Consumer<String> onError)
	{
		marketData.getDumpsAsync(sortBy, minProfit, limit, onSuccess, onError);
	}

	public void getDumpsAsync(Consumer<DumpEvent[]> onSuccess, Consumer<String> onError)
	{
		marketData.getDumpsAsync(onSuccess, onError);
	}

	public CompletableFuture<Integer> getDailyVolumeAsync(int itemId)
	{
		return marketData.getDailyVolumeAsync(itemId);
	}

	public CompletableFuture<Integer> peekCachedDailyVolume(int itemId)
	{
		return marketData.peekCachedDailyVolume(itemId);
	}

	public Integer getCachedDailyVolume(int itemId)
	{
		return marketData.getCachedDailyVolume(itemId);
	}

	// ============================================================================
	// Blocklists
	// ============================================================================

	public CompletableFuture<BlocklistsResponse> getBlocklistsAsync()
	{
		return blocklists.getBlocklistsAsync();
	}

	public CompletableFuture<Boolean> addItemToBlocklistAsync(int blocklistId, int itemId, String reason)
	{
		return blocklists.addItemToBlocklistAsync(blocklistId, itemId, reason);
	}

	public CompletableFuture<Boolean> addItemToBlocklistAsync(int blocklistId, int itemId)
	{
		return blocklists.addItemToBlocklistAsync(blocklistId, itemId);
	}

	// ============================================================================
	// Offer actions
	// ============================================================================

	public CompletableFuture<OfferAdviceBatchResponse> postOfferActionsBatchAsync(java.util.List<OfferAdviceRequest> reqs)
	{
		return offerActions.postOfferActionsBatchAsync(reqs);
	}

	public CompletableFuture<SellPriceCheckResponse> postSellPriceCheckAsync(SellPriceCheckRequest req)
	{
		return offerActions.postSellPriceCheckAsync(req);
	}

	// ============================================================================
	// Webhooks
	// ============================================================================

	public void updateWebhookAsync(
		String webhookUrl,
		boolean notifySale,
		boolean notifySuggestion,
		Runnable onSuccess,
		Consumer<String> onError
	)
	{
		webhooks.updateWebhookAsync(webhookUrl, notifySale, notifySuggestion, onSuccess, onError);
	}

	public void fetchWebhookConfigAsync(
		Consumer<JsonObject> onSuccess,
		Runnable onNotFound,
		Consumer<String> onError
	)
	{
		webhooks.fetchWebhookConfigAsync(onSuccess, onNotFound, onError);
	}

	public void deleteWebhookAsync(Runnable onSuccess, Consumer<String> onError)
	{
		webhooks.deleteWebhookAsync(onSuccess, onError);
	}

	// ============================================================================
	// MOTD
	// ============================================================================

	public CompletableFuture<MotdResponse> getMotdAsync()
	{
		return motd.getMotdAsync();
	}

	// ============================================================================
	// Trade Station
	// ============================================================================

	public CompletableFuture<Boolean> pushTradeStationSlotsAsync(String rsn, java.util.List<Integer> itemIds)
	{
		return tradeStation.pushTradeStationSlotsAsync(rsn, itemIds);
	}

	// ============================================================================
	// Static JSON body builders + in-flight dedup helper.
	// Kept here as part of the stable, package-visible test surface.
	// ============================================================================

	public static JsonObject buildOfferActionBody(OfferAdviceRequest req)
	{
		JsonObject body = new JsonObject();
		body.addProperty(JSON_KEY_ITEM_ID, req.getItemId());
		body.addProperty("pool", req.getPool());
		body.addProperty("side", req.getSide());
		body.addProperty("stage", req.getStage());
		String listedAt = req.getListedAtMillis() == null ? null : OfferAdviceRequest.toIsoUtc(req.getListedAtMillis());
		if (listedAt != null)
		{
			body.addProperty("listed_at", listedAt);
		}
		body.addProperty("listed_price", req.getListedPrice());
		body.addProperty("listed_quantity", req.getListedQuantity());
		body.addProperty("filled_quantity", req.getFilledQuantity());
		String lastFill = req.getLastFillAtMillis() == null ? null : OfferAdviceRequest.toIsoUtc(req.getLastFillAtMillis());
		if (lastFill != null)
		{
			body.addProperty("last_fill_at", lastFill);
		}
		if (req.getCurrentMarketHigh() != null)
		{
			body.addProperty("current_market_high", req.getCurrentMarketHigh());
		}
		if (req.getCurrentMarketLow() != null)
		{
			body.addProperty("current_market_low", req.getCurrentMarketLow());
		}
		if (req.getUserAvgBuyPrice() != null)
		{
			body.addProperty("user_avg_buy_price", req.getUserAvgBuyPrice());
		}
		if (req.getOriginalMargin() != null)
		{
			body.addProperty("original_margin", req.getOriginalMargin());
		}
		if (req.getPreviousPositionMargin() != null)
		{
			body.addProperty("previous_position_margin", req.getPreviousPositionMargin());
		}
		body.addProperty("consecutive_margin_decreases", req.getConsecutiveMarginDecreases());
		body.addProperty("cumulative_margin_reduction_pct", req.getCumulativeMarginReductionPct());
		return body;
	}

	public static JsonObject buildOfferActionsBody(java.util.List<OfferAdviceRequest> reqs)
	{
		JsonObject body = new JsonObject();
		com.google.gson.JsonArray offers = new com.google.gson.JsonArray();
		for (OfferAdviceRequest req : reqs)
		{
			offers.add(buildOfferActionBody(req));
		}
		body.add("offers", offers);
		return body;
	}

	public static JsonObject buildSellPriceCheckBody(SellPriceCheckRequest req)
	{
		JsonObject body = new JsonObject();
		body.addProperty(JSON_KEY_ITEM_ID, req.getItemId());
		body.addProperty("original_sell_price", req.getOriginalSellPrice());
		body.addProperty("current_market_high", req.getCurrentMarketHigh());
		body.addProperty("daily_volume", req.getDailyVolume());
		if (req.getTimeframe() != null)
		{
			body.addProperty("timeframe", req.getTimeframe());
		}
		if (req.getStyle() != null)
		{
			body.addProperty("style", req.getStyle());
		}
		if (req.getRsn() != null)
		{
			body.addProperty("rsn", req.getRsn());
		}
		return body;
	}

	/**
	 * Share a single in-flight fetch per key. Callers arriving while a fetch is
	 * running get the same future instead of starting their own; the entry is
	 * cleared on completion so a later (post-TTL) call can fetch again. Callers
	 * are serialized on the client thread, so a plain get-then-put is safe here.
	 */
	public static CompletableFuture<Integer> dedupeInFlight(
		Map<Integer, CompletableFuture<Integer>> inFlight,
		int itemId,
		Supplier<CompletableFuture<Integer>> fetcher)
	{
		CompletableFuture<Integer> existing = inFlight.get(itemId);
		if (existing != null)
		{
			return existing;
		}
		CompletableFuture<Integer> future = fetcher.get();
		inFlight.put(itemId, future);
		future.whenComplete((v, ex) -> inFlight.remove(itemId, future));
		return future;
	}
}
