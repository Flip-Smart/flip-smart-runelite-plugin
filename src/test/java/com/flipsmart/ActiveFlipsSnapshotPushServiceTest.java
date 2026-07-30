package com.flipsmart;

import com.flipsmart.domain.flip.ActiveFlip;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ActiveFlipsSnapshotPushServiceTest
{
	private static final String RSN = "zezima";

	private FlipSmartApiClient apiClient;
	private PlayerSession session;
	private ActiveFlipsSnapshotPushService service;

	private static ActiveFlip flip(int itemId, int filledSoFar)
	{
		ActiveFlip f = new ActiveFlip();
		f.setItemId(itemId);
		f.setPhase("sell");
		f.setOrderQuantity(100);
		f.setTotalQuantity(filledSoFar);
		return f;
	}

	@Before
	public void setUp()
	{
		apiClient = mock(FlipSmartApiClient.class);
		session = mock(PlayerSession.class);
		when(session.getRsnSafe()).thenReturn(Optional.of(RSN));
		when(apiClient.pushActiveFlipsSnapshotAsync(any(), any()))
			.thenReturn(CompletableFuture.completedFuture(true));
		service = new ActiveFlipsSnapshotPushService(apiClient, session);
	}

	@Test
	public void pushesTheProjection() throws Exception
	{
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0)));
		verify(apiClient, timeout(3000).times(1)).pushActiveFlipsSnapshotAsync(eq(RSN), any());
	}

	@Test
	public void identicalProjectionIsNotPushedTwice() throws Exception
	{
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0)));
		verify(apiClient, timeout(3000).times(1)).pushActiveFlipsSnapshotAsync(any(), any());
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0)));
		// after() blocks the full window so this actually waits out the second
		// debounced push instead of just sampling a moment right after scheduling.
		verify(apiClient, after(2500).times(1)).pushActiveFlipsSnapshotAsync(any(), any());
	}

	@Test
	public void fillProgressAloneDoesNotTriggerAPush() throws Exception
	{
		// Same set of flips, more filled. Fill ticks fire constantly; if they
		// each pushed, an actively-filling order would spam the API.
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0)));
		verify(apiClient, timeout(3000).times(1)).pushActiveFlipsSnapshotAsync(any(), any());
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 70)));
		verify(apiClient, after(2500).times(1)).pushActiveFlipsSnapshotAsync(any(), any());
	}

	@Test
	public void aNewItemTriggersAPush() throws Exception
	{
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0)));
		verify(apiClient, timeout(3000).times(1)).pushActiveFlipsSnapshotAsync(any(), any());
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0), flip(561, 0)));
		verify(apiClient, timeout(3000).times(2)).pushActiveFlipsSnapshotAsync(any(), any());
	}

	@Test
	public void emptyProjectionIsPushed() throws Exception
	{
		// "No active flips" must reach the server — it is what clears the dashboard.
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0)));
		verify(apiClient, timeout(3000).times(1)).pushActiveFlipsSnapshotAsync(any(), any());
		service.scheduleSnapshotPush(List::of);
		verify(apiClient, timeout(3000).times(2)).pushActiveFlipsSnapshotAsync(any(), any());
	}

	@Test
	public void failedPushDoesNotAdvanceTheDedupBaseline() throws Exception
	{
		// v1 bug: baseline recorded what was ATTEMPTED. A dropped push then
		// suppressed the retry, and AC2 (cancel disappears) silently failed.
		when(apiClient.pushActiveFlipsSnapshotAsync(any(), any()))
			.thenReturn(CompletableFuture.completedFuture(false));
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0)));
		verify(apiClient, timeout(3000).times(1)).pushActiveFlipsSnapshotAsync(any(), any());

		when(apiClient.pushActiveFlipsSnapshotAsync(any(), any()))
			.thenReturn(CompletableFuture.completedFuture(true));
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0)));
		verify(apiClient, timeout(3000).times(2)).pushActiveFlipsSnapshotAsync(any(), any());
	}

	@Test
	public void noRsnMeansNoPush() throws Exception
	{
		when(session.getRsnSafe()).thenReturn(Optional.empty());
		service.scheduleSnapshotPush(() -> List.of(flip(4151, 0)));
		verify(apiClient, after(2500).never()).pushActiveFlipsSnapshotAsync(any(), any());
	}

	@Test
	public void supplierIsEvaluatedAtSendTimeNotScheduleTime() throws Exception
	{
		// Regression: captured_at is stamped when the endpoint is called, which is
		// when the supplier runs here. If the caller captured the list up-front
		// instead of deferring to the supplier, this test would observe the stale
		// value instead of the one set right before firing.
		java.util.concurrent.atomic.AtomicReference<List<ActiveFlip>> latest =
			new java.util.concurrent.atomic.AtomicReference<>(List.of(flip(4151, 0)));

		service.scheduleSnapshotPush(latest::get);
		latest.set(List.of(flip(4151, 0), flip(561, 0)));

		verify(apiClient, timeout(3000).times(1))
			.pushActiveFlipsSnapshotAsync(eq(RSN), eq(latest.get()));
	}

	@Test
	public void nullFromSupplierSkipsThePush() throws Exception
	{
		// A null result signals "emptiness wasn't observed" (e.g. login burst) —
		// the service must not attempt a push at all, not even an empty one.
		service.scheduleSnapshotPush(() -> null);
		verify(apiClient, after(2500).never()).pushActiveFlipsSnapshotAsync(any(), any());
	}
}
