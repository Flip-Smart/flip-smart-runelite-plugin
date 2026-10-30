package com.flipsmart.ui.panel;

import com.flipsmart.domain.flip.FlipRecommendation;
import java.util.Comparator;
import lombok.Getter;

public enum RecommendationSort
{
	RANK("Rank", (a, b) -> 0),
	PROFIT("Profit", Comparator.comparingLong(FlipRecommendation::getPotentialProfit).reversed()),
	VOLUME("Volume", Comparator.comparingInt(FlipRecommendation::getDailyVolume).reversed()),
	ALPHABETICAL("A-Z", Comparator.comparing(
		r -> r.getItemName() == null ? "" : r.getItemName(), String.CASE_INSENSITIVE_ORDER));

	@Getter
	private final String label;
	private final Comparator<FlipRecommendation> itemComparator;

	RecommendationSort(String label, Comparator<FlipRecommendation> itemComparator)
	{
		this.label = label;
		this.itemComparator = itemComparator;
	}

	public Comparator<FlipRecommendation> comparator()
	{
		return itemComparator;
	}
}
