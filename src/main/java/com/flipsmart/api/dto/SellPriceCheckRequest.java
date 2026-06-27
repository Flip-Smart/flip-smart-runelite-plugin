package com.flipsmart.api.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SellPriceCheckRequest
{
	private final int itemId;
	private final int originalSellPrice;
	private final int currentMarketHigh;
	private final int dailyVolume;
	private final String timeframe;
	private final String style;
	private final String rsn;
}
