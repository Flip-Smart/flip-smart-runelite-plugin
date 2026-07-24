package com.flipsmart.ui.panel;

import com.flipsmart.api.dto.FavoriteItem;
import java.util.Comparator;

public enum FavoritesSort
{
	PROFIT("Profit", Comparator.comparingInt(FavoriteItem::getProfit).reversed()),
	VOLUME("Volume", Comparator.comparingInt(FavoriteItem::getVolume).reversed()),
	ALPHABETICAL("A-Z", Comparator.comparing(
		f -> f.getItemName() == null ? "" : f.getItemName(), String.CASE_INSENSITIVE_ORDER));

	private final String label;
	private final Comparator<FavoriteItem> comparator;

	FavoritesSort(String label, Comparator<FavoriteItem> comparator)
	{
		this.label = label;
		this.comparator = comparator;
	}

	public String getLabel()
	{
		return label;
	}

	public Comparator<FavoriteItem> comparator()
	{
		return comparator;
	}
}
