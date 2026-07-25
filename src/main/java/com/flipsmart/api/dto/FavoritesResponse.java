package com.flipsmart.api.dto;

import java.util.List;
import lombok.Data;

@Data
public class FavoritesResponse
{
	private List<FavoriteItem> items;

	private int count;
}
