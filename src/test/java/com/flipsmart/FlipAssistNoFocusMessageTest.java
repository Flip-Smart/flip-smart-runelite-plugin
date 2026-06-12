package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Covers {@link FlipAssistOverlay#selectNoFocusMessage} — which hint/status box (if any)
 * the overlay draws when no flip is focused. Issue #726: the "Collect profit" prompt
 * (and friends) must NOT overlay the GE buy/sell offer-setup screen, but must re-surface
 * once setup closes, and the history-backfill prompt stays the one high-priority exception.
 */
public class FlipAssistNoFocusMessageTest
{
	private static final String HIST = "history";
	private static final String LOGIN = "login";
	private static final String HINT = "hint";
	private static final String COLLECT = "Collect profit from GE";

	private static String select(boolean showHistory, boolean offerSetupOpen,
		String autoStatus, String fallback, boolean authenticated)
	{
		return FlipAssistOverlay.selectNoFocusMessage(
			showHistory, offerSetupOpen, autoStatus, fallback, authenticated, HIST, LOGIN, HINT);
	}

	// AC1/AC2 — collect-profit (autoStatus) is suppressed while in the offer-setup screen.
	@Test
	public void suppressesAutoStatusDuringOfferSetup()
	{
		assertNull(select(false, true, COLLECT, null, true));
	}

	// Suppression also hides the auto-recommend fallback message during setup.
	@Test
	public void suppressesFallbackDuringOfferSetup()
	{
		assertNull(select(false, true, null, "Monitoring active offers", true));
	}

	// AC4/AC5 — when NOT in setup, the collect-profit prompt shows normally.
	@Test
	public void showsAutoStatusWhenNotInSetup()
	{
		assertEquals(COLLECT, select(false, false, COLLECT, null, true));
	}

	// History-backfill recovery is one-shot per session — it still surfaces during setup.
	@Test
	public void historyPromptSurfacesEvenDuringOfferSetup()
	{
		assertEquals(HIST, select(true, true, COLLECT, null, true));
	}

	// History takes precedence over auto-status when both are present.
	@Test
	public void historyTakesPrecedenceOverAutoStatus()
	{
		assertEquals(HIST, select(true, false, COLLECT, null, true));
	}

	// Falls back to the auto-recommend fallback message when no autoStatus.
	@Test
	public void usesFallbackWhenNoAutoStatus()
	{
		assertEquals("fallback msg", select(false, false, null, "fallback msg", true));
	}

	// Login prompt when unauthenticated and nothing else to show.
	@Test
	public void showsLoginWhenUnauthenticatedAndNoMessages()
	{
		assertEquals(LOGIN, select(false, false, null, null, false));
	}

	// Generic hint when authenticated and nothing else to show.
	@Test
	public void showsHintWhenAuthenticatedAndNothingElse()
	{
		assertEquals(HINT, select(false, false, null, null, true));
	}
}
