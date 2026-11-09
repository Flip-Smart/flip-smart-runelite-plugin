package com.flipsmart.session;

/**
 * One session clock per game economy. Main and Hardcore Worlds are separate markets, so
 * each keeps its own session window and active time; only the current one counts time.
 */
public final class EconomySessionClocks
{
	private final SessionClock main;
	private SessionClock hardcore;

	public EconomySessionClocks(long nowMs)
	{
		main = new SessionClock(nowMs);
	}

	/** The clock for the given economy; a Hardcore session starts when it is first entered. */
	public synchronized SessionClock current(boolean hardcoreWorld, long nowMs)
	{
		if (!hardcoreWorld)
		{
			return main;
		}
		if (hardcore == null)
		{
			hardcore = new SessionClock(nowMs);
		}
		return hardcore;
	}

	/** Advance the current economy's clock and pause the other one. */
	public synchronized void update(boolean hardcoreWorld, boolean loggedIn, long nowMs)
	{
		current(hardcoreWorld, nowMs).update(loggedIn, nowMs);
		SessionClock other = hardcoreWorld ? main : hardcore;
		if (other != null)
		{
			other.update(false, nowMs);
		}
	}
}
