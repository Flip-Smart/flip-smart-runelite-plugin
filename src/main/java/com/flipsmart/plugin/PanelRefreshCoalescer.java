package com.flipsmart.plugin;

import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Coalesces event-driven panel refresh requests so a burst of GE offer events
 * produces one API-backed refresh per quiet window instead of one per event.
 * A window closes {@link #QUIET_WINDOW_MS} after the last request, or
 * {@link #MAX_WAIT_MS} after the first request during a continuous stream.
 * Requests carry a level: a full refresh covers recommendations, active flips,
 * and completed flips; an active-flips request refreshes only that list. A full
 * request upgrades the pending window and is never downgraded.
 *
 * Manual and timer-driven refreshes call the panel directly and bypass this.
 */
public class PanelRefreshCoalescer
{
	public static final int QUIET_WINDOW_MS = 5_000;
	// A completed flip changes session P&L, which the user watches update live; that refresh
	// wants a much shorter quiet window than a burst of routine offer events does.
	public static final int FAST_QUIET_WINDOW_MS = 750;
	public static final int MAX_WAIT_MS = 10_000;

	private final BiConsumer<Integer, Runnable> oneShotScheduler;
	private final LongSupplier clock;
	private final Runnable fullRefresh;
	private final Runnable activeFlipsRefresh;

	private boolean windowOpen;
	private boolean fullRequested;
	private long firstRequestAt;
	private long lastRequestAt;
	// The quiet window for the pending cycle. The fastest request in a cycle wins, so a
	// completed-flip refresh riding an open offer-event window still fires promptly.
	private int quietWindowMs = QUIET_WINDOW_MS;

	public PanelRefreshCoalescer(BiConsumer<Integer, Runnable> oneShotScheduler, LongSupplier clock,
		Runnable fullRefresh, Runnable activeFlipsRefresh)
	{
		this.oneShotScheduler = oneShotScheduler;
		this.clock = clock;
		this.fullRefresh = fullRefresh;
		this.activeFlipsRefresh = activeFlipsRefresh;
	}

	/** Request a refresh when the current window closes, opening one if needed. */
	public void request(boolean full)
	{
		request(full, QUIET_WINDOW_MS);
	}

	/** As {@link #request} but with the short quiet window, for a refresh the user watches live. */
	public void requestSoon(boolean full)
	{
		request(full, FAST_QUIET_WINDOW_MS);
	}

	private void request(boolean full, int quietMs)
	{
		int scheduleMs;
		synchronized (this)
		{
			long now = clock.getAsLong();
			lastRequestAt = now;
			fullRequested |= full;
			if (windowOpen)
			{
				quietWindowMs = Math.min(quietWindowMs, quietMs);
				return;
			}
			windowOpen = true;
			firstRequestAt = now;
			quietWindowMs = quietMs;
			scheduleMs = quietMs;
		}
		oneShotScheduler.accept(scheduleMs, this::onTimerFire);
	}

	private void onTimerFire()
	{
		Runnable action = null;
		int nextDelayMs = 0;
		synchronized (this)
		{
			long now = clock.getAsLong();
			long sinceLast = now - lastRequestAt;
			long sinceFirst = now - firstRequestAt;
			if (sinceLast >= quietWindowMs || sinceFirst >= MAX_WAIT_MS)
			{
				action = fullRequested ? fullRefresh : activeFlipsRefresh;
				windowOpen = false;
				fullRequested = false;
				quietWindowMs = QUIET_WINDOW_MS;
			}
			else
			{
				nextDelayMs = (int) Math.max(1, Math.min(quietWindowMs - sinceLast, MAX_WAIT_MS - sinceFirst));
			}
		}
		if (action != null)
		{
			action.run();
		}
		else
		{
			oneShotScheduler.accept(nextDelayMs, this::onTimerFire);
		}
	}
}
