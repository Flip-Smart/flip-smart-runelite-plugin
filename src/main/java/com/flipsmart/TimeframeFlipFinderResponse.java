package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

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

	/**
	 * Timeframe recommendation extending FlipRecommendation with additional fields.
	 * Inherits all common fields to reduce code duplication.
	 */
	@Data
	@EqualsAndHashCode(callSuper = true)
	@ToString(callSuper = true)
	public static class TimeframeRecommendation extends FlipRecommendation
	{
		// Additional timeframe-specific fields
		@SerializedName("daily_volume")
		private int dailyVolume;

		@SerializedName("factor_scores")
		private FactorScores factorScores;

		@SerializedName("weighted_score")
		private double weightedScore;

		/**
		 * Convert to base FlipRecommendation for UI compatibility.
		 * Since we extend FlipRecommendation, we can simply return this cast appropriately,
		 * or create a copy if needed.
		 */
		public FlipRecommendation toFlipRecommendation()
		{
			FlipRecommendation rec = new FlipRecommendation();
			rec.setItemId(getItemId());
			rec.setItemName(getItemName());
			rec.setMembers(isMembers());
			rec.setBuyLimit(getBuyLimit());
			rec.setInstantBuyPrice(getInstantBuyPrice());
			rec.setInstantSellPrice(getInstantSellPrice());
			rec.setRecommendedBuyPrice(getRecommendedBuyPrice());
			rec.setRecommendedSellPrice(getRecommendedSellPrice());
			rec.setRecommendedQuantity(getRecommendedQuantity());
			rec.setMargin(getMargin());
			rec.setRoiPercent(getRoiPercent());
			rec.setGeTax(getGeTax());
			rec.setVolumePerHour(getVolumePerHour());
			rec.setLiquidityScore(getLiquidityScore());
			rec.setLiquidityRating(getLiquidityRating());
			rec.setRiskScore(getRiskScore());
			rec.setRiskRating(getRiskRating());
			rec.setEfficiencyScore(getEfficiencyScore());
			rec.setEfficiencyRating(getEfficiencyRating());
			rec.setPotentialProfit(getPotentialProfit());
			rec.setTotalCost(getTotalCost());
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
