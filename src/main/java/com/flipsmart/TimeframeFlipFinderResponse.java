package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TimeframeFlipFinderResponse
{
	private TimeframeInfo timeframe;

	@SerializedName("cash_stack")
	private Integer cashStack;

	@SerializedName("max_investment_per_slot")
	private Integer maxInvestmentPerSlot;

	@SerializedName("total_items_analyzed")
	private int totalItemsAnalyzed;

	@SerializedName("total_recommendations")
	private int totalRecommendations;

	private List<TimeframeRecommendation> recommendations;

	private TimeframeTotals totals;

	private FlipFinderResponse.Subscription subscription;

	/**
	 * Check if the user has premium subscription
	 */
	public boolean isPremium()
	{
		return subscription != null && "premium".equals(subscription.getTier());
	}

	@Data
	public static class TimeframeInfo
	{
		private String name;

		@SerializedName("lookback_hours")
		private int lookbackHours;

		private Map<String, Double> weights;
	}

	@Data
	public static class TimeframeTotals
	{
		@SerializedName("total_investment")
		private int totalInvestment;

		@SerializedName("total_potential_profit")
		private int totalPotentialProfit;

		@SerializedName("overall_roi_percent")
		private double overallRoiPercent;
	}

	@Data
	public static class TimeframeRecommendation
	{
		@SerializedName("item_id")
		private int itemId;

		@SerializedName("item_name")
		private String itemName;

		private boolean members;

		@SerializedName("buy_limit")
		private Integer buyLimit;

		// Current market prices
		@SerializedName("instant_buy_price")
		private int instantBuyPrice;

		@SerializedName("instant_sell_price")
		private int instantSellPrice;

		// Recommended prices
		@SerializedName("recommended_buy_price")
		private int recommendedBuyPrice;

		@SerializedName("recommended_sell_price")
		private int recommendedSellPrice;

		@SerializedName("recommended_quantity")
		private int recommendedQuantity;

		// Profit metrics
		private int margin;

		@SerializedName("roi_percent")
		private double roiPercent;

		@SerializedName("ge_tax")
		private Integer geTax;

		// Volume/liquidity
		@SerializedName("volume_per_hour")
		private double volumePerHour;

		@SerializedName("daily_volume")
		private int dailyVolume;

		// Legacy compatibility scores
		@SerializedName("liquidity_score")
		private double liquidityScore;

		@SerializedName("liquidity_rating")
		private String liquidityRating;

		@SerializedName("risk_score")
		private double riskScore;

		@SerializedName("risk_rating")
		private String riskRating;

		@SerializedName("efficiency_score")
		private double efficiencyScore;

		@SerializedName("efficiency_rating")
		private String efficiencyRating;

		// Factor scores (timeframe-specific)
		@SerializedName("factor_scores")
		private FactorScores factorScores;

		@SerializedName("weighted_score")
		private double weightedScore;

		// Profit projections
		@SerializedName("potential_profit")
		private int potentialProfit;

		@SerializedName("total_cost")
		private int totalCost;

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

		/**
		 * Convert to FlipRecommendation for UI compatibility
		 */
		public FlipRecommendation toFlipRecommendation()
		{
			FlipRecommendation rec = new FlipRecommendation();
			rec.setItemId(itemId);
			rec.setItemName(itemName);
			rec.setMembers(members);
			rec.setBuyLimit(buyLimit);
			rec.setInstantBuyPrice(instantBuyPrice);
			rec.setInstantSellPrice(instantSellPrice);
			rec.setRecommendedBuyPrice(recommendedBuyPrice);
			rec.setRecommendedSellPrice(recommendedSellPrice);
			rec.setRecommendedQuantity(recommendedQuantity);
			rec.setMargin(margin);
			rec.setRoiPercent(roiPercent);
			rec.setGeTax(geTax != null ? geTax : 0);
			rec.setVolumePerHour(volumePerHour);
			rec.setLiquidityScore(liquidityScore);
			rec.setLiquidityRating(liquidityRating);
			rec.setRiskScore(riskScore);
			rec.setRiskRating(riskRating);
			rec.setEfficiencyScore(efficiencyScore);
			rec.setEfficiencyRating(efficiencyRating);
			rec.setPotentialProfit(potentialProfit);
			rec.setTotalCost(totalCost);
			return rec;
		}
	}

	@Data
	public static class FactorScores
	{
		@SerializedName("norm_sharpe")
		private double normSharpe;

		@SerializedName("norm_deviation")
		private double normDeviation;

		@SerializedName("norm_success")
		private double normSuccess;

		@SerializedName("norm_volume")
		private double normVolume;

		@SerializedName("norm_spread_quality")
		private double normSpreadQuality;

		@SerializedName("norm_trend")
		private double normTrend;

		@SerializedName("norm_volatility_inverse")
		private double normVolatilityInverse;

		@SerializedName("norm_capital_efficiency")
		private double normCapitalEfficiency;

		@SerializedName("norm_update_risk_inverse")
		private double normUpdateRiskInverse;
	}

	/**
	 * Convert to FlipFinderResponse for UI compatibility
	 */
	public FlipFinderResponse toFlipFinderResponse()
	{
		FlipFinderResponse response = new FlipFinderResponse();
		response.setFlipStyle(timeframe != null ? timeframe.getName() : "unknown");
		response.setCashStack(cashStack);
		response.setPerSlotBudget(maxInvestmentPerSlot != null ? maxInvestmentPerSlot.doubleValue() : null);
		response.setTotalItemsAnalyzed(totalItemsAnalyzed);
		response.setItemsMatchingCriteria(totalRecommendations);
		response.setSubscription(subscription);

		if (recommendations != null)
		{
			java.util.List<FlipRecommendation> converted = new java.util.ArrayList<>();
			for (TimeframeRecommendation rec : recommendations)
			{
				converted.add(rec.toFlipRecommendation());
			}
			response.setRecommendations(converted);
		}

		return response;
	}
}
