package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class FlipAnalysis
{
	@SerializedName("item_id")
	private int itemId;

	@SerializedName("item_name")
	private String itemName;

	private boolean members;

	@SerializedName("buy_limit")
	private Integer buyLimit;

	@SerializedName("current_prices")
	private CurrentPrices currentPrices;

	private Liquidity liquidity;
	private Risk risk;
	private Efficiency efficiency;

	@SerializedName("historical_data")
	private HistoricalData historicalData;

	@Data
	public static class CurrentPrices
	{
		private Integer high;
		private Integer low;

		@SerializedName("gross_margin")
		private Integer grossMargin;

		@SerializedName("ge_tax")
		private Integer geTax;

		@SerializedName("net_margin")
		private Integer netMargin;

		@SerializedName("roi_percent")
		private Double roiPercent;
	}

	@Data
	public static class Liquidity
	{
		private Double score;
		private String rating;

		@SerializedName("buys_per_hour")
		private Double buysPerHour;

		@SerializedName("sells_per_hour")
		private Double sellsPerHour;

		@SerializedName("total_volume_per_hour")
		private Double totalVolumePerHour;
	}

	@Data
	public static class Risk
	{
		private Double score;
		private String rating;
	}

	@Data
	public static class Efficiency
	{
		private Double score;
		private String rating;
		private String recommendation;
	}

	@Data
	public static class HistoricalData
	{
		private String timeframe;

		@SerializedName("data_points")
		private Integer dataPoints;

		@SerializedName("avg_price")
		private Integer avgPrice;

		private Integer volatility;
	}

	/**
	 * Check if this item is a good flip based on efficiency score
	 */
	public boolean isGoodFlip(int minEfficiencyScore)
	{
		return efficiency != null &&
			efficiency.getScore() != null &&
			efficiency.getScore() >= minEfficiencyScore;
	}

	/**
	 * Check if the item has positive net margin
	 */
	public boolean hasPositiveMargin()
	{
		return currentPrices != null &&
			currentPrices.getNetMargin() != null &&
			currentPrices.getNetMargin() > 0;
	}
}

