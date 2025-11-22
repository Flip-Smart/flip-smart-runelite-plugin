package com.flipsmart;

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
	private int buyPricePerItem;

	@SerializedName("buy_total")
	private int buyTotal;

	@SerializedName("buy_time")
	private String buyTime;

	@SerializedName("sell_price_per_item")
	private int sellPricePerItem;

	@SerializedName("sell_total")
	private int sellTotal;

	@SerializedName("sell_time")
	private String sellTime;

	@SerializedName("gross_profit")
	private int grossProfit;

	@SerializedName("ge_tax")
	private int geTax;

	@SerializedName("net_profit")
	private int netProfit;

	@SerializedName("roi_percent")
	private double roiPercent;

	@SerializedName("flip_duration_seconds")
	private int flipDurationSeconds;

	@SerializedName("is_successful")
	private boolean isSuccessful;
}

