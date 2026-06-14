package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class OfferAdviceResponse
{
	private String action;
	private String reason;

	@SerializedName("new_price")
	private Integer newPrice;

	@SerializedName("net_profit_estimate")
	private Integer netProfitEstimate;

	public OfferAction getActionEnum()
	{
		return OfferAction.fromWire(action);
	}
}
