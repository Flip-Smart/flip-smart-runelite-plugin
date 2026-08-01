package com.flipsmart.plugin;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the FlipSmart plugin's background timers and one-shot Swing timers.
 *
 * The plugin delegates all timer lifecycle to this collaborator: startUp wires
 * and starts the timers, shutDown stops them. Cadence, daemon flags, logged-in
 * guards and the one-shot tracking semantics are preserved exactly as they were
 * when this logic lived on the plugin.
 */
@Slf4j
public class PluginScheduler
{
	/** Auto-recommend queue refresh interval (2 minutes) */
	public static final long AUTO_RECOMMEND_REFRESH_INTERVAL_MS = 2 * 60 * 1000L;

	/** Active-offer advisor poll interval (30 seconds) */
	public static final long ACTIVE_OFFER_ADVISOR_INTERVAL_MS = 30_000L;
	public static final long ACTIVE_OFFER_ADVISOR_EVENT_DEBOUNCE_MS = 3_000L;

	/** Active Flips snapshot heartbeat interval (5 minutes), deliberately not user-configurable */
	public static final long ACTIVE_FLIPS_SNAPSHOT_INTERVAL_MS = 5 * 60 * 1000L;

	/** Delay before syncing offline fills after login */
	public static final int OFFLINE_SYNC_DELAY_MS = 2000;
	/** Delay before refreshing panel after sync */
	public static final int PANEL_REFRESH_DELAY_MS = 1000;
	/** Delay before cleaning up stale flips (allows GE state to stabilize) */
	public static final int STALE_FLIP_CLEANUP_DELAY_MS = 15000;
	/** Delay before validating inventory quantities */
	public static final int INVENTORY_VALIDATION_DELAY_MS = 2000;
	/** Delay before re-evaluating auto-recommend after login sync */
	public static final int AUTO_RECOMMEND_REEVALUATE_DELAY_MS = 3000;

	private Timer flipFinderRefreshTimer;
	private Timer autoRecommendRefreshTimer;
	private Timer activeOfferAdvisorTimer;
	private Timer activeFlipsSnapshotTimer;

	/**
	 * Wall-clock instant at which the flip-finder auto-refresh timer will next fire.
	 * This is the single source of truth for the "Item refresh in Ns" countdown the
	 * panel renders — the visual label reads this value rather than tracking its own
	 * independent deadline, so a manual refresh (which restarts the timer) always
	 * moves the countdown and the actual refresh together. 0 when the timer is stopped.
	 */
	@Getter
	private volatile long nextFlipFinderRefreshAtMillis;

	private final LongSupplier clock;

	private final List<javax.swing.Timer> activeOneShotTimers = new CopyOnWriteArrayList<>();

	public PluginScheduler()
	{
		this(System::currentTimeMillis);
	}

	/** Test seam: inject a deterministic clock for the refresh-cadence bookkeeping. */
	PluginScheduler(LongSupplier clock)
	{
		this.clock = clock;
	}

	/**
	 * Start the auto-refresh timer for flip finder.
	 *
	 * @param refreshMinutesRaw the configured refresh interval in minutes (clamped 1..60)
	 * @param loggedInCheck     supplies whether the player is logged into RuneScape
	 * @param refreshBody       the per-tick body to run when logged in (matches the
	 *                          original timer task body byte-for-byte)
	 */
	public void startFlipFinderRefreshTimer(int refreshMinutesRaw, BooleanSupplier loggedInCheck, Runnable refreshBody)
	{
		if (flipFinderRefreshTimer != null)
		{
			flipFinderRefreshTimer.cancel();
		}

		flipFinderRefreshTimer = new Timer("FlipFinderRefreshTimer", true);

		int refreshMinutes = Math.max(1, Math.min(60, refreshMinutesRaw));
		long refreshIntervalMs = refreshMinutes * 60 * 1000L;

		// Start (or restart, e.g. on a manual refresh) resets the countdown deadline
		// to a full interval from now, keeping the visual countdown and the actual
		// refresh trigger synchronized.
		nextFlipFinderRefreshAtMillis = clock.getAsLong() + refreshIntervalMs;

		flipFinderRefreshTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				// The fixed-rate timer fires regardless of login state; advance the
				// countdown deadline on every tick so the label tracks the real next fire.
				nextFlipFinderRefreshAtMillis = clock.getAsLong() + refreshIntervalMs;
				if (!loggedInCheck.getAsBoolean())
				{
					log.debug("Skipping auto-refresh - player not logged into RuneScape");
					return;
				}
				refreshBody.run();
			}
		}, refreshIntervalMs, refreshIntervalMs);

		log.debug("Flip Finder auto-refresh started (every {} minutes)", refreshMinutes);
	}

	public void stopFlipFinderRefreshTimer()
	{
		if (flipFinderRefreshTimer != null)
		{
			flipFinderRefreshTimer.cancel();
			flipFinderRefreshTimer = null;
			nextFlipFinderRefreshAtMillis = 0;
			log.debug("Flip Finder auto-refresh stopped");
		}
	}

	/**
	 * Start the auto-recommend refresh timer (2-minute interval).
	 *
	 * @param loggedInCheck supplies whether the player is logged into RuneScape
	 * @param refreshCycle  the cycle body to run when logged in
	 */
	/**
	 * A daemon timer running {@code body} every {@code intervalMs}, but only while the
	 * player is logged in. All three plugin timers share this shape; they differ only in
	 * how they dispose of the previous timer, which stays with each caller.
	 */
	private static Timer startGatedTimer(String name, long intervalMs,
		BooleanSupplier loggedInCheck, Runnable body)
	{
		Timer timer = new Timer(name, true);
		timer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				if (loggedInCheck.getAsBoolean())
				{
					body.run();
				}
			}
		}, intervalMs, intervalMs);
		return timer;
	}

	public void startAutoRecommendRefreshTimer(BooleanSupplier loggedInCheck, Runnable refreshCycle)
	{
		stopAutoRecommendRefreshTimer();

		autoRecommendRefreshTimer = startGatedTimer("AutoRecommendRefreshTimer",
			AUTO_RECOMMEND_REFRESH_INTERVAL_MS, loggedInCheck, refreshCycle);

		log.debug("Auto-recommend refresh timer started (every 2 minutes)");
	}

	public void stopAutoRecommendRefreshTimer()
	{
		if (autoRecommendRefreshTimer != null)
		{
			autoRecommendRefreshTimer.cancel();
			autoRecommendRefreshTimer = null;
		}
	}

	/** True when the auto-recommend refresh timer is running. */
	public boolean isAutoRecommendRefreshTimerRunning()
	{
		return autoRecommendRefreshTimer != null;
	}

	/**
	 * Start the active-offer advisor poll timer (30-second interval).
	 *
	 * @param loggedInCheck supplies whether the player is logged into RuneScape
	 * @param pollBody      the poll body to run on each interval when logged in
	 */
	public void startActiveOfferAdvisorTimer(BooleanSupplier loggedInCheck, Runnable pollBody)
	{
		activeOfferAdvisorTimer = startGatedTimer("ActiveOfferAdvisorTimer",
			ACTIVE_OFFER_ADVISOR_INTERVAL_MS, loggedInCheck, pollBody);
	}

	public void stopActiveOfferAdvisorTimer()
	{
		if (activeOfferAdvisorTimer != null)
		{
			activeOfferAdvisorTimer.cancel();
			activeOfferAdvisorTimer = null;
		}
	}

	/**
	 * Start the Active Flips snapshot heartbeat (5-minute interval). Deliberately its
	 * own timer rather than riding the user-configurable flip-finder refresh, which can
	 * be set up to 60 minutes and would let a player leave the web dashboard that stale.
	 *
	 * @param loggedInCheck supplies whether the player is logged into RuneScape
	 * @param body          the heartbeat body to run on each interval when logged in
	 */
	public void startActiveFlipsSnapshotTimer(BooleanSupplier loggedInCheck, Runnable body)
	{
		if (activeFlipsSnapshotTimer != null)
		{
			activeFlipsSnapshotTimer.cancel();
		}

		activeFlipsSnapshotTimer = startGatedTimer("ActiveFlipsSnapshotTimer",
			ACTIVE_FLIPS_SNAPSHOT_INTERVAL_MS, loggedInCheck, body);
	}

	public void stopActiveFlipsSnapshotTimer()
	{
		if (activeFlipsSnapshotTimer != null)
		{
			activeFlipsSnapshotTimer.cancel();
			activeFlipsSnapshotTimer = null;
		}
	}

	/**
	 * Create and start a tracked one-shot Swing timer. The timer is automatically
	 * removed from tracking after it fires. All tracked timers are stopped on shutdown.
	 */
	public void scheduleOneShot(int delayMs, Runnable action)
	{
		javax.swing.Timer timer = new javax.swing.Timer(delayMs, null);
		timer.addActionListener(e ->
		{
			try
			{
				action.run();
			}
			finally
			{
				activeOneShotTimers.remove(timer);
			}
		});
		timer.setRepeats(false);
		activeOneShotTimers.add(timer);
		timer.start();
	}

	/** Stop all active one-shot Swing timers. */
	public void stopAllOneShotTimers()
	{
		for (javax.swing.Timer timer : activeOneShotTimers)
		{
			timer.stop();
		}
		activeOneShotTimers.clear();
	}
}
