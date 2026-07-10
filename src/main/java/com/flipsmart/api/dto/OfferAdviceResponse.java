package com.flipsmart.api.dto;
import com.flipsmart.domain.offer.OfferAction;

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

	// Courier state (#918) echoed back to the plugin for the next poll.
	@SerializedName("position_margin")
	private Integer positionMargin;

	@SerializedName("consecutive_margin_decreases")
	private int consecutiveMarginDecreases;

	@SerializedName("cumulative_margin_reduction_pct")
	private double cumulativeMarginReductionPct;

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
