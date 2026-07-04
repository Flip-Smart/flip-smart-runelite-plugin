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
	public static final int MAX_WAIT_MS = 10_000;

	private final BiConsumer<Integer, Runnable> oneShotScheduler;
	private final LongSupplier clock;
	private final Runnable fullRefresh;
	private final Runnable activeFlipsRefresh;

	private boolean windowOpen;
	private boolean fullRequested;
	private long firstRequestAt;
	private long lastRequestAt;

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
		synchronized (this)
		{
			long now = clock.getAsLong();
			lastRequestAt = now;
			fullRequested |= full;
			if (windowOpen)
			{
				return;
			}
			windowOpen = true;
			firstRequestAt = now;
		}
		oneShotScheduler.accept(QUIET_WINDOW_MS, this::onTimerFire);
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
			if (sinceLast >= QUIET_WINDOW_MS || sinceFirst >= MAX_WAIT_MS)
			{
				action = fullRequested ? fullRefresh : activeFlipsRefresh;
				windowOpen = false;
				fullRequested = false;
			}
			else
			{
				nextDelayMs = (int) Math.max(1, Math.min(QUIET_WINDOW_MS - sinceLast, MAX_WAIT_MS - sinceFirst));
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
