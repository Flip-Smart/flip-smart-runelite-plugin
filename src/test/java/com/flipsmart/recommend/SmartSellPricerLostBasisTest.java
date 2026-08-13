package com.flipsmart.recommend;

import com.flipsmart.domain.flip.ActiveFlip;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A flip whose cost basis has gone missing must never be priced as if the basis were
 * genuinely zero. The breakeven formula reads a lost basis as "this cost me nothing",
 * so it happily returns 1gp — a price the player can act on and lose the whole position.
 */
public class SmartSellPricerLostBasisTest
{
	private static final int MARKET_PRICE = 8_925_470;

	private static ActiveFlip flip(int averageBuyPrice, Integer recommendedSellPrice)
	{
		ActiveFlip f = new ActiveFlip();
		f.setItemId(1234);
		f.setItemName("Blood moon tassets");
		f.setAverageBuyPrice(averageBuyPrice);
		f.setRecommendedSellPrice(recommendedSellPrice);
		return f;
	}

	@Test
	public void minProfitableSellPriceIsNotComputableWithoutABasis()
	{
		assertEquals("a lost basis has no breakeven; 0 means 'not computable'",
			0, SmartSellPricer.calculateMinProfitableSellPrice(0));
	}

	@Test
	public void minProfitableSellPriceStillComputesFromARealBasis()
	{
		assertEquals(104, SmartSellPricer.calculateMinProfitableSellPrice(100));
	}

	@Test
	public void smartSellPriceFallsBackToMarketWhenBasisIsLost()
	{
		Integer price = SmartSellPricer.calculateSmartSellPrice(flip(0, null), MARKET_PRICE);

		assertEquals("with no basis and no target, the market price is the only sane answer",
			Integer.valueOf(MARKET_PRICE), price);
	}

	@Test
	public void smartSellPriceKeepsTheOriginalTargetWhenBasisIsLost()
	{
		Integer price = SmartSellPricer.calculateSmartSellPrice(flip(0, 9_100_000), MARKET_PRICE);

		assertEquals("the original recommendation outranks the market fallback",
			Integer.valueOf(9_100_000), price);
	}

	@Test
	public void smartSellPriceIsUnavailableWhenBasisAndMarketAreBothUnknown()
	{
		assertNull("nothing is known, so no price may be surfaced",
			SmartSellPricer.calculateSmartSellPrice(flip(0, null), null));
	}

	@Test
	public void smartSellPriceIsUnchangedForAHealthyFlip()
	{
		Integer price = SmartSellPricer.calculateSmartSellPrice(flip(8_000_000, null), MARKET_PRICE);

		assertEquals(Integer.valueOf(SmartSellPricer.calculateMinProfitableSellPrice(8_000_000)), price);
	}

	@Test
	public void aOneGpSellIsImplausibleAgainstARealMarketPrice()
	{
		assertTrue(SmartSellPricer.isImplausibleSellPrice(1, MARKET_PRICE));
	}

	@Test
	public void cuttingLossesBelowMarketIsStillPlausible()
	{
		assertFalse("a 10% loss-cut is a legitimate exit, not a corrupt price",
			SmartSellPricer.isImplausibleSellPrice(8_000_000, MARKET_PRICE));
	}

	@Test
	public void noSellPriceIsImplausibleWhenTheMarketIsUnknown()
	{
		assertFalse("without a market reference there is nothing to judge against",
			SmartSellPricer.isImplausibleSellPrice(1, null));
	}
}
