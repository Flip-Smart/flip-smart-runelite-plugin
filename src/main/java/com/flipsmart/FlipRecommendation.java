package com.flipsmart;

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
	private int instantBuyPrice;

	@SerializedName("instant_sell_price")
	private int instantSellPrice;

	// Recommended prices (for GE offers)
	@SerializedName("recommended_buy_price")
	private int recommendedBuyPrice;

	@SerializedName("recommended_sell_price")
	private int recommendedSellPrice;

	@SerializedName("recommended_quantity")
	private int recommendedQuantity;

	private int margin;

	@SerializedName("roi_percent")
	private double roiPercent;

	@SerializedName("ge_tax")
	private int geTax;

	@SerializedName("liquidity_score")
	private double liquidityScore;

	@SerializedName("liquidity_rating")
	private String liquidityRating;

	@SerializedName("volume_per_hour")
	private double volumePerHour;

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
	private int potentialProfit;

	@SerializedName("total_cost")
	private int totalCost;

	// Deprecated fields (kept for backwards compatibility)
	@SerializedName("buy_price")
	private Integer buyPrice;

	@SerializedName("sell_price")
	private Integer sellPrice;

	@SerializedName("cost_per_flip")
	private Integer costPerFlip;

	/**
	 * Get formatted margin text
	 */
	public String getFormattedMargin()
	{
		return GpUtils.formatGPWithSuffix(margin);
	}

	/**
	 * Get formatted ROI text
	 */
	public String getFormattedROI()
	{
		return GpUtils.formatROI(roiPercent);
	}

	/**
	 * Get formatted potential profit text
	 */
	public String getFormattedPotentialProfit()
	{
		return GpUtils.formatGPWithSuffix(potentialProfit);
	}
}

