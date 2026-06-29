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
	private static final String MONITOR = "Monitoring your flips";
	private static final String COLLECT = "Collect profit from GE";

	private static String select(boolean showHistory, boolean offerInterfaceOpen,
		String autoStatus, String fallback, boolean authenticated)
	{
		return select(showHistory, offerInterfaceOpen, autoStatus, fallback, authenticated, false);
	}

	private static String select(boolean showHistory, boolean offerInterfaceOpen,
		String autoStatus, String fallback, boolean authenticated, boolean hasTradeActivity)
	{
		return FlipAssistOverlay.selectNoFocusMessage(
			showHistory, offerInterfaceOpen, autoStatus, fallback, authenticated, hasTradeActivity,
			HIST, LOGIN, MONITOR, HINT);
	}

	// Collect-profit (autoStatus) is suppressed while in any offer interface.
	@Test
	public void suppressesAutoStatusDuringOfferInterface()
	{
		assertNull(select(false, true, COLLECT, null, true));
	}

	// Suppression also hides the auto-recommend fallback message during an offer interface.
	@Test
	public void suppressesFallbackDuringOfferInterface()
	{
		assertNull(select(false, true, null, "Monitoring active offers", true));
	}

	// When NOT in an offer interface, the collect-profit prompt shows normally.
	@Test
	public void showsAutoStatusWhenNotInOfferInterface()
	{
		assertEquals(COLLECT, select(false, false, COLLECT, null, true));
	}

	// The offer interface now takes priority over EVERYTHING — even the one-shot
	// history-backfill prompt is suppressed while a buy/sell screen is up.
	@Test
	public void suppressesHistoryDuringOfferInterface()
	{
		assertNull(select(true, true, COLLECT, null, true));
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

	// #814 AC11: with no GE activity, the default state is the generic hint, regardless of Auto.
	@Test
	public void defaultsToHintWhenNoTradeActivity()
	{
		assertEquals(HINT, select(false, false, null, null, true, false));
	}

	// #814 AC12/AC13: GE activity opens the monitoring log even when Auto is off (no auto messages).
	@Test
	public void showsMonitoringOnTradeActivityWithoutAuto()
	{
		assertEquals(MONITOR, select(false, false, null, null, true, true));
	}

	// Auto-recommend messages still take precedence over the activity-driven monitoring message.
	@Test
	public void autoFallbackTakesPrecedenceOverMonitoring()
	{
		assertEquals("Monitoring active offers",
			select(false, false, null, "Monitoring active offers", true, true));
	}

	// Trade activity must not surface the monitoring log while unauthenticated.
	@Test
	public void unauthenticatedTradeActivityStillShowsLogin()
	{
		assertEquals(LOGIN, select(false, false, null, null, false, true));
	}
}
