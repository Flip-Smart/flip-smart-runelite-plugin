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
	public void currentProfitIsSignedShortForm()
	{
		assertEquals("Current Profit: +1.2M", PanelFormat.formatCurrentProfitText(1_200_000L));
		assertEquals("Current Profit: -500.0K", PanelFormat.formatCurrentProfitText(-500_000L));
		assertEquals("Current Profit: 0", PanelFormat.formatCurrentProfitText(0L));
	}

	@Test
	public void profitPotentialProfitSignedCostUnsigned()
	{
		assertEquals("Profit Potential: 180.0K | Cost: 3.6M", PanelFormat.formatProfitPotentialText(180_000L, 3_600_000L));
		assertEquals("Profit Potential: -12.0K | Cost: 1.0M", PanelFormat.formatProfitPotentialText(-12_000L, 1_000_000L));
	}

	@Test
	public void taxSplitPerItemExactTotalShort()
	{
		assertEquals("Tax: 2 | 60.0K", PanelFormat.formatTaxSplitText(2, 60_000L));
		assertEquals("Tax: 1,000 | 5.0M", PanelFormat.formatTaxSplitText(1000, 5_000_000L));
	}
}
