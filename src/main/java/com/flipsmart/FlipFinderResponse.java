package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

@Data
public class FlipFinderResponse
{
	@SerializedName("flip_style")
	private String flipStyle;

	@SerializedName("cash_stack")
	private Integer cashStack;

	@SerializedName("per_slot_budget")
	private Double perSlotBudget;

	@SerializedName("total_items_analyzed")
	private int totalItemsAnalyzed;

	@SerializedName("items_matching_criteria")
	private int itemsMatchingCriteria;

	private List<FlipRecommendation> recommendations;
}

