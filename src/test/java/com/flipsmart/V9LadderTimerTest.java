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
	public void ladder2NotDueBeforeTwoIntervals()
	{
		assertEquals(0, FlipSmartPlugin.v9DueRung(1, 0, INTERVAL, INTERVAL, 2 * INTERVAL - 1));
	}

	@Test
	public void ladder2DueAtTwoIntervalsFromListing()
	{
		assertEquals(2, FlipSmartPlugin.v9DueRung(1, 0, INTERVAL, INTERVAL, 2 * INTERVAL));
	}

	@Test
	public void ladder2CountsOfflineGapInsteadOfDeferringFromLateLadder1()
	{
		// Player was away: ladder 1 wasn't processed until 42 min (well past its 20-min due time).
		// Ladder 2 must be due immediately (wall-clock listing + 2 intervals = 40 min has passed),
		// NOT pushed to ladder1ResolvedAt + interval (which would be 62 min).
		long now = 42 * 60 * 1000L;
		long ladder1ResolvedLate = now;
		assertEquals(2, FlipSmartPlugin.v9DueRung(1, 0, ladder1ResolvedLate, INTERVAL, now));
	}
}
