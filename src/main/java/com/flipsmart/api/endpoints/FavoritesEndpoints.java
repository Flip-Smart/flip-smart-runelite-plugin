package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.FavoritesResponse;
import java.util.concurrent.CompletableFuture;
import okhttp3.Request;
import okhttp3.RequestBody;

import static com.flipsmart.api.ApiHttpTransport.JSON;

/**
 * Favorites endpoints: list the enriched favorites, and star/un-star an item.
 * Toggles are idempotent server-side, so callers may re-issue them safely.
 */
public class FavoritesEndpoints
{
	private final ApiHttpTransport transport;

	public FavoritesEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
	}

	static String favoritesListPath(String apiUrl)
	{
		return apiUrl + "/plugin/favorites";
	}

	static String favoritePath(String apiUrl, int itemId)
	{
		return apiUrl + "/plugin/favorites/" + itemId;
	}

	public CompletableFuture<FavoritesResponse> getFavoritesAsync()
	{
		Request.Builder requestBuilder = new Request.Builder()
			.url(favoritesListPath(transport.getApiUrl()))
			.get();
		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			transport.parse(jsonData, FavoritesResponse.class));
	}

	public CompletableFuture<Boolean> addFavoriteAsync(int itemId)
	{
		Request.Builder requestBuilder = new Request.Builder()
			.url(favoritePath(transport.getApiUrl(), itemId))
			.post(RequestBody.create(JSON, "{}"));
		return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
			.exceptionally(e -> false);
	}

	public CompletableFuture<Boolean> removeFavoriteAsync(int itemId)
	{
		Request.Builder requestBuilder = new Request.Builder()
			.url(favoritePath(transport.getApiUrl(), itemId))
			.delete();
		return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
			.exceptionally(e -> false);
	}
}
