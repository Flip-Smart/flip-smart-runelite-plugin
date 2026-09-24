package com.flipsmart.domain.flip;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class FlipRecommendation
{
	@SerializedName("item_id")
	private int itemId;

	@SerializedName("item_name")
	private String itemName;

	private boolean members;

	@SerializedName("buy_limit")
	private Integer buyLimit;

	// Instant prices (reference only)
	@SerializedName("instant_buy_price")
	private long instantBuyPrice;

	@SerializedName("instant_sell_price")
	private long instantSellPrice;

	// Recommended prices (for GE offers)
	@SerializedName("recommended_buy_price")
	private long recommendedBuyPrice;

	@SerializedName("recommended_sell_price")
	private long recommendedSellPrice;

	@SerializedName("recommended_quantity")
	private int recommendedQuantity;

	private long margin;

	@SerializedName("roi_percent")
	private double roiPercent;

	@SerializedName("ge_tax")
	private long geTax;

	@SerializedName("volume_per_hour")
	private double volumePerHour;

	@SerializedName("daily_volume")
	private int dailyVolume;

	@SerializedName("buy_limit_cycles")
	private int buyLimitCycles;

	@SerializedName("breakeven_sell_price")
	private long breakevenSellPrice;

	@SerializedName("breakeven_is_estimate")
	private boolean breakevenIsEstimate;

	@SerializedName("risk_score")
	private double riskScore;

	@SerializedName("risk_rating")
	private String riskRating;

	@SerializedName("efficiency_score")
	private double efficiencyScore;

	@SerializedName("efficiency_rating")
	private String efficiencyRating;

	@SerializedName("quantity_affordable")
	private Integer quantityAffordable;

	@SerializedName("potential_profit")
	private long potentialProfit;

	@SerializedName("total_cost")
	private long totalCost;

}

