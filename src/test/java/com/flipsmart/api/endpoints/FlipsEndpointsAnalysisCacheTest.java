package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.domain.flip.FlipAnalysis;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the analysis-cache contract behind getItemAnalysisAsync: fresh entries
 * are served without a network call, concurrent misses share one in-flight
 * request, expiry triggers a refetch, and null (failed) results are never
 * cached.
 */
public class FlipsEndpointsAnalysisCacheTest
{
	private static final int ITEM_ID = 4151;
	private static final String JSON_BODY = "{}";

	private final long[] now = {0L};
	private final List<PendingRequest> requests = new ArrayList<>();
	private ApiHttpTransport transport;
	private FlipsEndpoints endpoints;
	private FlipAnalysis parsed;

	private static class PendingRequest
	{
		final Function<String, FlipAnalysis> handler;
		final CompletableFuture<FlipAnalysis> future = new CompletableFuture<>();

		PendingRequest(Function<String, FlipAnalysis> handler)
		{
			this.handler = handler;
		}

		void completeWithBody(String body)
		{
			future.complete(handler.apply(body));
		}
	}

	@Before
	@SuppressWarnings("unchecked")
	public void setUp()
	{
		transport = mock(ApiHttpTransport.class);
		parsed = new FlipAnalysis();
		when(transport.getApiUrl()).thenReturn("http://localhost:8000");
		when(transport.parse(anyString(), eq(FlipAnalysis.class))).thenReturn(parsed);
		when(transport.executeAuthenticatedAsync(any(), any(Function.class))).thenAnswer(invocation ->
		{
			PendingRequest pending = new PendingRequest(invocation.getArgument(1));
			requests.add(pending);
			return pending.future;
		});
		endpoints = new FlipsEndpoints(transport, () -> now[0]);
	}

	@Test
	public void freshCacheEntryIsServedWithoutNetworkCall()
	{
		endpoints.getItemAnalysisAsync(ITEM_ID);
		requests.get(0).completeWithBody(JSON_BODY);

		FlipAnalysis second = endpoints.getItemAnalysisAsync(ITEM_ID).join();

		assertEquals("cached result must not trigger a second request", 1, requests.size());
		assertSame(parsed, second);
	}

	@Test
	public void concurrentMissesShareOneInFlightRequest()
	{
		CompletableFuture<FlipAnalysis> first = endpoints.getItemAnalysisAsync(ITEM_ID);
		CompletableFuture<FlipAnalysis> second = endpoints.getItemAnalysisAsync(ITEM_ID);

		assertEquals("coalesced callers must share the single in-flight call", 1, requests.size());
		requests.get(0).completeWithBody(JSON_BODY);
		assertSame(parsed, first.join());
		assertSame(parsed, second.join());
	}

	@Test
	public void expiredEntryTriggersRefetch()
	{
		endpoints.getItemAnalysisAsync(ITEM_ID);
		requests.get(0).completeWithBody(JSON_BODY);

		now[0] = FlipsEndpoints.CACHE_DURATION_MS + 1;
		endpoints.getItemAnalysisAsync(ITEM_ID);

		assertEquals(2, requests.size());
	}

	@Test
	public void entryJustInsideTtlStillServedFromCache()
	{
		endpoints.getItemAnalysisAsync(ITEM_ID);
		requests.get(0).completeWithBody(JSON_BODY);

		now[0] = FlipsEndpoints.CACHE_DURATION_MS;
		endpoints.getItemAnalysisAsync(ITEM_ID);

		assertEquals(1, requests.size());
	}

	@Test
	public void nullResultIsNotCachedAndNextCallRetries()
	{
		when(transport.parse(anyString(), eq(FlipAnalysis.class))).thenReturn(null);

		CompletableFuture<FlipAnalysis> first = endpoints.getItemAnalysisAsync(ITEM_ID);
		requests.get(0).completeWithBody(JSON_BODY);
		assertNull(first.join());

		endpoints.getItemAnalysisAsync(ITEM_ID);
		assertEquals("a null result must not poison the cache", 2, requests.size());
	}

	@Test
	public void completedInFlightEntryIsReleased()
	{
		endpoints.getItemAnalysisAsync(ITEM_ID);
		requests.get(0).completeWithBody(JSON_BODY);
		now[0] = FlipsEndpoints.CACHE_DURATION_MS + 1;

		CompletableFuture<FlipAnalysis> refetched = endpoints.getItemAnalysisAsync(ITEM_ID);

		assertEquals("expired entry must start a new request, not reuse the finished one", 2, requests.size());
		assertFalse(refetched.isDone());
		requests.get(1).completeWithBody(JSON_BODY);
		assertTrue(refetched.isDone());
	}

	@Test
	public void differentItemsDoNotShareInFlightRequests()
	{
		endpoints.getItemAnalysisAsync(ITEM_ID);
		endpoints.getItemAnalysisAsync(ITEM_ID + 1);

		assertEquals(2, requests.size());
	}
}
