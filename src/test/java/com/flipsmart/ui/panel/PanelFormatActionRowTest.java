package com.flipsmart.ui.panel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Action row tells the player which side of a flip a card represents. It has
 * to reuse the two colours the price row already trains them on — blue for the
 * buy side, orange for the sell side — so a wrong hex here is a real defect
 * rather than a cosmetic one.
 */
public class PanelFormatActionRowTest
{
	private static final String BLUE = "#6fb1ff";
	private static final String ORANGE = "#ffab54";

	@Test
	public void buyingRowIsBoldAndUsesTheBuySideBlue()
	{
		String out = PanelFormat.actionBuyingHtml();

		assertTrue(out, out.contains("Action: "));
		assertTrue(out, out.contains("<b>"));
		assertTrue("expected the buy-side blue in " + out, out.contains(BLUE));
		assertTrue("the coloured span must be the action word: " + out,
			out.contains(">Buying<"));
	}

	@Test
	public void sellingRowIsBoldAndUsesTheSellSideOrange()
	{
		String out = PanelFormat.actionSellingHtml();

		assertTrue(out, out.contains("Action: "));
		assertTrue(out, out.contains("<b>"));
		assertTrue("expected the sell-side orange in " + out, out.contains(ORANGE));
		assertTrue("the coloured span must be the action word: " + out,
			out.contains(">Selling<"));
	}

	@Test
	public void theTwoSidesNeverRenderTheSameColour()
	{
		assertTrue(PanelFormat.actionBuyingHtml().contains(BLUE));
		assertTrue(PanelFormat.actionSellingHtml().contains(ORANGE));
		assertEquals(false, PanelFormat.actionBuyingHtml().contains(ORANGE));
		assertEquals(false, PanelFormat.actionSellingHtml().contains(BLUE));
	}

	@Test
	public void theLabelStaysPlainSoOnlyTheActionWordIsEmphasised()
	{
		// "Action: " must sit outside the <b>, matching how Live Price styles its label.
		String out = PanelFormat.actionSellingHtml();
		assertTrue(out, out.indexOf("Action: ") < out.indexOf("<b>"));
	}
}
