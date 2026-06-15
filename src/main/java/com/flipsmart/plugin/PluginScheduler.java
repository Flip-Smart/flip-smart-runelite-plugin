package com.flipsmart.plugin;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

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

	private final List<javax.swing.Timer> activeOneShotTimers = new CopyOnWriteArrayList<>();

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

		flipFinderRefreshTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
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
			log.debug("Flip Finder auto-refresh stopped");
		}
	}

	/**
	 * Start the auto-recommend refresh timer (2-minute interval).
	 *
	 * @param loggedInCheck supplies whether the player is logged into RuneScape
	 * @param refreshCycle  the cycle body to run when logged in
	 */
	public void startAutoRecommendRefreshTimer(BooleanSupplier loggedInCheck, Runnable refreshCycle)
	{
		stopAutoRecommendRefreshTimer();

		autoRecommendRefreshTimer = new Timer("AutoRecommendRefreshTimer", true);
		autoRecommendRefreshTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				if (loggedInCheck.getAsBoolean())
				{
					refreshCycle.run();
				}
			}
		}, AUTO_RECOMMEND_REFRESH_INTERVAL_MS, AUTO_RECOMMEND_REFRESH_INTERVAL_MS);

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
	 * @param pollBody the poll body to run on each interval
	 */
	public void startActiveOfferAdvisorTimer(Runnable pollBody)
	{
		activeOfferAdvisorTimer = new Timer("ActiveOfferAdvisorTimer", true);
		activeOfferAdvisorTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				pollBody.run();
			}
		}, ACTIVE_OFFER_ADVISOR_INTERVAL_MS, ACTIVE_OFFER_ADVISOR_INTERVAL_MS);
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
