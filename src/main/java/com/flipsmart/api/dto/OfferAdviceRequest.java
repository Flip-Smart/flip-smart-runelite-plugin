package com.flipsmart.api.dto;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OfferAdviceRequest
{
	private final int itemId;
	private final String pool;
	private final String side;
	private final String stage;
	private final Long listedAtMillis;
	private final int listedPrice;
	private final int listedQuantity;
	private final int filledQuantity;
	private final Long lastFillAtMillis;
	private final Integer currentMarketHigh;
	private final Integer currentMarketLow;
	private final Integer userAvgBuyPrice;

	// Courier state (#918): the backend advisor is stateless, so cross-poll
	// state travels on the request and is echoed back from the previous response.
	private final Integer originalMargin;
	private final Integer previousPositionMargin;
	private final int consecutiveMarginDecreases;
	private final double cumulativeMarginReductionPct;

	public static String toIsoUtc(long epochMillis)
	{
		if (epochMillis <= 0)
		{
			return null;
		}
		return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
	}
}
