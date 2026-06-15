package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.MotdResponse;
import com.google.gson.Gson;
import okhttp3.Request;

import java.util.concurrent.CompletableFuture;

/**
 * Message-of-the-day endpoint (public, unauthenticated).
 */
public class MotdEndpoints
{
	private final ApiHttpTransport transport;
	private final Gson gson;

	public MotdEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
		this.gson = transport.getGson();
	}

	/**
	 * Fetch the current Message of the Day for both web and plugin channels.
	 * Public endpoint — no authentication required.
	 *
	 * @return CompletableFuture resolving to the parsed response, or null on error.
	 */
	public CompletableFuture<MotdResponse> getMotdAsync()
	{
		Request request = new Request.Builder()
			.url(transport.getApiUrl() + "/motd")
			.get()
			.build();

		return transport.executeAsync(
			request,
			body -> gson.fromJson(body, MotdResponse.class),
			null,
			false // public endpoint — do not retry on 401
		);
	}
}
