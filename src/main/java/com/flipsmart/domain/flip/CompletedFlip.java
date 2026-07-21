package com.flipsmart.domain.flip;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Represents a completed flip (matched buy/sell pair)
 */
@Data
public class CompletedFlip
{
	@SerializedName("id")
	private int id;

	@SerializedName("item_id")
	private int itemId;

	@SerializedName("item_name")
	private String itemName;

	@SerializedName("quantity")
	private int quantity;

	@SerializedName("buy_price_per_item")
	private long buyPricePerItem;

	@SerializedName("buy_total")
	private long buyTotal;

	@SerializedName("buy_time")
	private String buyTime;

	@SerializedName("sell_price_per_item")
	private long sellPricePerItem;

	@SerializedName("sell_total")
	private long sellTotal;

	@SerializedName("sell_time")
	private String sellTime;

	@SerializedName("gross_profit")
	private long grossProfit;

	@SerializedName("ge_tax")
	private long geTax;

	@SerializedName("net_profit")
	private long netProfit;

	@SerializedName("roi_percent")
	private double roiPercent;

	@SerializedName("flip_duration_seconds")
	private int flipDurationSeconds;

	@SerializedName("is_successful")
	private boolean isSuccessful;

	// True when the recorded price came from an offline history backfill (a
	// blended average, not a live per-fill observation) — shown as an estimate.
	@SerializedName("price_is_estimated")
	private boolean priceIsEstimated;

	// True when matched buy legs exceed the item's 4h GE buy limit (possible
	// over-count from a re-emitted offer).
	@SerializedName("quantity_anomaly")
	private boolean quantityAnomaly;
}

