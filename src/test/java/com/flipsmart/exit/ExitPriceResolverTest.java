package com.flipsmart.exit;

import com.flipsmart.api.dto.WikiPrice;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ExitPriceResolverTest
{
	@Test
	public void breakevenUsesGeTaxWhenBasisKnown()
	{
		// breakevenSellPrice(1000) = smallest S with S - floor(0.02S) >= 1000 => 1020
		int p = ExitPriceResolver.resolve(ExitTradesMode.BREAKEVEN, 4151, 1000, new WikiPrice(1100, 950));
		assertEquals(1020, p);
	}

	@Test
	public void instantUsesInstaSell()
	{
		int p = ExitPriceResolver.resolve(ExitTradesMode.INSTANT, 4151, 1000, new WikiPrice(1100, 950));
		assertEquals(950, p);
	}

	@Test
	public void breakevenFallsBackToMidWhenBasisMissing()
	{
		// no basis -> midPrice of (1100,950) = 1025
		int p = ExitPriceResolver.resolve(ExitTradesMode.BREAKEVEN, 4151, 0, new WikiPrice(1100, 950));
		assertEquals(1025, p);
	}

	@Test
	public void instantFallsBackToMidWhenInstaSellMissing()
	{
		int p = ExitPriceResolver.resolve(ExitTradesMode.INSTANT, 4151, 0, new WikiPrice(1100, 0));
		assertEquals(1100, p); // midPrice degrades to present side
	}

	@Test
	public void zeroWhenNoDataAtAll()
	{
		assertEquals(0, ExitPriceResolver.resolve(ExitTradesMode.INSTANT, 4151, 0, null));
		assertEquals(0, ExitPriceResolver.resolve(ExitTradesMode.BREAKEVEN, 4151, 0, new WikiPrice(0, 0)));
	}
}
