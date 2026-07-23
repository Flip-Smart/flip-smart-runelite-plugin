package com.flipsmart.session;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SessionClockTest
{
	@Test
	public void noLoggedInTimeBeforeFirstLogin()
	{
		SessionClock clock = new SessionClock(1_000L);
		assertEquals(1_000L, clock.startMs());
		assertEquals(0L, clock.activeMs(50_000L));
	}

	@Test
	public void accruesOpenLoggedInSpan()
	{
		SessionClock clock = new SessionClock(1_000L);
		clock.update(true, 1_000L);
		assertEquals(3_000L, clock.activeMs(4_000L));
	}

	@Test
	public void freezesAccrualWhileLoggedOut()
	{
		SessionClock clock = new SessionClock(1_000L);
		clock.update(true, 1_000L);
		clock.update(false, 4_000L);
		assertEquals(3_000L, clock.activeMs(9_999L));
	}

	@Test
	public void resumesAccrualAfterRelogin()
	{
		SessionClock clock = new SessionClock(1_000L);
		clock.update(true, 1_000L);
		clock.update(false, 4_000L);
		clock.update(true, 5_000L);
		assertEquals(4_000L, clock.activeMs(6_000L));
	}

	@Test
	public void repeatedLoggedInUpdatesDoNotResetOpenSpan()
	{
		SessionClock clock = new SessionClock(1_000L);
		clock.update(true, 1_000L);
		clock.update(true, 2_000L);
		assertEquals(2_000L, clock.activeMs(3_000L));
	}
}
