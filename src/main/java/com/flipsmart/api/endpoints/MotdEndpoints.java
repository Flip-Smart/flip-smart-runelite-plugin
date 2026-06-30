package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.MotdResponse;
import okhttp3.Request;

import java.util.concurrent.CompletableFuture;

/**
 * Message-of-the-day endpoint (public, unauthenticated).
 */
public class MotdEndpoints
{
	private final ApiHttpTransport transport;

	public MotdEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
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
			body -> transport.parse(body, MotdResponse.class),
			null,
			false // public endpoint — do not retry on 401
		);
	}
}
