package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Response from the /flips/statistics API endpoint.
 * Provides aggregate flip performance stats over a time period.
 */
@Data
public class FlipStatisticsResponse
{
	@SerializedName("total_flips")
	private int totalFlips;

	@SerializedName("successful_flips")
	private int successfulFlips;

	@SerializedName("total_profit")
	private int totalProfit;

	@SerializedName("success_rate")
	private double successRate;

	@SerializedName("average_roi")
	private double averageRoi;
}
