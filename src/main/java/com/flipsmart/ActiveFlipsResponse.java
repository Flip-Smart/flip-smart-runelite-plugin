package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

/**
 * Response from the active flips API endpoint
 */
@Data
public class ActiveFlipsResponse
{
	@SerializedName("active_flips")
	private List<ActiveFlip> activeFlips;

	@SerializedName("total_items")
	private int totalItems;

	@SerializedName("total_invested")
	private int totalInvested;
}

