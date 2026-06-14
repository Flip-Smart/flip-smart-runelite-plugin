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

	private transient Integer itemIdHint;

	public Integer getItemIdHint()
	{
		return itemIdHint;
	}

	public void setItemIdHint(Integer itemIdHint)
	{
		this.itemIdHint = itemIdHint;
	}

	public OfferAction getActionEnum()
	{
		return OfferAction.fromWire(action);
	}
}
