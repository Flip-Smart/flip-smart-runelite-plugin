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

	@SerializedName("buy_price")
	private int buyPrice;

	@SerializedName("sell_price")
	private int sellPrice;

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

	@SerializedName("cost_per_flip")
	private int costPerFlip;

	/**
	 * Format GP amount for display
	 */
	public String formatGP(int amount)
	{
		if (amount >= 1_000_000)
		{
			return String.format("%.1fM", amount / 1_000_000.0);
		}
		else if (amount >= 1_000)
		{
			return String.format("%.1fK", amount / 1_000.0);
		}
		return String.valueOf(amount);
	}

	/**
	 * Get formatted margin text
	 */
	public String getFormattedMargin()
	{
		return formatGP(margin) + " gp";
	}

	/**
	 * Get formatted ROI text
	 */
	public String getFormattedROI()
	{
		return String.format("%.1f%%", roiPercent);
	}

	/**
	 * Get formatted potential profit text
	 */
	public String getFormattedPotentialProfit()
	{
		return formatGP(potentialProfit) + " gp";
	}
}

