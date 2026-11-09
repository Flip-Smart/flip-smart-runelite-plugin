package com.flipsmart.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/** Main and Hardcore keep separate session stats: their own start and their own active time. */
public class EconomySessionClocksTest
{
	private static final long MIN = 60_000L;

	@Test
	public void eachWorldKeepsItsOwnActiveTime()
	{
		EconomySessionClocks clocks = new EconomySessionClocks(0);
		clocks.update(false, true, 0);
		clocks.update(false, true, 10 * MIN);

		clocks.update(true, true, 10 * MIN);
		clocks.update(true, true, 25 * MIN);

		assertEquals("Hardcore counted only its own 15 minutes", 15 * MIN, clocks.current(true, 25 * MIN).activeMs(25 * MIN));
		assertEquals("main paused while on Hardcore", 10 * MIN, clocks.current(false, 25 * MIN).activeMs(25 * MIN));

		clocks.update(false, true, 25 * MIN);
		clocks.update(false, true, 30 * MIN);
		assertEquals("main resumes where it left off", 15 * MIN, clocks.current(false, 30 * MIN).activeMs(30 * MIN));
	}

	@Test
	public void aHardcoreSessionStartsWhenHardcoreIsFirstEntered()
	{
		EconomySessionClocks clocks = new EconomySessionClocks(0);
		SessionClock hardcore = clocks.current(true, 40 * MIN);

		assertEquals(40 * MIN, hardcore.startMs());
		assertSame(hardcore, clocks.current(true, 90 * MIN));
		assertNotSame(hardcore, clocks.current(false, 90 * MIN));
		assertEquals(0, clocks.current(false, 90 * MIN).startMs());
	}
}
