package com.flipsmart;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class V9LadderTimerTest
{
	private static final long INTERVAL = 20 * 60 * 1000L; // 30m timeframe rung interval

	@Test
	public void ladder1NotDueBeforeOneInterval()
	{
		assertEquals(0, FlipSmartPlugin.v9DueRung(0, 0, 0, INTERVAL, INTERVAL - 1));
	}

	@Test
	public void ladder1DueAtOneInterval()
	{
		assertEquals(1, FlipSmartPlugin.v9DueRung(0, 0, 0, INTERVAL, INTERVAL));
	}

	@Test
	public void ladder2RequiresLadder1Resolved()
	{
		// rung 1 but ladder 1 never stamped a resolve time -> nothing due
		assertEquals(0, FlipSmartPlugin.v9DueRung(1, 0, 0, INTERVAL, 5 * INTERVAL));
	}

	@Test
	public void ladder2NotDueBeforeOneIntervalAfterLadder1Resolved()
	{
		// Ladder 1 resolved on time at one interval; ladder 2 is due one interval AFTER that.
		assertEquals(0, FlipSmartPlugin.v9DueRung(1, 0, INTERVAL, INTERVAL, 2 * INTERVAL - 1));
	}

	@Test
	public void ladder2DueOneIntervalAfterLadder1Resolved()
	{
		assertEquals(2, FlipSmartPlugin.v9DueRung(1, 0, INTERVAL, INTERVAL, 2 * INTERVAL));
	}

	@Test
	public void ladder2DoesNotSkipOnLoginAfterOfflineGap()
	{
		// Player was away well past ladder 1's due time; on login ladder 1 fires and stamps its
		// resolve time (here 42 min). Ladder 2 must NOT be due at that same instant — it waits a
		// full interval after ladder 1 resolves, so an offline gap can't skip straight to the dump
		// (Scapenomics 2026-09-30). Regression for the old wall-clock-from-listing anchor.
		long login = 42 * 60 * 1000L;
		assertEquals(0, FlipSmartPlugin.v9DueRung(1, 0, login, INTERVAL, login));
		// ...and it becomes due one interval later.
		assertEquals(2, FlipSmartPlugin.v9DueRung(1, 0, login, INTERVAL, login + INTERVAL));
	}
}
