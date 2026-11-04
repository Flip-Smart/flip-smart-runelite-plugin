package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.exit.ExitPriceResolver;
import com.flipsmart.exit.ExitTradesMode;
import com.flipsmart.recommend.SmartSellPricer;
import com.flipsmart.session.OpenPosition;
import com.flipsmart.session.SessionStats;
import com.flipsmart.trading.ActiveFlipCardMetrics;
import com.flipsmart.trading.RealizedFlipProfit;
import com.flipsmart.util.GeTax;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The GE charges no tax on Hardcore Worlds. Each local tax path is pinned twice: the
 * Hardcore result (zero tax) and the main-world result, which must stay exactly as before.
 */
public class HardcoreGeTaxTest
{
	private static final int WHIP = 4151;
	private static final long BUY = 900_000L;
	private static final long SELL = 1_000_000L;
	private static final int QTY = 10;

	@After
	public void resetEconomy()
	{
		GeTax.setHardcoreWorld(false);
	}

	private static void hardcore()
	{
		GeTax.setHardcoreWorld(true);
	}

	@Test
	public void taxAndExemption()
	{
		assertEquals(20_000, GeTax.taxFor(WHIP, SELL));
		assertEquals(5_000_000, GeTax.taxFor(WHIP, 500_000_000L));
		assertEquals(20_000, GeTax.taxFor(SELL));
		assertFalse(GeTax.isExempt(WHIP, SELL));

		hardcore();
		assertEquals(0, GeTax.taxFor(WHIP, SELL));
		assertEquals(0, GeTax.taxFor(WHIP, 500_000_000L));
		assertEquals(0, GeTax.taxFor(SELL));
		assertTrue(GeTax.isExempt(WHIP, SELL));
	}

	@Test
	public void breakevenExitPrice()
	{
		assertEquals(1_020_408L, GeTax.breakevenSellPrice(WHIP, SELL));
		assertEquals(1_020_408L, ExitPriceResolver.resolve(ExitTradesMode.BREAKEVEN, WHIP, SELL, 0L, null));
		assertEquals(505_000_000L, GeTax.breakevenSellPrice(500_000_000L));

		hardcore();
		assertEquals(SELL, GeTax.breakevenSellPrice(WHIP, SELL));
		assertEquals(SELL, ExitPriceResolver.resolve(ExitTradesMode.BREAKEVEN, WHIP, SELL, 0L, null));
		assertEquals(500_000_000L, GeTax.breakevenSellPrice(500_000_000L));
	}

	@Test
	public void backendBreakevenStillWinsOnHardcore()
	{
		hardcore();
		assertEquals(1_234_567L, ExitPriceResolver.resolve(ExitTradesMode.BREAKEVEN, WHIP, SELL, 1_234_567L, null));
	}

	@Test
	public void smartSellMinimumProfitablePrice()
	{
		assertEquals(1_020_410L, SmartSellPricer.calculateMinProfitableSellPrice(SELL));
		assertEquals(104L, SmartSellPricer.calculateMinProfitableSellPrice(100));
		assertEquals(505_000_001L, SmartSellPricer.calculateMinProfitableSellPrice(500_000_000L));
		assertEquals(0L, SmartSellPricer.calculateMinProfitableSellPrice(0));

		hardcore();
		assertEquals(SELL + 1, SmartSellPricer.calculateMinProfitableSellPrice(SELL));
		assertEquals(101L, SmartSellPricer.calculateMinProfitableSellPrice(100));
		assertEquals(0L, SmartSellPricer.calculateMinProfitableSellPrice(0));
	}

	private static List<OfferRecord> soldTen()
	{
		return Collections.singletonList(
			OfferRecord.newOffer(1L, 0, WHIP, "Abyssal whip", false, QTY, (int) SELL, 5_000L)
				.withFill(QTY, SELL * QTY, OfferState.FILLED, 5_000L));
	}

	@Test
	public void realizedProfit()
	{
		RealizedFlipProfit.Result main = RealizedFlipProfit.compute(soldTen(), WHIP, BUY, 0L);
		assertEquals(200_000L, main.taxTotal);
		assertEquals(800_000L, main.netProfit);

		hardcore();
		RealizedFlipProfit.Result hc = RealizedFlipProfit.compute(soldTen(), WHIP, BUY, 0L);
		assertEquals(0L, hc.taxTotal);
		assertEquals(1_000_000L, hc.netProfit);
	}

	private static long projectedSessionProfit()
	{
		return SessionStats.compute(Collections.emptyList(),
			Collections.singletonList(new OpenPosition(WHIP, QTY, BUY, SELL)), 0L, 0L).projectedProfit;
	}

	@Test
	public void sessionStatsUnrealisedProfit()
	{
		assertEquals(800_000L, projectedSessionProfit());

		hardcore();
		assertEquals(1_000_000L, projectedSessionProfit());
	}

	@Test
	public void activeFlipCardMetrics()
	{
		ActiveFlipCardMetrics.Result main = ActiveFlipCardMetrics.compute(950_000L, SELL, WHIP, BUY, 0, QTY);
		assertEquals(200_000L, main.totalTax);
		assertEquals(80_000L, main.positionNetPerUnit);

		hardcore();
		ActiveFlipCardMetrics.Result hc = ActiveFlipCardMetrics.compute(950_000L, SELL, WHIP, BUY, 0, QTY);
		assertEquals(0L, hc.totalTax);
		assertEquals(100_000L, hc.positionNetPerUnit);
	}

	@Test
	public void offerDescription()
	{
		assertEquals(MAIN_DESCRIPTION, GeOfferDescriptionFormatter.formatSellDescription(WHIP, BUY, SELL, QTY));

		hardcore();
		assertEquals(HARDCORE_DESCRIPTION, GeOfferDescriptionFormatter.formatSellDescription(WHIP, BUY, SELL, QTY));
	}

	private static final String MAIN_DESCRIPTION = "<col=ffb83f>Breakeven: </col><col=ffffff>918,367 gp</col><br>"
		+ "<col=ffb83f>Tax applied: </col>200k (20k per item)<br>"
		+ "<col=ffb83f>Your profit: </col><col=00ff00>+800,000 gp (+80k per item)</col>";
	private static final String HARDCORE_DESCRIPTION = "<col=ffb83f>Breakeven: </col><col=ffffff>900,000 gp</col><br>"
		+ "<col=ffb83f>Tax applied: </col>0 (0 per item)<br>"
		+ "<col=ffb83f>Your profit: </col><col=00ff00>+1,000,000 gp (+100k per item)</col>";
}
