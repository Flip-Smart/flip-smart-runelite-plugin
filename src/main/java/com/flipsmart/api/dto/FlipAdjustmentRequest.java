package com.flipsmart.api.dto;

import lombok.Builder;

/**
 * Request parameters for the flip adjustment API.
 */
@Builder
public class FlipAdjustmentRequest
{
	public final int itemId;
	public final boolean isBuyOffer;
	public final int offerPrice;
	public final int averageBuyPrice;
	public final int minutesSinceOffer;
	public final int adjustmentCount;
	public final int quantityFilled;
	public final int totalQuantity;
	public final String timeframe;
	public final String rsn;
}
