package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.domain.flip.ActiveFlip;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;
import static com.flipsmart.api.ApiHttpTransport.JSON;

@Slf4j
public class ActiveFlipsSnapshotEndpoints
{
	private final ApiHttpTransport transport;
	private final Gson gson;

	public ActiveFlipsSnapshotEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
		this.gson = transport.getGson();
	}

	public CompletableFuture<Boolean> pushActiveFlipsSnapshotAsync(String rsn, List<ActiveFlip> flips)
	{
		String url = String.format("%s/plugin/active-flips-snapshot", transport.getApiUrl());

		JsonObject body = new JsonObject();
		body.addProperty("rsn", rsn);
		body.addProperty("captured_at", Instant.now().toString());
		// ActiveFlip carries @SerializedName on every field, so it serialises
		// straight into the snake_case shape the backend expects.
		body.add("flips", gson.toJsonTree(flips));

		RequestBody rb = RequestBody.create(JSON, body.toString());
		Request.Builder requestBuilder = new Request.Builder().url(url).post(rb);

		return transport.executeAuthenticatedAsync(requestBuilder, jsonData -> Boolean.TRUE)
			.exceptionally(e ->
			{
				if (log.isDebugEnabled())
				{
					log.debug("pushActiveFlipsSnapshotAsync failed: {}", e.getMessage());
				}
				return false;
			});
	}
}
