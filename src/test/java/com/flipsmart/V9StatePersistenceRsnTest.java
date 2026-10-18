package com.flipsmart;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Guards the RSN resolution used when persisting/restoring v9 flip-ladder state.
 *
 * The logout handler persists state AFTER {@link PlayerSession#onLogout()} has nulled the
 * live session RSN, so a naive {@code session.getRsn()} argument writes nothing and the
 * ladder state is lost across a logout. Persistence must fall back to the last-known RSN so
 * the state round-trips — the manual-mode ladder-freeze behaviour depends on it.
 */
public class V9StatePersistenceRsnTest
{
	@Test
	public void fallsBackToLastKnownWhenSessionRsnCleared()
	{
		assertEquals("dumbridge3", FlipSmartPlugin.resolveStateRsn(null, "dumbridge3"));
		assertEquals("dumbridge3", FlipSmartPlugin.resolveStateRsn("", "dumbridge3"));
	}

	@Test
	public void prefersLiveSessionRsnWhenPresent()
	{
		assertEquals("live", FlipSmartPlugin.resolveStateRsn("live", "stale"));
	}

	@Test
	public void nullWhenNeitherAvailable()
	{
		assertNull(FlipSmartPlugin.resolveStateRsn(null, null));
		assertNull(FlipSmartPlugin.resolveStateRsn("", null));
	}

	@Test
	public void onLogoutNullsRsnSoResolutionNeedsTheFallback()
	{
		PlayerSession session = new PlayerSession();
		session.setRsn("dumbridge3");
		session.onLogout();
		assertNull(session.getRsn());
		assertEquals("dumbridge3", FlipSmartPlugin.resolveStateRsn(session.getRsn(), "dumbridge3"));
	}
}
