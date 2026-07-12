package com.flipsmart.ui.panel;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class PanelFormatActiveFlipsTest
{
	@Test
	public void livePriceColoursLowBlueHighOrangeWithCommas()
	{
		assertEquals(
			"<html>Live Price: <font color='#6fb1ff'>1,500,000</font> | <font color='#ffab54'>2,000,000</font></html>",
			PanelFormat.livePriceHtml(1_500_000, 2_000_000));
	}

	@Test
	public void liveMarginGreenOnProfitRedOnLossNoPlusSign()
	{
		assertEquals(
			"<html>Live Margin: <font color='#5ee66e'>60.0K (1.5% ROI)</font></html>",
			PanelFormat.liveMarginHtml(60_000, 1.5));
		assertEquals(
			"<html>Live Margin: <font color='#ff6b6b'>-4.0K (-2.3% ROI)</font></html>",
			PanelFormat.liveMarginHtml(-4_000, -2.31));
	}

	@Test
	public void currentProfitGoldLabelValueColouredBySign()
	{
		assertEquals(
			"<html><font color='#ffce54'>Current Profit: </font><font color='#5ee66e'>35.0K</font></html>",
			PanelFormat.currentProfitHtml(35_000L));
		assertEquals(
			"<html><font color='#ffce54'>Current Profit: </font><font color='#ff6b6b'>-12.0K</font></html>",
			PanelFormat.currentProfitHtml(-12_000L));
	}

	@Test
	public void potentialMutedLabelValueColouredBySign()
	{
		assertEquals(
			"<html><font color='#9aa0a8'>Potential: </font><font color='#5ee66e'>539.0K</font></html>",
			PanelFormat.potentialHtml(539_000L));
	}
}
