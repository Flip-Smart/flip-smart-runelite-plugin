package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the pure-function description formatters used by
 * {@link GeOfferDescriptionService} (issue #665).
 *
 * The expected strings here are exact — the AC examples in the ticket dictate
 * the wording (e.g. {@code "Tax applied: 2,000gp"}) and any deviation breaks
 * the user-visible contract. Color/label tags are matched verbatim too.
 */
public class GeOfferDescriptionFormatterTest
{
	private static final String LABEL_BUY = "<col=ffb83f>Daily Volume: </col>";
	private static final String LABEL_BREAKEVEN = "<col=ffb83f>Breakeven: </col>";
	private static final String LABEL_TAX = "<col=ffb83f>Tax applied: </col>";
	private static final String LABEL_PROFIT = "<col=ffb83f>Your profit: </col>";
	private static final String GREEN = "<col=00ff00>";
	private static final String RED = "<col=ff4040>";
	private static final String WHITE = "<col=ffffff>";

	// ---------------------------------------------------------------- AC2 — Buy

	@Test
	public void buyDescription_withVolume_formatsWithCommas()
	{
		String result = GeOfferDescriptionFormatter.formatBuyDescription(142_300);
		assertEquals(LABEL_BUY + "142,300", result);
	}

	@Test
	public void buyDescription_nullVolume_showsNA()
	{
		String result = GeOfferDescriptionFormatter.formatBuyDescription(null);
		assertEquals(LABEL_BUY + "N/A", result);
	}

	@Test
	public void buyDescription_zeroVolume_showsZero()
	{
		// Zero is a valid (if surprising) value — not the same as missing.
		String result = GeOfferDescriptionFormatter.formatBuyDescription(0);
		assertEquals(LABEL_BUY + "0", result);
	}

	// ---------------------------------------------------------------- AC3 — Breakeven

	@Test
	public void breakeven_unknownBuyPrice_showsQuestionMark()
	{
		String line = GeOfferDescriptionFormatter.formatBreakevenLine(null);
		assertEquals(LABEL_BREAKEVEN + "?", line);
	}

	@Test
	public void breakeven_atExemptThreshold_isIdentity()
	{
		// 50gp is exempt — break-even = buy price exactly
		String line = GeOfferDescriptionFormatter.formatBreakevenLine(50);
		assertEquals(LABEL_BREAKEVEN + "50 gp", line);
	}

	@Test
	public void breakeven_standardCase_compensatesForTwoPercent()
	{
		// Buy 100gp -> tax = floor(102 * 0.02) = 2 -> 102-2=100. Breakeven=102.
		String line = GeOfferDescriptionFormatter.formatBreakevenLine(100);
		assertEquals(LABEL_BREAKEVEN + "102 gp", line);
	}

	@Test
	public void breakeven_calc_invertsTaxExactly()
	{
		// Property: at the returned breakeven, the player exactly recovers the buy price.
		int[] buyPrices = {100, 1_000, 10_000, 100_000, 1_000_000, 50_000_000};
		for (int buy : buyPrices)
		{
			int breakeven = GeOfferDescriptionFormatter.calculateBreakevenPrice(buy);
			int tax = GeOfferDescriptionFormatter.calculateTaxPerItem(breakeven);
			assertTrue("buy=" + buy + " breakeven=" + breakeven + " tax=" + tax,
				breakeven - tax >= buy);
			// And one lower is insufficient (when not in the cap region)
			if (breakeven > buy + 1 && breakeven - 1 > 50)
			{
				int taxLower = GeOfferDescriptionFormatter.calculateTaxPerItem(breakeven - 1);
				assertTrue("breakeven not minimal for buy=" + buy,
					(breakeven - 1) - taxLower < buy);
			}
		}
	}

	// ---------------------------------------------------------------- AC4 — Tax

	@Test
	public void taxLine_qty1_usesExactGpSuffix()
	{
		// AC4 example: "Tax applied: 2,000gp" — exact, no shorthand, no space before gp
		String line = GeOfferDescriptionFormatter.formatTaxLine(2_000, 1);
		assertEquals(LABEL_TAX + "2,000gp", line);
	}

	@Test
	public void taxLine_qtyGt1_usesShortLowercaseKWithPerItemParens()
	{
		// AC4 example: "Tax applied: 100k (50k per item)"
		String line = GeOfferDescriptionFormatter.formatTaxLine(50_000, 2);
		assertEquals(LABEL_TAX + "100k (50k per item)", line);
	}

	@Test
	public void taxLine_belowExemptThreshold_isZero()
	{
		int tax = GeOfferDescriptionFormatter.calculateTaxPerItem(49);
		assertEquals(0, tax);
	}

	@Test
	public void taxLine_atCap_isFiveMillion()
	{
		// 2% of 1B = 20M, but cap = 5M
		int tax = GeOfferDescriptionFormatter.calculateTaxPerItem(1_000_000_000);
		assertEquals(5_000_000, tax);
	}

	@Test
	public void taxLine_zeroQty_treatsAsQty1Wording()
	{
		// Defensive: an offer mid-construction with qty not yet entered should
		// still produce a usable string, not a 0gp total which would be confusing.
		String line = GeOfferDescriptionFormatter.formatTaxLine(1_000, 0);
		assertEquals(LABEL_TAX + "1,000gp", line);
	}

	// ---------------------------------------------------------------- AC5 + AC6 — Profit (color + sign + format)

	@Test
	public void profitLine_unknownBuyPrice_isWhiteQuestionMark()
	{
		// AC5: "Your profit: ?" when buy history unavailable
		// AC6: ? renders white
		String line = GeOfferDescriptionFormatter.formatProfitLine(null, 10_000, 200, 1);
		assertEquals(LABEL_PROFIT + WHITE + "?</col>", line);
	}

	@Test
	public void profitLine_qty1_positive_isGreenWithPlusSignAndExactFormat()
	{
		// AC5 example: "Your profit: +210,000gp"
		// Buy 1M, sell ~1.214M (tax floor(0.02 * 1214286)=24285), profit = 1214286-1000000-24285 = 190001
		// Use cleaner numbers: buy=500,000  sell=714,286  tax=floor(0.02*714286)=14285  profit=714286-500000-14285=200001
		// Even cleaner: buy=1000  sell=4080  tax=floor(0.02*4080)=81  profit=4080-1000-81=2999
		// Match AC literally: derive sell to land at +210,000 profit qty=1 with buy=1,000,000
		// 1,000,000 + 210,000 + tax = sell; tax = floor(0.02*sell). Try sell=1,234,694 -> tax=24693 -> profit=1234694-1000000-24693=210001 (close enough)
		// For unit test, just assert the structure
		int buy = 1_000_000;
		int sell = 1_234_694;
		int taxPerItem = GeOfferDescriptionFormatter.calculateTaxPerItem(sell);
		int profitPerItem = sell - buy - taxPerItem;
		String line = GeOfferDescriptionFormatter.formatProfitLine(buy, sell, taxPerItem, 1);
		String expected = LABEL_PROFIT + GREEN + "+" + String.format("%,d", profitPerItem) + "gp</col>";
		assertEquals(expected, line);
	}

	@Test
	public void profitLine_qty1_negative_isRedWithMinusSignAndExactFormat()
	{
		// Selling at a loss
		int buy = 1_000_000;
		int sell = 900_000;  // tax = 18,000; profit = -118,000
		int taxPerItem = GeOfferDescriptionFormatter.calculateTaxPerItem(sell);
		int profitPerItem = sell - buy - taxPerItem;
		assertTrue(profitPerItem < 0);
		String line = GeOfferDescriptionFormatter.formatProfitLine(buy, sell, taxPerItem, 1);
		assertTrue("line should be red on loss: " + line, line.contains(RED));
		assertTrue("line should retain leading minus from negative count: " + line,
			line.contains("-" + String.format("%,d", -profitPerItem) + "gp"));
	}

	@Test
	public void profitLine_qty1_zeroProfit_isWhiteNoSign()
	{
		// Sell at the breakeven point. Use buy=100 -> breakeven=102, tax(102)=2 -> profit=0
		String line = GeOfferDescriptionFormatter.formatProfitLine(100, 102, 2, 1);
		assertEquals(LABEL_PROFIT + WHITE + "0gp</col>", line);
	}

	@Test
	public void profitLine_qtyGt1_positive_includesPerItemShorthand()
	{
		// AC5 example pattern: "Your profit: +420,000gp (+210k per item)"
		// (AC literal example has no + before per-item but we add for symmetry with the total.)
		int buy = 1_000_000;
		int sell = 1_234_694;
		int qty = 2;
		int taxPerItem = GeOfferDescriptionFormatter.calculateTaxPerItem(sell);
		int profitPerItem = sell - buy - taxPerItem;
		long total = (long) profitPerItem * qty;
		String line = GeOfferDescriptionFormatter.formatProfitLine(buy, sell, taxPerItem, qty);
		assertTrue("must show total profit with sign+exact: " + line,
			line.contains("+" + String.format("%,d", total) + "gp"));
		assertTrue("must show per-item shorthand in parens: " + line,
			line.contains("per item)"));
		assertTrue("must be color-coded green for positive: " + line, line.contains(GREEN));
	}

	@Test
	public void profitLine_qtyGt1_perItemUsesLowercaseK()
	{
		// 210,000 per item -> "210k" not "210K"
		String line = GeOfferDescriptionFormatter.formatProfitLine(100_000, 320_000, 6_400, 5);
		assertTrue("expected lowercase 'k' in per-item shorthand: " + line, line.contains("k per item"));
		assertFalse("must not contain uppercase 'K' shorthand: " + line, line.contains("K per item"));
	}

	// ---------------------------------------------------------------- formatShortLower edge cases

	@Test
	public void formatShortLower_dropsTrailingZeroDecimal()
	{
		assertEquals("100k", GeOfferDescriptionFormatter.formatShortLower(100_000));
		assertEquals("1M", GeOfferDescriptionFormatter.formatShortLower(1_000_000));
		assertEquals("1.5M", GeOfferDescriptionFormatter.formatShortLower(1_500_000));
		assertEquals("2.5k", GeOfferDescriptionFormatter.formatShortLower(2_500));
	}

	@Test
	public void formatShortLower_underThousand_isExactNumber()
	{
		assertEquals("999", GeOfferDescriptionFormatter.formatShortLower(999));
		assertEquals("-50", GeOfferDescriptionFormatter.formatShortLower(-50));
	}

	@Test
	public void formatShortLower_negative_carriesSign()
	{
		assertEquals("-1.5M", GeOfferDescriptionFormatter.formatShortLower(-1_500_000));
		assertEquals("-100k", GeOfferDescriptionFormatter.formatShortLower(-100_000));
	}

	// ---------------------------------------------------------------- full sell description integration

	@Test
	public void formatSellDescription_qty1_assemblesThreeLinesSeparatedByBr()
	{
		String desc = GeOfferDescriptionFormatter.formatSellDescription(1_000_000, 1_234_694, 1);
		String[] lines = desc.split("<br>");
		assertEquals(3, lines.length);
		assertTrue(lines[0].startsWith(LABEL_BREAKEVEN));
		assertTrue(lines[1].startsWith(LABEL_TAX));
		assertTrue(lines[2].startsWith(LABEL_PROFIT));
	}

	@Test
	public void formatSellDescription_unknownBuyPrice_breakevenAndProfitBothQuestionMark()
	{
		String desc = GeOfferDescriptionFormatter.formatSellDescription(null, 1_000_000, 5);
		assertTrue(desc.contains(LABEL_BREAKEVEN + "?"));
		assertTrue(desc.contains(LABEL_PROFIT + WHITE + "?</col>"));
		// Tax line still rendered because tax depends only on sell price + qty
		assertTrue(desc.contains(LABEL_TAX));
	}
}
