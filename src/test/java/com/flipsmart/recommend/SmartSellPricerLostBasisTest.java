package com.flipsmart.recommend;

import com.flipsmart.domain.flip.ActiveFlip;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * A flip whose cost basis has gone missing must never be priced as if the basis were
 * genuinely zero. The breakeven formula reads a lost basis as "this cost me nothing",
 * so it happily returns 1gp — a price the player can act on and lose the whole position.
 */
public class SmartSellPricerLostBasisTest
{
	private static final long MARKET_PRICE = 8_925_470L;

	private static ActiveFlip flip(int averageBuyPrice, Integer recommendedSellPrice)
	{
		ActiveFlip f = new ActiveFlip();
		f.setItemId(1234);
		f.setItemName("Blood moon tassets");
		f.setAverageBuyPrice(averageBuyPrice);
		f.setRecommendedSellPrice(recommendedSellPrice == null ? null : recommendedSellPrice.longValue());
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
		Long price = SmartSellPricer.calculateSmartSellPrice(flip(0, null), MARKET_PRICE);

		assertEquals("with no basis and no target, the market price is the only sane answer",
			Long.valueOf(MARKET_PRICE), price);
	}

	@Test
	public void smartSellPriceKeepsTheOriginalTargetWhenBasisIsLost()
	{
		Long price = SmartSellPricer.calculateSmartSellPrice(flip(0, 9_100_000), MARKET_PRICE);

		assertEquals("the original recommendation outranks the market fallback",
			Long.valueOf(9_100_000), price);
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
		Long price = SmartSellPricer.calculateSmartSellPrice(flip(8_000_000, null), MARKET_PRICE);

		assertEquals(Long.valueOf(SmartSellPricer.calculateMinProfitableSellPrice(8_000_000)), price);
	}

	@Test
	public void smartSellPriceKeepsRecommendationThatClearsBreakeven()
	{
		Long price = SmartSellPricer.calculateSmartSellPrice(flip(8_000_000, 9_000_000), MARKET_PRICE);

		assertEquals("a recommendation at or above breakeven outranks the market price",
			Long.valueOf(9_000_000), price);
	}

	@Test
	public void smartSellPriceKeepsRecommendationWhenUnderwaterAndMarketIsUnviable()
	{
		Long price = SmartSellPricer.calculateSmartSellPrice(flip(8_000_000, 7_000_000), null);

		assertEquals("with no viable market, an underwater recommendation is still the last known target",
			Long.valueOf(7_000_000), price);
	}

	@Test
	public void smartSellPriceFallsBackToBreakevenWhenOnlyBasisIsKnown()
	{
		Long price = SmartSellPricer.calculateSmartSellPrice(flip(8_000_000, null), null);

		assertEquals("no recommendation and no market leaves breakeven as the floor",
			Long.valueOf(SmartSellPricer.calculateMinProfitableSellPrice(8_000_000)), price);
	}

	@Test
	public void smartSellPriceRejectsANonPositiveRecommendation()
	{
		Long price = SmartSellPricer.calculateSmartSellPrice(flip(8_000_000, 0), null);

		assertEquals("a non-positive recommendation is not a price; breakeven is the floor",
			Long.valueOf(SmartSellPricer.calculateMinProfitableSellPrice(8_000_000)), price);
	}
}
