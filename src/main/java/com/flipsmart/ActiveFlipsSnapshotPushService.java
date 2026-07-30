package com.flipsmart;

import com.flipsmart.domain.flip.ActiveFlip;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
	private final ScheduledExecutorService scheduler; // NOPMD DoNotUseThreads - desktop plugin, not J2EE
	private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
	private final AtomicReference<String> lastDelivered = new AtomicReference<>();

	@Inject
	public ActiveFlipsSnapshotPushService(FlipSmartApiClient apiClient, PlayerSession session)
	{
		this.apiClient = apiClient;
		this.session = session;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "flipsmart-active-flips-push"); // NOPMD DoNotUseThreads
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Identity of a projection for dedup purposes: which flips exist and on which
	 * side, deliberately EXCLUDING fill progress. Fill ticks fire constantly; if
	 * they counted as changes, one slowly-filling order would push dozens of times.
	 * This is also exactly what the server consumes, so the payload's other fields
	 * are only ever as fresh as the last identity change.
	 */
	private static String identity(List<ActiveFlip> flips)
	{
		return flips.stream()
			.map(f -> f.getItemId() + ":" + f.getPhase())
			.sorted()
			.collect(Collectors.joining(","));
	}

	/**
	 * Schedule a debounced push. {@code flipsSupplier} is invoked at SEND time (after
	 * the debounce), not now — so the payload and the {@code captured_at} timestamp
	 * the endpoint stamps around it describe the same instant, instead of the payload
	 * being up to {@link #DEBOUNCE_MS} stale relative to its own timestamp.
	 */
	public void scheduleSnapshotPush(Supplier<List<ActiveFlip>> flipsSupplier)
	{
		try
		{
			ScheduledFuture<?> previous = pending.getAndSet(
				scheduler.schedule(() -> doPush(flipsSupplier), DEBOUNCE_MS, TimeUnit.MILLISECONDS));
			if (previous != null)
			{
				previous.cancel(false);
			}
		}
		catch (RejectedExecutionException e)
		{
			if (log.isDebugEnabled())
			{
				log.debug("active flips snapshot schedule rejected (shutting down): {}", e.getMessage());
			}
		}
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
		scheduler.shutdownNow(); // NOPMD DoNotUseThreads
	}

	/**
	 * @param flipsSupplier evaluated here, at send time. May return {@code null} to
	 *                      mean "skip this push" (e.g. an unobserved empty snapshot) —
	 *                      distinct from an empty list, which is a real "no flips" and
	 *                      is always delivered.
	 */
	private void doPush(Supplier<List<ActiveFlip>> flipsSupplier)
	{
		Optional<String> rsn = session.getRsnSafe();
		if (!rsn.isPresent())
		{
			return;
		}

		List<ActiveFlip> flips = flipsSupplier.get();
		if (flips == null)
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
					if (log.isDebugEnabled())
					{
						log.debug("active flips snapshot push failed: {}", e.getMessage());
					}
					return null;
				});
		}
		catch (RuntimeException e)
		{
			if (log.isDebugEnabled())
			{
				log.debug("active flips snapshot push threw: {}", e.getMessage());
			}
		}
	}
}
