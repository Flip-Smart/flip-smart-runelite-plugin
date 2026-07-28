package com.flipsmart;

import com.flipsmart.api.dto.LiveStateSnapshot;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Pushes the player's live GE slot, inventory and collected state to the backend
 * so the web dashboard can tell an open position from a finished one.
 *
 * Debounced because a single fill produces a burst of offer events. The payload
 * is absolute, so a dropped push costs freshness, never correctness. Also heartbeats
 * on a bounded clock — see {@link #startHeartbeat}.
 */
@Slf4j
@Singleton
public class LiveStatePushService
{
	private static final long DEBOUNCE_MS = 1_500L;
	private static final long HEARTBEAT_INTERVAL_MS = 60_000L;
	private static final long HEARTBEAT_STALE_AFTER_MS = 600_000L;
	private static final long NEVER_PUSHED = -1L;

	private final FlipSmartApiClient apiClient;
	private final PlayerSession session;
	private final LongSupplier clock;
	private final ScheduledExecutorService scheduler; // NOPMD DoNotUseThreads - desktop plugin, not J2EE

	private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
	private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
	private final AtomicReference<LiveStateSnapshot> lastPushed = new AtomicReference<>();
	private final AtomicReference<LiveStateSnapshot> lastSent = new AtomicReference<>();
	private final AtomicLong lastSuccessfulPushAt = new AtomicLong(NEVER_PUSHED);
	private volatile boolean ready;

	@Inject
	public LiveStatePushService(FlipSmartApiClient apiClient, PlayerSession session)
	{
		this(apiClient, session, System::currentTimeMillis);
	}

	LiveStatePushService(FlipSmartApiClient apiClient, PlayerSession session, LongSupplier clock)
	{
		this.apiClient = apiClient;
		this.session = session;
		this.clock = clock;
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
		ScheduledFuture<?> next = scheduler.schedule(() -> doPush(snapshot, false), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
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
		doPush(snapshot, false);
	}

	/**
	 * Start the freshness heartbeat. No offer or inventory event fires while the
	 * player's GE state is genuinely unchanged, so nothing else advances the
	 * backend's "as of" timestamp for that (common) steady state, and nothing
	 * else gives a failed push a bounded retry. The supplier is called off the
	 * client thread on every tick; it must be safe for that (it is — see
	 * {@code FlipSmartPlugin#buildLiveStateSnapshot}).
	 */
	public void startHeartbeat(Supplier<LiveStateSnapshot> snapshotSupplier)
	{
		ScheduledFuture<?> next = scheduler.scheduleAtFixedRate(() ->
		{
			try
			{
				heartbeatTick(snapshotSupplier);
			}
			catch (RuntimeException e)
			{
				log.debug("Live-state heartbeat threw: {}", e.getMessage());
			}
		}, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
		ScheduledFuture<?> previous = heartbeat.getAndSet(next);
		if (previous != null)
		{
			previous.cancel(false);
		}
	}

	public void shutdown()
	{
		ScheduledFuture<?> existing = pending.getAndSet(null);
		if (existing != null)
		{
			existing.cancel(false);
		}
		ScheduledFuture<?> existingHeartbeat = heartbeat.getAndSet(null);
		if (existingHeartbeat != null)
		{
			existingHeartbeat.cancel(false);
		}
		scheduler.shutdownNow(); // NOPMD DoNotUseThreads
	}

	/**
	 * Package-private so tests can drive a tick deterministically instead of
	 * waiting on {@link #HEARTBEAT_INTERVAL_MS} of real scheduler time.
	 */
	void heartbeatTick(Supplier<LiveStateSnapshot> snapshotSupplier)
	{
		if (!ready || !session.getRsnSafe().isPresent())
		{
			return;
		}
		long lastSuccess = lastSuccessfulPushAt.get();
		if (lastSuccess != NEVER_PUSHED && clock.getAsLong() - lastSuccess < HEARTBEAT_STALE_AFTER_MS)
		{
			return;
		}
		doPush(snapshotSupplier.get(), true);
	}

	private void doPush(LiveStateSnapshot snapshot, boolean bypassDedup)
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
		if (!bypassDedup && snapshot.equals(lastPushed.get()))
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
						lastSuccessfulPushAt.set(clock.getAsLong());
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
