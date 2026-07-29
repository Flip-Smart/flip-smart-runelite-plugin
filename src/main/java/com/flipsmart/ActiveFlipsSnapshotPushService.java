package com.flipsmart;

import com.flipsmart.domain.flip.ActiveFlip;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Pushes the plugin's canonical Active Flips projection to the backend so the
 * website mirrors the same list (Flip-Smart/flip-smart#1113).
 *
 * The projection itself is never recomputed here — this only serialises what the
 * Active Flips tab already renders, which is what makes the two surfaces agree
 * by construction.
 */
@Slf4j
@Singleton
public class ActiveFlipsSnapshotPushService
{
	private static final long DEBOUNCE_MS = 2_000L;

	private final FlipSmartApiClient apiClient;
	private final PlayerSession session;
	private final ScheduledExecutorService scheduler;
	private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
	private final AtomicReference<String> lastDelivered = new AtomicReference<>();

	@Inject
	public ActiveFlipsSnapshotPushService(FlipSmartApiClient apiClient, PlayerSession session)
	{
		this.apiClient = apiClient;
		this.session = session;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "flipsmart-active-flips-push");
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Identity of a projection for dedup purposes: which flips exist and on which
	 * side, deliberately EXCLUDING fill progress. Fill ticks fire constantly; if
	 * they counted as changes, one slowly-filling order would push dozens of times.
	 */
	private static String identity(List<ActiveFlip> flips)
	{
		return flips.stream()
			.map(f -> f.getItemId() + ":" + f.getPhase() + ":" + f.getOrderQuantity())
			.sorted()
			.collect(Collectors.joining(","));
	}

	public void scheduleSnapshotPush(List<ActiveFlip> flips)
	{
		List<ActiveFlip> snapshot = new ArrayList<>(flips);
		ScheduledFuture<?> previous = pending.getAndSet(
			scheduler.schedule(() -> doPush(snapshot), DEBOUNCE_MS, TimeUnit.MILLISECONDS));
		if (previous != null)
		{
			previous.cancel(false);
		}
	}

	public void pushNow(List<ActiveFlip> flips)
	{
		List<ActiveFlip> snapshot = new ArrayList<>(flips);
		ScheduledFuture<?> previous = pending.getAndSet(null);
		if (previous != null)
		{
			previous.cancel(false);
		}
		scheduler.execute(() -> doPush(snapshot));
	}

	/** Force the next push through even if the projection is unchanged (TTL refresh). */
	public void invalidateDedup()
	{
		lastDelivered.set(null);
	}

	public void shutdown()
	{
		ScheduledFuture<?> previous = pending.getAndSet(null);
		if (previous != null)
		{
			previous.cancel(false);
		}
		scheduler.shutdownNow();
	}

	private void doPush(List<ActiveFlip> flips)
	{
		Optional<String> rsn = session.getRsnSafe();
		if (!rsn.isPresent())
		{
			return;
		}

		String identity = identity(flips);
		if (identity.equals(lastDelivered.get()))
		{
			return;
		}

		try
		{
			apiClient.pushActiveFlipsSnapshotAsync(rsn.get(), flips)
				.thenAccept(ok ->
				{
					// Advance the baseline only on DELIVERY. Recording an attempt
					// would let a dropped push suppress its own retry, silently
					// breaking AC2 (a cancelled offer never leaving the dashboard).
					if (Boolean.TRUE.equals(ok))
					{
						lastDelivered.set(identity);
					}
				})
				.exceptionally(e ->
				{
					log.debug("active flips snapshot push failed: {}", e.getMessage());
					return null;
				});
		}
		catch (RuntimeException e)
		{
			log.debug("active flips snapshot push threw: {}", e.getMessage());
		}
	}
}
