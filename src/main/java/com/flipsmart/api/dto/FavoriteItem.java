package com.flipsmart.api.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class FavoriteItem
{
	@SerializedName("item_id")
	private int itemId;

	@SerializedName("item_name")
	private String itemName;

	@SerializedName("icon_url")
	private String iconUrl;

	@SerializedName("buy_price")
	private Integer buyPrice;

	@SerializedName("sell_price")
	private Integer sellPrice;

	private int margin;

	private int profit;

	private int volume;

	@SerializedName("risk_score")
	private int riskScore;

	@SerializedName("risk_rating")
	private String riskRating;
}
