package com.flipsmart.api.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class SellPriceCheckResponse
{
	@SerializedName("recommended_sell_price")
	private int recommendedSellPrice;

	private boolean adjusted;

	private String reason;
}
