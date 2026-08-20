package com.flipsmart.ui.panel;

import java.awt.Color;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Characterization tests for the formatting layer every card renders through.
 *
 * <p>These pin CURRENT observable behaviour, not desired behaviour. Each value below was
 * captured by running the real methods, so a refactor that changes any rendered string
 * fails here loudly instead of silently altering what players see. Where current output
 * looks odd, it is pinned as-is and called out in a comment rather than corrected --
 * fixing behaviour inside a characterization suite would destroy the baseline it exists
 * to protect.
 */
public class PanelFormatCharacterizationTest
{
	// ---------------------------------------------------------------- formatGP

	@Test
	public void formatGpLeavesValuesUnderOneThousandBare()
	{
		assertEquals("0", PanelFormat.formatGP(0));
		assertEquals("1", PanelFormat.formatGP(1));
		assertEquals("999", PanelFormat.formatGP(999));
	}

	@Test
	public void formatGpAbbreviatesThousandsWithOneDecimal()
	{
		assertEquals("1.0K", PanelFormat.formatGP(1_000));
		assertEquals("1.5K", PanelFormat.formatGP(1_500));
		assertEquals("-1.5K", PanelFormat.formatGP(-1_500));
	}

	/**
	 * Pinned oddity: the K band runs to 999,999 rather than rolling into M at 1,000K,
	 * so a value one gp below a million renders as "1000.0K", not "1.0M". Players do
	 * see this. Changing it is a product decision, not a refactor.
	 */
	@Test
	public void formatGpRendersJustUnderOneMillionAsFourDigitK()
	{
		assertEquals("1000.0K", PanelFormat.formatGP(999_999));
		assertEquals("1.0M", PanelFormat.formatGP(1_000_000));
	}

	/**
	 * There is no B suffix -- ten billion renders as "10000.0M". Pinned so the absence
	 * is a deliberate, visible fact rather than something a future reader assumes.
	 */
	@Test
	public void formatGpHasNoBillionsSuffix()
	{
		assertEquals("10000.0M", PanelFormat.formatGP(10_000_000_000L));
		assertEquals("-10000.0M", PanelFormat.formatGP(-10_000_000_000L));
	}

	/**
	 * formatGP itself takes a long and handles values past Integer.MAX_VALUE correctly.
	 * The remaining risk tracked by #961 lived at the totals CALL SITES; formatProfitCostText
	 * (below) now takes long, and its caller widens margin*qty and buyPrice*qty to long before
	 * multiplying, so a large-quantity flip no longer overflows int into a garbage total.
	 */
	@Test
	public void formatGpHandlesValuesBeyondIntegerRange()
	{
		assertEquals("2147.5M", PanelFormat.formatGP(2_147_483_647L));
		assertEquals("2147.5M", PanelFormat.formatGP(2_147_483_648L));
	}

	/**
	 * Regression for #961: a profit/cost total above Integer.MAX_VALUE must render its true
	 * magnitude, not a saturated or int-overflowed value. Narrowing 3B to int would wrap to a
	 * negative number ("-1294.9M"); the long path renders the real figure.
	 */
	@Test
	public void formatProfitCostTextRendersTotalsBeyondIntegerRange()
	{
		assertEquals("Profit: 3000.0M | Cost: 5000.0M",
			PanelFormat.formatProfitCostText(3_000_000_000L, 5_000_000_000L));
	}

	@Test
	public void formatGpExactGroupsWithCommasAndKeepsSign()
	{
		assertEquals("0", PanelFormat.formatGPExact(0));
		assertEquals("1,000", PanelFormat.formatGPExact(1_000));
		assertEquals("-1,500", PanelFormat.formatGPExact(-1_500));
		assertEquals("2,147,483,648", PanelFormat.formatGPExact(2_147_483_648L));
	}

	// ------------------------------------------------------------- risk colour

	/**
	 * Boundaries are inclusive upper bounds: <=20 green, <=40 yellow-green, <=60 yellow,
	 * else red. Pinned at both sides of every boundary because an off-by-one here silently
	 * recolours risk for a whole band of items.
	 */
	@Test
	public void getRiskColorBandsAreInclusiveUpperBounds()
	{
		assertEquals(new Color(100, 255, 100), PanelFormat.getRiskColor(0));
		assertEquals(new Color(100, 255, 100), PanelFormat.getRiskColor(20));
		assertEquals(new Color(150, 255, 100), PanelFormat.getRiskColor(20.1));
		assertEquals(new Color(150, 255, 100), PanelFormat.getRiskColor(40));
		assertEquals(new Color(255, 255, 100), PanelFormat.getRiskColor(40.1));
		assertEquals(new Color(255, 255, 100), PanelFormat.getRiskColor(60));
		assertEquals(new Color(255, 100, 100), PanelFormat.getRiskColor(60.1));
		assertEquals(new Color(255, 100, 100), PanelFormat.getRiskColor(100));
	}

	@Test
	public void brightenColorClampsEachChannelAtMax()
	{
		assertEquals(new Color(110, 120, 130), PanelFormat.brightenColor(new Color(100, 110, 120), 10));
		assertEquals(new Color(255, 255, 255), PanelFormat.brightenColor(new Color(250, 250, 250), 50));
	}

	// -------------------------------------------------------------- escapeHtml

	@Test
	public void escapeHtmlEscapesAmpersandAnglesAndQuote()
	{
		assertEquals("a&amp;b&lt;c&gt;d&quot;e", PanelFormat.escapeHtml("a&b<c>d\"e"));
	}

	@Test
	public void escapeHtmlMapsNullToEmptyString()
	{
		assertEquals("", PanelFormat.escapeHtml(null));
	}

	/** Single quotes are deliberately NOT escaped; the HTML labels quote attributes with '. */
	@Test
	public void escapeHtmlLeavesSingleQuotesAlone()
	{
		assertEquals("it's", PanelFormat.escapeHtml("it's"));
	}

	// ------------------------------------------------------------- row strings

	@Test
	public void formatVolumeTextReportsNaForNonPositiveVolume()
	{
		assertEquals("Volume: N/A", PanelFormat.formatVolumeText(0));
		assertEquals("Volume: N/A", PanelFormat.formatVolumeText(-5));
	}

	@Test
	public void formatVolumeTextAbbreviatesAndSuffixesPerDay()
	{
		assertEquals("Volume: 1.5K/day", PanelFormat.formatVolumeText(1_500));
	}

	@Test
	public void formatRiskTextFallsBackOnNullScoreAndNullRating()
	{
		assertEquals("Risk: N/A", PanelFormat.formatRiskText(null, "Low"));
		assertEquals("Risk: 15 (Low)", PanelFormat.formatRiskText(15.0, "Low"));
		assertEquals("Risk: 15 (Unknown)", PanelFormat.formatRiskText(15.0, null));
	}

	@Test
	public void formatLiquidityTextFallsBackOnNullScore()
	{
		assertEquals("Liquidity: N/A", PanelFormat.formatLiquidityText(null, "High", 1.0));
		assertEquals("Liquidity: 9 (High) | 2.0K/hr", PanelFormat.formatLiquidityText(9.0, "High", 2000.0));
	}

	/**
	 * Pinned wart: a null volumePerHour still emits the " | " separator with nothing after
	 * it, so the row ends in a dangling pipe. Cosmetic, and visible to players.
	 */
	@Test
	public void formatLiquidityTextLeavesDanglingSeparatorWhenVolumeIsNull()
	{
		assertEquals("Liquidity: 9 (High) | ", PanelFormat.formatLiquidityText(9.0, "High", null));
	}

	/**
	 * Profit switches representation at 1,000 by absolute value -- under it the exact
	 * comma-grouped figure, at or over it the abbreviated one. Cost is always abbreviated.
	 */
	@Test
	public void formatProfitCostTextSwitchesProfitFormatAtOneThousand()
	{
		assertEquals("Profit: 500 | Cost: 10.0K", PanelFormat.formatProfitCostText(500, 10_000));
		assertEquals("Profit: 5.0K | Cost: 10.0K", PanelFormat.formatProfitCostText(5_000, 10_000));
	}

	/** The 1,000 threshold is on the absolute value, so large losses abbreviate too. */
	@Test
	public void formatProfitCostTextAbbreviatesLargeNegativeProfit()
	{
		assertEquals("Profit: -5.0K | Cost: 100", PanelFormat.formatProfitCostText(-5_000, 100));
	}

	// -------------------------------------------------------------- html rows

	@Test
	public void qtyHtmlWrapsInDimGrayFont()
	{
		assertEquals("<html><font color='#9aa0a8'>Qty: 3/10</font></html>", PanelFormat.qtyHtml(3, 10));
	}

	@Test
	public void taxHtmlAbbreviatesAndWrapsInDimGrayFont()
	{
		assertEquals("<html><font color='#9aa0a8'>Tax: 1.2K</font></html>", PanelFormat.taxHtml(1234));
	}

	@Test
	public void buySellHtmlRendersNaWhenSellPriceMissing()
	{
		assertEquals("<html>Buy: <b><font color='#6fb1ff'>100</font></b> | Sell: N/A</html>",
			PanelFormat.buySellHtml(100, null));
	}

	@Test
	public void buySellHtmlColoursBuyBlueAndSellOrange()
	{
		assertEquals("<html>Buy: <b><font color='#6fb1ff'>100</font></b> "
				+ "| Sell: <b><font color='#ffab54'>200</font></b></html>",
			PanelFormat.buySellHtml(100, 200));
	}
}
