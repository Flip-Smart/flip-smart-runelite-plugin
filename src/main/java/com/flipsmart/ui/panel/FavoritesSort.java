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
	private final Comparator<FavoriteItem> itemComparator;

	FavoritesSort(String label, Comparator<FavoriteItem> itemComparator)
	{
		this.label = label;
		this.itemComparator = itemComparator;
	}

	public String getLabel()
	{
		return label;
	}

	public Comparator<FavoriteItem> comparator()
	{
		return itemComparator;
	}
}
