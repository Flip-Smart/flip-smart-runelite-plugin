package com.flipsmart.plugin;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Regression coverage for issue #917: a manual refresh must reset BOTH the visual
 * countdown and the underlying refresh scheduler together. The countdown deadline
 * ({@code nextFlipFinderRefreshAtMillis}) is the single source of truth the panel's
 * label reads, so asserting it here proves the label and the actual trigger stay in
 * step — the next auto-refresh always sits a full interval after the manual refresh.
 */
public class PluginSchedulerTest
{
	private static final int FIVE_MINUTES = 5;
	private static final long FIVE_MINUTES_MS = FIVE_MINUTES * 60 * 1000L;

	private long now;
	private PluginScheduler scheduler;

	@Before
	public void setUp()
	{
		now = 0;
		scheduler = new PluginScheduler(() -> now);
	}

	@After
	public void tearDown()
	{
		scheduler.stopFlipFinderRefreshTimer();
	}

	/** AC5(b): starting the timer aligns the next refresh to a full interval from now. */
	@Test
	public void startAlignsNextRefreshToFullInterval()
	{
		scheduler.startFlipFinderRefreshTimer(FIVE_MINUTES, () -> true, () -> { });

		assertEquals(FIVE_MINUTES_MS, scheduler.getNextFlipFinderRefreshAtMillis());
	}

	/**
	 * AC4 / AC5(c): a manual refresh part-way through the cycle restarts the timer, so
	 * the next auto-refresh fires a full interval from the manual click — never early on
	 * the stale original deadline (which was the bug).
	 */
	@Test
	public void manualRefreshRealignsNextRefreshToFullIntervalFromNow()
	{
		scheduler.startFlipFinderRefreshTimer(FIVE_MINUTES, () -> true, () -> { });
		assertEquals(FIVE_MINUTES_MS, scheduler.getNextFlipFinderRefreshAtMillis());

		// Player manually refreshes 200s into the 300s cycle.
		now = 200_000;
		scheduler.startFlipFinderRefreshTimer(FIVE_MINUTES, () -> true, () -> { });

		// Next auto-refresh is a FULL interval from the manual refresh, not the stale 300_000.
		assertEquals(now + FIVE_MINUTES_MS, scheduler.getNextFlipFinderRefreshAtMillis());
	}

	/** Stopping the timer clears the deadline so the label falls back to its default. */
	@Test
	public void stopClearsNextRefreshDeadline()
	{
		scheduler.startFlipFinderRefreshTimer(FIVE_MINUTES, () -> true, () -> { });
		scheduler.stopFlipFinderRefreshTimer();

		assertEquals(0, scheduler.getNextFlipFinderRefreshAtMillis());
	}

	/** The configured interval is clamped to at least one minute before scheduling. */
	@Test
	public void intervalIsClampedToConfiguredMinimum()
	{
		scheduler.startFlipFinderRefreshTimer(0, () -> true, () -> { });

		assertEquals(60_000L, scheduler.getNextFlipFinderRefreshAtMillis());
	}
}
