package com.flipsmart.recommend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Manual-mode sell pricing must resolve from local state alone.
 *
 * Prod (#1133): with Auto Mode off, the sell focus was resolved only from the backend
 * active-flips list, which is trimmed to the subscription's display slots (2 on free).
 * A free user's held position could be absent from that list entirely, so clicking the
 * item to sell it back populated no price at all. Selling what you already own must not
 * depend on a display cap, so every price source used here is local.
 */
public class ManualSellFocusTest
{
	@Test
	public void prefersThePanelDisplayedPrice()
	{
		assertEquals(Long.valueOf(120), ManualSellFocus.resolveSellPrice(120L, 110L, 100));
	}

	@Test
	public void fallsBackToTheSessionRecommendedPriceWhenThePanelHasNone()
	{
		assertEquals(Long.valueOf(110), ManualSellFocus.resolveSellPrice(null, 110L, 100));
	}

	@Test
	public void fallsBackToMinProfitableWhenOnlyTheLocalBuyBasisIsKnown()
	{
		// The free-tier regression: no backend flip, no cached price — the buy basis in
		// the offer store is enough to price the exit.
		assertEquals(
			Long.valueOf(SmartSellPricer.calculateMinProfitableSellPrice(100)),
			ManualSellFocus.resolveSellPrice(null, null, 100));
	}

	@Test
	public void treatsNonPositivePricesAsAbsent()
	{
		assertEquals(Long.valueOf(110), ManualSellFocus.resolveSellPrice(0L, 110L, 100));
		assertEquals(
			Long.valueOf(SmartSellPricer.calculateMinProfitableSellPrice(100)),
			ManualSellFocus.resolveSellPrice(-1L, 0L, 100));
	}

	@Test
	public void unresolvableWithoutAnyPriceOrBuyBasis()
	{
		assertNull(ManualSellFocus.resolveSellPrice(null, null, 0));
	}

	@Test
	public void neverPricesBelowBreakevenWhenFallingBackToBasis()
	{
		// A basis-derived price must clear the 2% GE tax, else the "fix" would hand the
		// player a guaranteed loss instead of no price at all.
		int basis = 1_000_000;
		Long resolved = ManualSellFocus.resolveSellPrice(null, null, basis);
		assertEquals(true, resolved != null && resolved * 0.98 > basis);
	}
}
