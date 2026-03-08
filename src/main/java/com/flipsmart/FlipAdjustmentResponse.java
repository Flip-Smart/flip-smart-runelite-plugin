package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Response from the /flips/adjustment API endpoint.
 * Contains a recommendation for adjusting a stale flip offer.
 */
@Data
public class FlipAdjustmentResponse
{
	private String action;

	@SerializedName("recommended_price")
	private Integer recommendedPrice;

	@SerializedName("current_margin")
	private Integer currentMargin;

	@SerializedName("is_profitable")
	private boolean isProfitable;

	@SerializedName("breakeven_price")
	private int breakevenPrice;

	@SerializedName("minutes_elapsed")
	private int minutesElapsed;

	@SerializedName("threshold_minutes")
	private int thresholdMinutes;

	@SerializedName("adjustment_count")
	private int adjustmentCount;

	private String message;

	@SerializedName("daily_volume")
	private Integer dailyVolume;

	@SerializedName("next_check_minutes")
	private Integer nextCheckMinutes;

	/**
	 * Whether this response recommends taking an action (not hold).
	 */
	public boolean isActionRequired()
	{
		return !"hold".equals(action);
	}

	/**
	 * Whether this response recommends adjusting a buy price.
	 */
	public boolean isReadjustBuy()
	{
		return "readjust_buy".equals(action);
	}

	/**
	 * Whether this response recommends adjusting a sell price.
	 */
	public boolean isReadjustSell()
	{
		return "readjust_sell".equals(action);
	}

	/**
	 * Whether this response recommends cancelling and selling.
	 */
	public boolean isCancelAndSell()
	{
		return "cancel_and_sell".equals(action);
	}
}
