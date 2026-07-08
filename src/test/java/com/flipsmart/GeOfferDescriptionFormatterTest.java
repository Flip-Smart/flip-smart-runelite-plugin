package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the sell-offer description math, in particular that the GE tax-exempt
 * item list is honoured on the offer-setup screen (issue #904 AC2). A previous
 * version used the item-less {@link com.flipsmart.util.GeTax} overloads here,
 * so exempt-list items (bonds, darts, arrows, cooked food, …) were shown with
 * tax and an under-stated profit.
 */
public class GeOfferDescriptionFormatterTest
{
	private static final int ABYSSAL_WHIP_ID = 4151;       // taxable
	private static final int OLD_SCHOOL_BOND_ID = 13190;   // tax-exempt list

	@Test
	public void taxableItemAppliesTaxToBreakevenAndProfit()
	{
		// Buy 100, sell 200, qty 1: tax = floor(200 * 0.02) = 4/item.
		String out = GeOfferDescriptionFormatter.formatSellDescription(
			ABYSSAL_WHIP_ID, 100, 200, 1);

		assertTrue("taxable item should show non-zero tax", out.contains("Tax applied: </col>4 gp"));
		// Breakeven must sit above the buy price to cover tax.
		int breakeven = GeOfferDescriptionFormatter.calculateBreakevenPrice(ABYSSAL_WHIP_ID, 100);
		assertTrue("taxable breakeven should exceed buy price", breakeven > 100);
		// Profit = 200 - 100 - 4 = 96 (post-tax).
		assertTrue("taxable profit is post-tax", out.contains("+96 gp"));
	}

	@Test
	public void exemptListItemShowsNoTaxAndPreTaxProfit()
	{
		// Old school bond is on the exempt list; at any price it is never taxed.
		String out = GeOfferDescriptionFormatter.formatSellDescription(
			OLD_SCHOOL_BOND_ID, 8_000_000, 8_100_000, 1);

		assertEquals("exempt-list item is never taxed",
			0, GeOfferDescriptionFormatter.calculateTaxPerItem(OLD_SCHOOL_BOND_ID, 8_100_000));
		assertTrue("tax line reads zero for exempt item", out.contains("Tax applied: </col>0 gp"));
		// Breakeven for an exempt item is simply the buy price (no tax to cover).
		assertEquals(8_000_000,
			GeOfferDescriptionFormatter.calculateBreakevenPrice(OLD_SCHOOL_BOND_ID, 8_000_000));
		// Profit is the raw margin: 8,100,000 - 8,000,000 = 100,000 (pre-tax).
		assertTrue("exempt profit is pre-tax margin", out.contains("+100,000 gp"));
	}
}
