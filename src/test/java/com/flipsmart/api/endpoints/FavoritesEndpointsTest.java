package com.flipsmart.api.endpoints;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FavoritesEndpointsTest
{
	@Test
	public void appendFavoritesOnlyAddsParamOnlyWhenTrue()
	{
		StringBuilder on = new StringBuilder("http://x/flip-finder?limit=10");
		FlipsEndpoints.appendFavoritesOnly(on, true);
		assertTrue(on.toString().endsWith("&favorites_only=true"));

		StringBuilder off = new StringBuilder("http://x/flip-finder?limit=10");
		FlipsEndpoints.appendFavoritesOnly(off, false);
		assertFalse(off.toString().contains("favorites_only"));
	}

	@Test
	public void favoriteTogglePathsAreCorrect()
	{
		assertEquals("http://api/plugin/favorites/4151",
			FavoritesEndpoints.favoritePath("http://api", 4151));
		assertEquals("http://api/plugin/favorites",
			FavoritesEndpoints.favoritesListPath("http://api"));
	}
}
