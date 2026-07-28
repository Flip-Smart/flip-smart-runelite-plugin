package com.flipsmart;

import com.flipsmart.api.dto.LiveStateSnapshot;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Pushes the player's live GE slot, inventory and collected state to the backend
 * so the web dashboard can tell an open position from a finished one.
 *
 * Debounced because a single fill produces a burst of offer events. The payload
 * is absolute, so a dropped push costs freshness, never correctness.
 */
@Slf4j
@Singleton
public class LiveStatePushService
{
	private static final long DEBOUNCE_MS = 1_500L;

	private final FlipSmartApiClient apiClient;
	private final PlayerSession session;
	private final ScheduledExecutorService scheduler; // NOPMD DoNotUseThreads - desktop plugin, not J2EE

	private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
	private final AtomicReference<LiveStateSnapshot> lastPushed = new AtomicReference<>();
	private final AtomicReference<LiveStateSnapshot> lastSent = new AtomicReference<>();
	private volatile boolean ready;

	@Inject
	public LiveStatePushService(FlipSmartApiClient apiClient, PlayerSession session)
	{
		this.apiClient = apiClient;
		this.session = session;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "flipsmart-live-state-push"); // NOPMD DoNotUseThreads
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Allow pushes. Called once the GE login burst has settled — before that the
	 * client's slot state is not yet trustworthy and an early snapshot would
	 * briefly hide real positions.
	 */
	public void markReady()
	{
		ready = true;
	}

	/** Block pushes and forget the dedup baseline, so the next session re-pushes in full. */
	public void markNotReady()
	{
		ready = false;
		lastPushed.set(null);
	}

	public void schedulePush(LiveStateSnapshot snapshot)
	{
		ScheduledFuture<?> next = scheduler.schedule(() -> doPush(snapshot), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
		ScheduledFuture<?> previous = pending.getAndSet(next);
		if (previous != null)
		{
			previous.cancel(false);
		}
	}

	public void pushNow(LiveStateSnapshot snapshot)
	{
		ScheduledFuture<?> existing = pending.getAndSet(null);
		if (existing != null)
		{
			existing.cancel(false);
		}
		doPush(snapshot);
	}

	public void shutdown()
	{
		ScheduledFuture<?> existing = pending.getAndSet(null);
		if (existing != null)
		{
			existing.cancel(false);
		}
		scheduler.shutdownNow(); // NOPMD DoNotUseThreads
	}

	private void doPush(LiveStateSnapshot snapshot)
	{
		if (!ready)
		{
			return;
		}
		String rsn = session.getRsnSafe().orElse(null);
		if (rsn == null)
		{
			return;
		}
		if (snapshot.equals(lastPushed.get()))
		{
			return;
		}
		try
		{
			lastSent.set(snapshot);
			apiClient.pushLiveStateAsync(rsn, Instant.now().toString(), snapshot)
				.whenComplete((ok, err) ->
				{
					boolean succeeded = err == null && Boolean.TRUE.equals(ok);
					if (succeeded && snapshot == lastSent.get())
					{
						lastPushed.set(snapshot);
					}
					else if (!succeeded)
					{
						log.debug("Live-state push failed: {}", err != null ? err.getMessage() : ok);
					}
				});
		}
		catch (RuntimeException e)
		{
			log.debug("Live-state push threw: {}", e.getMessage());
		}
	}
}
