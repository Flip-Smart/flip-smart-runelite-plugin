package com.flipsmart;

import com.flipsmart.api.dto.LiveStateSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;

public class LiveStatePushServiceTest
{
	private static final long HEARTBEAT_STALE_AFTER_MS = 600_000L;

	private FlipSmartApiClient apiClient;
	private PlayerSession session;
	private LiveStatePushService service;
	private final AtomicInteger pushes = new AtomicInteger();
	private final AtomicLong clockMillis = new AtomicLong(0L);

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
		service = new LiveStatePushService(apiClient, session, clockMillis::get);
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

	@Test
	public void heartbeatPushesIdenticalContentWhenLastPushIsStale()
	{
		service.markReady();
		service.pushNow(snapshot(4151));
		assertEquals(1, pushes.get());

		clockMillis.set(HEARTBEAT_STALE_AFTER_MS + 1);
		service.heartbeatTick(() -> snapshot(4151));

		assertEquals(2, pushes.get());
	}

	@Test
	public void heartbeatDoesNotPushWhenLastPushIsRecent()
	{
		service.markReady();
		service.pushNow(snapshot(4151));
		assertEquals(1, pushes.get());

		clockMillis.set(HEARTBEAT_STALE_AFTER_MS - 1);
		service.heartbeatTick(() -> snapshot(4151));

		assertEquals(1, pushes.get());
	}

	@Test
	public void heartbeatDoesNotPushWhenNotReady()
	{
		clockMillis.set(HEARTBEAT_STALE_AFTER_MS + 1);
		service.heartbeatTick(() -> snapshot(4151));

		assertEquals(0, pushes.get());
	}

	@Test
	public void heartbeatPushesEmptySnapshot()
	{
		service.markReady();

		clockMillis.set(HEARTBEAT_STALE_AFTER_MS + 1);
		service.heartbeatTick(() -> new LiveStateSnapshot(
			Collections.emptyList(), Collections.emptySet(), Collections.emptySet()));

		assertEquals(1, pushes.get());
	}

	@Test
	public void failedPushDoesNotRefreshLastSuccessfulPushTime()
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
		assertEquals(1, pushes.get());

		// The prior push never succeeded, so the heartbeat should still see the
		// last-successful-push time as unset and fire immediately, well before
		// the staleness threshold would otherwise have elapsed.
		clockMillis.set(1L);
		service.heartbeatTick(() -> snapshot(4151));

		assertEquals(2, pushes.get());
	}
}
