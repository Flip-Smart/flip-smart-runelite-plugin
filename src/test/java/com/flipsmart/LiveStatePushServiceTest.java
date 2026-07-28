package com.flipsmart;

import com.flipsmart.api.dto.LiveStateSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;

public class LiveStatePushServiceTest
{
	private FlipSmartApiClient apiClient;
	private PlayerSession session;
	private LiveStatePushService service;
	private final AtomicInteger pushes = new AtomicInteger();

	private static LiveStateSnapshot snapshot(int itemId)
	{
		return new LiveStateSnapshot(
			Collections.singletonList(new LiveStateSnapshot.SlotState(
				0, itemId, "Item", true, "BUYING", 5, 1, 100)),
			Collections.emptySet(), Collections.emptySet());
	}

	@Before
	public void setUp()
	{
		apiClient = Mockito.mock(FlipSmartApiClient.class);
		session = Mockito.mock(PlayerSession.class);
		Mockito.when(session.getRsnSafe()).thenReturn(Optional.of("dumbridge3"));
		Mockito.when(apiClient.pushLiveStateAsync(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
			.thenAnswer(inv ->
			{
				pushes.incrementAndGet();
				return CompletableFuture.completedFuture(true);
			});
		service = new LiveStatePushService(apiClient, session);
		pushes.set(0);
	}

	@Test
	public void doesNotPushBeforeReady()
	{
		service.pushNow(snapshot(4151));
		assertEquals(0, pushes.get());
	}

	@Test
	public void pushesOnceReady()
	{
		service.markReady();
		service.pushNow(snapshot(4151));
		assertEquals(1, pushes.get());
	}

	@Test
	public void suppressesIdenticalConsecutiveSnapshot()
	{
		service.markReady();
		service.pushNow(snapshot(4151));
		service.pushNow(snapshot(4151));
		assertEquals(1, pushes.get());
	}

	@Test
	public void pushesEmptySnapshotOnceReady()
	{
		service.markReady();
		service.pushNow(new LiveStateSnapshot(
			Collections.emptyList(), Collections.emptySet(), Collections.emptySet()));
		assertEquals(1, pushes.get());
	}

	@Test
	public void markNotReadyBlocksAgain()
	{
		service.markReady();
		service.pushNow(snapshot(4151));
		service.markNotReady();
		service.pushNow(snapshot(555));
		assertEquals(1, pushes.get());
	}

	@Test
	public void failedPushDoesNotSuppressRetry()
	{
		Mockito.doAnswer(inv ->
			{
				pushes.incrementAndGet();
				CompletableFuture<Boolean> failed = new CompletableFuture<>();
				failed.completeExceptionally(new RuntimeException("boom"));
				return failed;
			})
			.when(apiClient)
			.pushLiveStateAsync(Mockito.anyString(), Mockito.anyString(), Mockito.any());
		service.markReady();
		service.pushNow(snapshot(4151));
		service.pushNow(snapshot(4151));
		assertEquals(2, pushes.get());
	}

	@Test
	public void outOfOrderCompletionDoesNotRevertToStaleSnapshot()
	{
		List<CompletableFuture<Boolean>> futures = new ArrayList<>();
		Mockito.doAnswer(inv ->
			{
				pushes.incrementAndGet();
				CompletableFuture<Boolean> future = new CompletableFuture<>();
				futures.add(future);
				return future;
			})
			.when(apiClient)
			.pushLiveStateAsync(Mockito.anyString(), Mockito.anyString(), Mockito.any());
		service.markReady();

		service.pushNow(snapshot(4151));
		service.pushNow(snapshot(555));

		// Newer push (555) resolves first, older push (4151) resolves after it —
		// the older completion must not overwrite the dedup baseline it lost the race to.
		futures.get(1).complete(true);
		futures.get(0).complete(true);

		service.pushNow(snapshot(4151));
		assertEquals(3, pushes.get());
	}
}
