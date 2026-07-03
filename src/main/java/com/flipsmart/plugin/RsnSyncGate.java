package com.flipsmart.plugin;

/**
 * Deduplicates RSN pushes to the backend. The RSN sync path fires on every
 * LOGGED_IN transition — including every world hop — but the backend only
 * needs to hear about an RSN it has not been told about this client session.
 * Callers mark a push only after the backend confirms it, so a failed push
 * is retried on the next login transition.
 */
public class RsnSyncGate
{
	private volatile String lastPushedRsn;

	public boolean shouldPush(String rsn)
	{
		return rsn != null && !rsn.isEmpty() && !rsn.equals(lastPushedRsn);
	}

	public void markPushed(String rsn)
	{
		lastPushedRsn = rsn;
	}

	/** Forget the pushed state so the next sync pushes again (e.g. after re-auth). */
	public void reset()
	{
		lastPushedRsn = null;
	}
}
