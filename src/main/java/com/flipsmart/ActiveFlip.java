package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Represents an active flip (item bought but not yet sold)
 */
@Data
public class ActiveFlip
{
	@SerializedName("item_id")
	private int itemId;

	@SerializedName("item_name")
	private String itemName;

	@SerializedName("total_quantity")
	private int totalQuantity;

	@SerializedName("original_quantity")
	private int originalQuantity;

	@SerializedName("average_buy_price")
	private int averageBuyPrice;

	@SerializedName("total_invested")
	private int totalInvested;

	@SerializedName("first_buy_time")
	private String firstBuyTime;

	@SerializedName("last_buy_time")
	private String lastBuyTime;

	@SerializedName("transaction_count")
	private int transactionCount;

	@SerializedName("recommended_sell_price")
	private Integer recommendedSellPrice;

	@SerializedName("order_quantity")
	private int orderQuantity;

	@SerializedName("phase")
	private String phase;  // "buy" or "sell"
}

