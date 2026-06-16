package com.flipsmart.api.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class OfferAdviceResult extends OfferAdviceResponse
{
	@SerializedName("item_id")
	private int itemId;
}
