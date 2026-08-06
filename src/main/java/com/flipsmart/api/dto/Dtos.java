package com.flipsmart.api.dto;

import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.flip.CompletedFlip;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.domain.offer.OfferAction;
import com.flipsmart.util.GpUtils;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Wire types for the FlipSmart API, grouped into one container.
 *
 * <p>These are small data holders that each cost a package declaration and an import
 * block of their own as separate files. Nesting them here removes that per-file
 * overhead, which the plugin-hub token budget counts. Every nested type stays
 * {@code public static} so Gson can instantiate it and call sites can import it
 * directly as {@code com.flipsmart.api.dto.Dtos.TypeName}.
 */
public final class Dtos
{
	private Dtos()
	{
	}

	/**
	 * Response from the active flips API endpoint
	 */
	@Data
	public static class ActiveFlipsResponse
	{
		@SerializedName("active_flips")
		private List<ActiveFlip> activeFlips;

		@SerializedName("total_items")
		private int totalItems;

		@SerializedName("total_invested")
		private long totalInvested;
	}

	/**
	 * Authentication result with status and message
	 */
	public static class AuthResult
	{
		public final boolean success;
		public final String message;

		public AuthResult(boolean success, String message)
		{
			this.success = success;
			this.message = message;
		}
	}

	/**
	 * Data class for bank snapshot item
	 */
	public static class BankItem
	{
		public final int itemId;
		public final int quantity;
		public final int valuePerItem;

		public BankItem(int itemId, int quantity, int valuePerItem)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.valuePerItem = valuePerItem;
		}
	}

	/**
	 * Data class for an inventory or gear item.
	 * Backend prices these server-side, so no value_per_item is sent.
	 */
	public static class BankItemId
	{
		public final int itemId;
		public final int quantity;

		public BankItemId(int itemId, int quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
		}
	}

	/**
	 * Response from creating a bank snapshot
	 */
	@Data
	public static class BankSnapshotResponse
	{
		private int id;

		private String rsn;

		@SerializedName("total_value")
		private long totalValue;

		@SerializedName("inventory_value")
		private long inventoryValue;

		@SerializedName("ge_offers_value")
		private long geOffersValue;

		@SerializedName("total_wealth")
		private long totalWealth;

		@SerializedName("item_count")
		private int itemCount;

		@SerializedName("snapshot_time")
		private String snapshotTime;

		private String message;
	}

	/**
	 * Outcome of a bank snapshot upload. The server answers HTTP 429 when a
	 * snapshot was already taken inside its 24h window; that is an expected
	 * outcome rather than a failure, so callers can back off without surfacing
	 * an error to the player.
	 */
	public static final class BankSnapshotResult
	{
		@Getter
		private final BankSnapshotResponse response;
		@Getter
		private final boolean rateLimited;

		private BankSnapshotResult(BankSnapshotResponse response, boolean rateLimited)
		{
			this.response = response;
			this.rateLimited = rateLimited;
		}

		public static BankSnapshotResult success(BankSnapshotResponse response)
		{
			return new BankSnapshotResult(response, false);
		}

		public static BankSnapshotResult rateLimitedResult()
		{
			return new BankSnapshotResult(null, true);
		}

		public static BankSnapshotResult failure()
		{
			return new BankSnapshotResult(null, false);
		}

		public boolean isSuccess()
		{
			return response != null;
		}

	}

	/**
	 * Summary of a blocklist for display in the plugin dropdown.
	 */
	@Data
	public static class BlocklistSummary
	{
		private int id;
		private String name;
		private String description;
		@SerializedName("item_count")
		private int itemCount;
		@SerializedName("share_id")
		private String shareId;
		@SerializedName("is_public")
		private boolean isPublic;
		@SerializedName("is_active")
		private boolean isActive;
		@SerializedName("created_at")
		private String createdAt;
		@SerializedName("updated_at")
		private String updatedAt;
	}

	/**
	 * Response from the /blocklists endpoint containing user's blocklists.
	 */
	@Data
	public static class BlocklistsResponse
	{
		private List<BlocklistSummary> blocklists;
		private int count;
	}

	/**
	 * Response from the completed flips API endpoint
	 */
	@Data
	public static class CompletedFlipsResponse
	{
		@SerializedName("flips")
		private List<CompletedFlip> flips;

		@SerializedName("count")
		private int count;
	}

	/**
	 * Response from starting device authorization
	 */
	public static class DeviceAuthResponse
	{
		private String deviceCode;
		private String userCode;
		private String verificationUrl;
		private int expiresIn;
		private int pollInterval;

		/** Default constructor required for Gson deserialization */
		public DeviceAuthResponse() { }

		public String getDeviceCode() { return deviceCode; }
		public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }

		public String getUserCode() { return userCode; }
		public void setUserCode(String userCode) { this.userCode = userCode; }

		public String getVerificationUrl() { return verificationUrl; }
		public void setVerificationUrl(String verificationUrl) { this.verificationUrl = verificationUrl; }

		public int getExpiresIn() { return expiresIn; }
		public void setExpiresIn(int expiresIn) { this.expiresIn = expiresIn; }

		public int getPollInterval() { return pollInterval; }
		public void setPollInterval(int pollInterval) { this.pollInterval = pollInterval; }
	}

	/**
	 * Response from polling device authorization status
	 */
	public static class DeviceStatusResponse
	{
		private String status;  // pending, authorized, expired
		private String accessToken;
		private String tokenType;
		private String refreshToken;  // For session persistence across client restarts

		/** Default constructor required for Gson deserialization */
		public DeviceStatusResponse() { }

		public String getStatus() { return status; }
		public void setStatus(String status) { this.status = status; }

		public String getAccessToken() { return accessToken; }
		public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

		public String getTokenType() { return tokenType; }
		public void setTokenType(String tokenType) { this.tokenType = tokenType; }

		public String getRefreshToken() { return refreshToken; }
		public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
	}

	/**
	 * Parsed {@code GET /auth/entitlements} response. Replaces the hand-rolled
	 * {@code JsonObject} walk that previously lived in the transport.
	 *
	 * <p>Premium is held as a raw {@link JsonElement} rather than a {@code Boolean}
	 * so a non-primitive {@code is_premium} (object/array) defaults to false instead
	 * of throwing during deserialization, preserving the original defensive parse.
	 */
	public static class EntitlementsResponse
	{
		private static final String STATUS_BLOCKED = "blocked";

		@SerializedName("is_premium")
		private JsonElement premiumElement;

		@SerializedName("rsn_entitlement")
		private RsnEntitlement rsnEntitlement;

		public static EntitlementsResponse fromJson(Gson gson, String body)
		{
			EntitlementsResponse parsed = gson.fromJson(body, EntitlementsResponse.class);
			return parsed != null ? parsed : new EntitlementsResponse();
		}

		public boolean isPremium()
		{
			return premiumElement != null && premiumElement.isJsonPrimitive() && premiumElement.getAsBoolean();
		}

		public boolean isRsnBlocked()
		{
			return rsnEntitlement != null && STATUS_BLOCKED.equals(rsnEntitlement.status);
		}

		private static class RsnEntitlement
		{
			@SerializedName("status")
			private String status;
		}
	}

	@Data
	public static class FavoriteItem
	{
		@SerializedName("item_id")
		private int itemId;

		@SerializedName("item_name")
		private String itemName;

		@SerializedName("icon_url")
		private String iconUrl;

		@SerializedName("buy_price")
		private Integer buyPrice;

		@SerializedName("sell_price")
		private Integer sellPrice;

		@SerializedName("buy_limit")
		private Integer buyLimit;

		private int margin;

		private int profit;

		private int volume;

		@SerializedName("risk_score")
		private int riskScore;

		@SerializedName("risk_rating")
		private String riskRating;
	}

	@Data
	public static class FavoritesResponse
	{
		private List<FavoriteItem> items;

		private int count;
	}

	/**
	 * Request parameters for the flip adjustment API.
	 */
	@Builder
	public static class FlipAdjustmentRequest
	{
		public final int itemId;
		public final boolean isBuyOffer;
		public final int offerPrice;
		public final int averageBuyPrice;
		public final int minutesSinceOffer;
		public final int adjustmentCount;
		public final int quantityFilled;
		public final int totalQuantity;
		public final String timeframe;
		public final String rsn;
		public final String style;
	}

	/**
	 * Response from the /flips/adjustment API endpoint.
	 * Contains a recommendation for adjusting a stale flip offer.
	 */
	@Data
	public static class FlipAdjustmentResponse
	{
		private String action;

		@SerializedName("recommended_price")
		private Integer recommendedPrice;

		@SerializedName("current_margin")
		private Integer currentMargin;

		@SerializedName("is_profitable")
		private boolean isProfitable;

		@SerializedName("breakeven_price")
		private int breakevenPrice;

		@SerializedName("minutes_elapsed")
		private int minutesElapsed;

		@SerializedName("threshold_minutes")
		private int thresholdMinutes;

		@SerializedName("adjustment_count")
		private int adjustmentCount;

		private String message;

		@SerializedName("daily_volume")
		private Integer dailyVolume;

		@SerializedName("next_check_minutes")
		private Integer nextCheckMinutes;

		/**
		 * Whether this response recommends taking an action (not hold).
		 */
		public boolean isActionRequired()
		{
			return !"hold".equals(action);
		}

		/**
		 * Whether this response recommends adjusting a buy price.
		 */
		public boolean isReadjustBuy()
		{
			return "readjust_buy".equals(action);
		}

		/**
		 * Whether this response recommends adjusting a sell price.
		 */
		public boolean isReadjustSell()
		{
			return "readjust_sell".equals(action);
		}

		/**
		 * Whether this response recommends cancelling and selling.
		 */
		public boolean isCancelAndSell()
		{
			return "cancel_and_sell".equals(action);
		}
	}

	@Data
	public static class FlipFinderResponse
	{
		@SerializedName("flip_style")
		private String flipStyle;

		@SerializedName("cash_stack")
		private Integer cashStack;

		@SerializedName("per_slot_budget")
		private Double perSlotBudget;

		@SerializedName("total_items_analyzed")
		private int totalItemsAnalyzed;

		@SerializedName("items_matching_criteria")
		private int itemsMatchingCriteria;

		private List<FlipRecommendation> recommendations;

		private Subscription subscription;

		/**
		 * Check if the user has premium subscription
		 */
		public boolean isPremium()
		{
			return subscription != null && "premium".equals(subscription.getTier());
		}

		@Data
		public static class Subscription
		{
			private String tier;

			@SerializedName("recommendation_limit")
			private Integer recommendationLimit;

			@SerializedName("recommendations_returned")
			private int recommendationsReturned;
		}
	}

	/**
	 * Response from the /flips/statistics API endpoint.
	 * Provides aggregate flip performance stats over a time period.
	 */
	@Data
	public static class FlipStatisticsResponse
	{
		@SerializedName("total_flips")
		private int totalFlips;

		@SerializedName("successful_flips")
		private int successfulFlips;

		@SerializedName("total_profit")
		private long totalProfit;

		@SerializedName("success_rate")
		private double successRate;

		@SerializedName("average_roi")
		private double averageRoi;
	}

	/**
	 * Single GE History row, used by recordHistoryBackfillBatchAsync.
	 */
	public static class HistoryBackfillEntry
	{
		public final int itemId;
		public final String itemName;
		public final boolean isBuy;
		public final int quantity;
		public final int pricePerItem;
		public final Long offerId;

		public HistoryBackfillEntry(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem, Long offerId)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.isBuy = isBuy;
			this.quantity = quantity;
			this.pricePerItem = pricePerItem;
			this.offerId = offerId;
		}
	}

	@Data
	public static class OfferAdviceBatchResponse
	{
		private List<OfferAdviceResult> results;
	}

	@Getter
	@Builder
	public static class OfferAdviceRequest
	{
		private final int itemId;
		private final String pool;
		private final String side;
		private final String stage;
		private final Long listedAtMillis;
		private final int listedPrice;
		private final int listedQuantity;
		private final int filledQuantity;
		private final Long lastFillAtMillis;
		private final Integer currentMarketHigh;
		private final Integer currentMarketLow;
		private final Integer userAvgBuyPrice;

		// Courier state (#918): the backend advisor is stateless, so cross-poll
		// state travels on the request and is echoed back from the previous response.
		private final Integer originalMargin;
		private final Integer previousPositionMargin;
		private final int consecutiveMarginDecreases;
		private final double cumulativeMarginReductionPct;

		public static String toIsoUtc(long epochMillis)
		{
			if (epochMillis <= 0)
			{
				return null;
			}
			return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
		}
	}

	@Data
	public static class OfferAdviceResponse
	{
		private String action;
		private String reason;

		@SerializedName("new_price")
		private Integer newPrice;

		@SerializedName("net_profit_estimate")
		private Integer netProfitEstimate;

		// Courier state (#918) echoed back to the plugin for the next poll.
		@SerializedName("position_margin")
		private Integer positionMargin;

		@SerializedName("consecutive_margin_decreases")
		private int consecutiveMarginDecreases;

		@SerializedName("cumulative_margin_reduction_pct")
		private double cumulativeMarginReductionPct;

		@Getter @Setter
		private transient Integer itemIdHint;

		public OfferAction getActionEnum()
		{
			return OfferAction.fromWire(action);
		}
	}

	@Data
	@EqualsAndHashCode(callSuper = true)
	public static class OfferAdviceResult extends OfferAdviceResponse
	{
		@SerializedName("item_id")
		private int itemId;
	}

	/**
	 * Parsed {@code GET /plugin/sync} response — the bundled 2-minute poll.
	 *
	 * Each sub-payload is byte-equal to its standalone endpoint's response, so the
	 * existing per-endpoint DTOs deserialize the bundle directly. Any field may be
	 * null when that sub-fetch failed server-side; callers skip a null payload and
	 * leave the corresponding UI section unchanged. The webhook and _meta fields
	 * from the endpoint are intentionally omitted — the poll does not consume them.
	 */
	@Data
	public static class PluginSyncResponse
	{
		@SerializedName("version")
		private int version;

		@SerializedName("flip_finder")
		private FlipFinderResponse flipFinder;

		@SerializedName("active_flips")
		private ActiveFlipsResponse activeFlips;

		@SerializedName("completed_flips")
		private CompletedFlipsResponse completedFlips;

		@SerializedName("statistics")
		private FlipStatisticsResponse statistics;

		@SerializedName("favorite_item_ids")
		private java.util.List<Integer> favoriteItemIds;

		// The endpoint also returns `entitlements`, but the poll does not consume it:
		// premium is sourced from the flip_finder payload (see FlipFinderPanel), and the
		// EntitlementSnapshot shape has no top-level is_premium for this DTO to read.
	}

	@Getter
	@Builder
	public static class SellPriceCheckRequest
	{
		private final int itemId;
		private final int originalSellPrice;
		private final int currentMarketHigh;
		private final int dailyVolume;
		private final String timeframe;
		private final String style;
		private final String rsn;
	}

	@Data
	public static class SellPriceCheckResponse
	{
		@SerializedName("recommended_sell_price")
		private int recommendedSellPrice;

		private boolean adjusted;

		private String reason;
	}

	/**
	 * Data class for transaction request parameters (use Builder to construct)
	 */
	public static class TransactionRequest
	{
		public final int itemId;
		public final String itemName;
		public final boolean isBuy;
		public final int quantity;
		public final int pricePerItem;
		public final Integer geSlot;
		public final Integer recommendedSellPrice;
		public final String rsn;
		public final Integer totalQuantity;
		public final String idempotencyKey;
		public final Long offerId;
		public final Integer roundTripId;
		public final Integer slotGeneration;

		private TransactionRequest(Builder builder)
		{
			this.itemId = builder.itemId;
			this.itemName = builder.itemName;
			this.isBuy = builder.isBuy;
			this.quantity = builder.quantity;
			this.pricePerItem = builder.pricePerItem;
			this.geSlot = builder.geSlot;
			this.recommendedSellPrice = builder.recommendedSellPrice;
			this.rsn = builder.rsn;
			this.totalQuantity = builder.totalQuantity;
			this.idempotencyKey = builder.idempotencyKey;
			this.offerId = builder.offerId;
			this.roundTripId = builder.roundTripId;
			this.slotGeneration = builder.slotGeneration;
		}

		public static Builder builder(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem)
		{
			return new Builder(itemId, itemName, isBuy, quantity, pricePerItem);
		}

		public static class Builder
		{
			private final int itemId;
			private final String itemName;
			private final boolean isBuy;
			private final int quantity;
			private final int pricePerItem;
			private Integer geSlot;
			private Integer recommendedSellPrice;
			private String rsn;
			private Integer totalQuantity;
			private String idempotencyKey;
			private Long offerId;
			private Integer roundTripId;
			private Integer slotGeneration;

			private Builder(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem)
			{
				this.itemId = itemId;
				this.itemName = itemName;
				this.isBuy = isBuy;
				this.quantity = quantity;
				this.pricePerItem = pricePerItem;
			}

			public Builder geSlot(Integer geSlot) { this.geSlot = geSlot; return this; }
			public Builder recommendedSellPrice(Integer price) { this.recommendedSellPrice = price; return this; }
			public Builder rsn(String rsn) { this.rsn = rsn; return this; }
			public Builder totalQuantity(Integer qty) { this.totalQuantity = qty; return this; }
			public Builder idempotencyKey(String key) { this.idempotencyKey = key; return this; }
			public Builder offerId(Long offerId) { this.offerId = offerId; return this; }
			public Builder roundTripId(Integer roundTripId) { this.roundTripId = roundTripId; return this; }
			public Builder slotGeneration(Integer generation) { this.slotGeneration = generation; return this; }

			public TransactionRequest build() { return new TransactionRequest(this); }
		}
	}

	/**
	 * Real-time price data from the wiki API
	 */
	public static class WikiPrice
	{
		// Public so MarketDataEndpoints can share this instead of keeping its own
		// copy in sync by hand.
		public static final long WIKI_PRICE_CACHE_DURATION_MS = 60_000; // 1 minute cache

		public final int instaBuy;   // High price - what buyers pay to instant-buy
		public final int instaSell;  // Low price - what sellers receive when instant-selling
		public final long fetchedAt;

		public WikiPrice(int instaBuy, int instaSell)
		{
			this.instaBuy = instaBuy;
			this.instaSell = instaSell;
			this.fetchedAt = System.currentTimeMillis();
		}

		public boolean isExpired()
		{
			return System.currentTimeMillis() - fetchedAt > WIKI_PRICE_CACHE_DURATION_MS;
		}

		/**
		 * Midpoint of instant-buy and instant-sell, used as a safe exit fallback when
		 * a mode's target price is unavailable. Degrades to whichever side is present.
		 */
		public int midPrice()
		{
			if (instaBuy <= 0)
			{
				return Math.max(instaSell, 0);
			}
			if (instaSell <= 0)
			{
				return instaBuy;
			}
			return (int) (((long) instaBuy + instaSell) / 2);
		}
	}

}
