package com.flipsmart;

import com.flipsmart.api.dto.FavoriteItem;
import com.flipsmart.api.dto.FavoritesResponse;
import com.flipsmart.api.dto.PluginSyncResponse;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FavoritesDtoParsingTest
{
	private final Gson gson = new Gson();

	@Test
	public void parsesFavoritesResponse()
	{
		String json = "{\"items\":[{\"item_id\":4151,\"item_name\":\"Abyssal whip\",\"icon_url\":\"http://x/w.png\","
			+ "\"buy_price\":1900000,\"sell_price\":2000000,\"margin\":80000,\"profit\":5600000,"
			+ "\"volume\":5000000,\"risk_score\":20,\"risk_rating\":\"Very Low\"}],\"count\":1}";
		FavoritesResponse r = gson.fromJson(json, FavoritesResponse.class);
		assertEquals(1, r.getCount());
		FavoriteItem item = r.getItems().get(0);
		assertEquals(4151, item.getItemId());
		assertEquals("Abyssal whip", item.getItemName());
		assertEquals(80000, item.getMargin());
		assertEquals(5600000, item.getProfit());
		assertEquals(5000000, item.getVolume());
		assertEquals("Very Low", item.getRiskRating());
		assertEquals(2000000, item.getSellPrice().intValue());
		assertEquals(1900000, item.getBuyPrice().intValue());
	}

	@Test
	public void parsesFavoriteItemIdsFromSync()
	{
		String json = "{\"version\":1,\"favorite_item_ids\":[4151,561],\"flip_finder\":null}";
		PluginSyncResponse sync = gson.fromJson(json, PluginSyncResponse.class);
		assertNotNull(sync.getFavoriteItemIds());
		assertEquals(2, sync.getFavoriteItemIds().size());
		assertTrue(sync.getFavoriteItemIds().contains(4151));
		assertTrue(sync.getFavoriteItemIds().contains(561));
	}
}
