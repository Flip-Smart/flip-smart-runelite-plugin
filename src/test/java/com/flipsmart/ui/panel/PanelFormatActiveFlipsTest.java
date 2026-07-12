package com.flipsmart.ui.panel;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class PanelFormatActiveFlipsTest
{
	@Test
	public void marketBuySellShowsLowThenHighWithCommas()
	{
		assertEquals("Buy: 173 | Sell: 179", PanelFormat.formatMarketBuySellText(173, 179));
		assertEquals("Buy: 1,234 | Sell: 5,678", PanelFormat.formatMarketBuySellText(1234, 5678));
	}

	@Test
	public void currentMarginIsSignedGpWithRoi()
	{
		assertEquals("Current Margin: +6 gp (3.5% ROI)", PanelFormat.formatCurrentMarginText(6, 3.468));
		assertEquals("Current Margin: -4 gp (-2.3% ROI)", PanelFormat.formatCurrentMarginText(-4, -2.31));
		assertEquals("Current Margin: 0 gp (0.0% ROI)", PanelFormat.formatCurrentMarginText(0, 0.0));
	}

	@Test
	public void profitCombinedShowsRealizedSignedAndPotential()
	{
		assertEquals("Current Profit: +35.0K | Potential: 539.0K",
			PanelFormat.formatProfitCombinedText(35_000L, 539_000L));
		assertEquals("Current Profit: +1.2M | Potential: 180.0K",
			PanelFormat.formatProfitCombinedText(1_200_000L, 180_000L));
		assertEquals("Current Profit: -12.0K | Potential: -5.0K",
			PanelFormat.formatProfitCombinedText(-12_000L, -5_000L));
		assertEquals("Current Profit: 0 | Potential: 0",
			PanelFormat.formatProfitCombinedText(0L, 0L));
	}

	@Test
	public void taxSplitPerItemExactTotalShort()
	{
		assertEquals("Tax: 2 | 60.0K", PanelFormat.formatTaxSplitText(2, 60_000L));
		assertEquals("Tax: 1,000 | 5.0M", PanelFormat.formatTaxSplitText(1000, 5_000_000L));
	}
}
