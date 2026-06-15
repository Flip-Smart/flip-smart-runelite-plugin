package com.flipsmart;

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

	public static String toIsoUtc(long epochMillis)
	{
		if (epochMillis <= 0)
		{
			return null;
		}
		return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
	}
}
