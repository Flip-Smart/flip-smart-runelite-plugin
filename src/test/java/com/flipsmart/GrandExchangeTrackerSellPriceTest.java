package com.flipsmart;

import com.flipsmart.domain.flip.ActiveFlip;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Flip Assist prompted a sell at 1gp on a 26.9m position after the cost basis went
 * missing. Every price this resolver hands back has to trace to the player's own
 * basis, the original target, or the live market — and a price that cannot be
 * sourced must surface as nothing at all rather than as a number.
 */
public class GrandExchangeTrackerSellPriceTest
{
	private static final int MARKET = 8_925_470;

	private static ActiveFlip flip(int averageBuyPrice, Integer recommendedSellPrice)
	{
		ActiveFlip f = new ActiveFlip();
		f.setItemId(29_804);
		f.setItemName("Blood moon tassets");
		f.setAverageBuyPrice(averageBuyPrice);
		f.setRecommendedSellPrice(recommendedSellPrice);
		return f;
	}

	@Test
	public void prefersThePanelPriceWhenItIsSane()
	{
		assertEquals(Integer.valueOf(9_200_000),
			GrandExchangeTracker.resolveSellFocusPrice(9_200_000, flip(8_900_000, 9_100_000), MARKET));
	}

	@Test
	public void rejectsACorruptPanelPriceAndUsesTheOriginalTarget()
	{
		assertEquals("a cached 1gp must never outrank the real target",
			Integer.valueOf(9_100_000),
			GrandExchangeTracker.resolveSellFocusPrice(1, flip(0, 9_100_000), MARKET));
	}

	@Test
	public void fallsBackToMarketWhenBasisAndTargetAreBothGone()
	{
		assertEquals(Integer.valueOf(MARKET),
			GrandExchangeTracker.resolveSellFocusPrice(null, flip(0, null), MARKET));
	}

	@Test
	public void yieldsNoPriceWhenNothingIsKnown()
	{
		assertNull("no basis, no target, no market — Flip Assist must stay silent",
			GrandExchangeTracker.resolveSellFocusPrice(null, flip(0, null), null));
	}

	@Test
	public void aCorruptPanelPriceCannotSurviveEvenWithoutATarget()
	{
		assertEquals(Integer.valueOf(MARKET),
			GrandExchangeTracker.resolveSellFocusPrice(1, flip(0, null), MARKET));
	}

	@Test
	public void healthyFlipWithNoPanelPriceStillPricesOffTheBasis()
	{
		assertEquals(Integer.valueOf(9_100_000),
			GrandExchangeTracker.resolveSellFocusPrice(null, flip(8_900_000, 9_100_000), MARKET));
	}
}
