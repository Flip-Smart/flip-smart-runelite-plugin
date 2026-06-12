package com.flipsmart;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@link FlipSmartApiClient#dedupeInFlight} — the in-flight dedup that stops
 * the per-render-frame GE offer loop from opening one daily-volume connection per
 * frame for the same uncached item (issue #727).
 */
public class DailyVolumeDedupTest
{
	// AC1 — repeated calls while a fetch is in flight share a single underlying fetch.
	@Test
	public void sharesSingleFetchWhileInFlight()
	{
		Map<Integer, CompletableFuture<Integer>> inFlight = new ConcurrentHashMap<>();
		AtomicInteger fetches = new AtomicInteger();
		Supplier<CompletableFuture<Integer>> fetcher = () ->
		{
			fetches.incrementAndGet();
			return new CompletableFuture<>(); // never completes during this test
		};

		CompletableFuture<Integer> first = FlipSmartApiClient.dedupeInFlight(inFlight, 1747, fetcher);
		for (int i = 0; i < 10; i++)
		{
			CompletableFuture<Integer> again = FlipSmartApiClient.dedupeInFlight(inFlight, 1747, fetcher);
			assertSame("repeat callers get the same in-flight future", first, again);
		}

		assertEquals("only one underlying fetch is issued", 1, fetches.get());
	}

	// AC2 — once the in-flight fetch completes, the entry clears and a later call refetches.
	@Test
	public void clearsInFlightOnCompletionAndAllowsRefetch()
	{
		Map<Integer, CompletableFuture<Integer>> inFlight = new ConcurrentHashMap<>();
		AtomicInteger fetches = new AtomicInteger();
		CompletableFuture<Integer> backing = new CompletableFuture<>();
		Supplier<CompletableFuture<Integer>> fetcher = () ->
		{
			fetches.incrementAndGet();
			return backing.thenApply(v -> v); // a fresh dependent future per call
		};

		FlipSmartApiClient.dedupeInFlight(inFlight, 1747, fetcher);
		assertTrue("entry registered while in flight", inFlight.containsKey(1747));

		backing.complete(42);

		assertFalse("entry cleared after completion", inFlight.containsKey(1747));

		FlipSmartApiClient.dedupeInFlight(inFlight, 1747, fetcher);
		assertEquals("a post-completion call refetches", 2, fetches.get());
	}

	// AC3 — different item ids fetch independently.
	@Test
	public void differentItemsFetchIndependently()
	{
		Map<Integer, CompletableFuture<Integer>> inFlight = new ConcurrentHashMap<>();
		AtomicInteger fetches = new AtomicInteger();
		Supplier<CompletableFuture<Integer>> fetcher = () ->
		{
			fetches.incrementAndGet();
			return new CompletableFuture<>();
		};

		CompletableFuture<Integer> a = FlipSmartApiClient.dedupeInFlight(inFlight, 1747, fetcher);
		CompletableFuture<Integer> b = FlipSmartApiClient.dedupeInFlight(inFlight, 27641, fetcher);

		assertEquals("each distinct item issues its own fetch", 2, fetches.get());
		org.junit.Assert.assertNotSame("distinct items get distinct futures", a, b);
		assertTrue(inFlight.containsKey(1747));
		assertTrue(inFlight.containsKey(27641));
	}
}
