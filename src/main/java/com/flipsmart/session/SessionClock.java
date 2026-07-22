package com.flipsmart.session;

/**
 * Tracks the wall-clock session start and accumulated logged-in time, excluding
 * spans the player was logged out. Poll {@link #update(boolean, long)} with the
 * current login state; read elapsed logged-in time with {@link #activeMs(long)}.
 * Pure and deterministic — every "now" is injected so it unit-tests without a
 * real clock.
 */
public final class SessionClock
{
	private final long sessionStartMs;
	private long accumulatedActiveMs;
	private Long loginStartMs;

	public SessionClock(long sessionStartMs)
	{
		this.sessionStartMs = sessionStartMs;
	}

	public long sessionStartMs()
	{
		return sessionStartMs;
	}

	public void update(boolean loggedIn, long nowMs)
	{
		if (loggedIn && loginStartMs == null)
		{
			loginStartMs = nowMs;
		}
		else if (!loggedIn && loginStartMs != null)
		{
			accumulatedActiveMs += Math.max(0L, nowMs - loginStartMs);
			loginStartMs = null;
		}
	}

	public long activeMs(long nowMs)
	{
		long openSpan = loginStartMs != null ? Math.max(0L, nowMs - loginStartMs) : 0L;
		return accumulatedActiveMs + openSpan;
	}
}
