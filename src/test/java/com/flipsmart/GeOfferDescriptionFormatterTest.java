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

	// ---------------------------------------------------------------------
	// Buy-limit cooldown line (#1115)
	// ---------------------------------------------------------------------

	private static final long MINUTE_MS = 60_000L;
	private static final long HOUR_MS = 60 * MINUTE_MS;

	@Test
	public void buyDescriptionShowsCooldownWhenTheItemWasBoughtInsideTheWindow()
	{
		String out = GeOfferDescriptionFormatter.formatBuyDescription(
			1000, 15, 500_000, 3 * HOUR_MS + 42 * MINUTE_MS);

		assertTrue("cooldown line should render H:mm remaining",
			out.contains("Limit resets in: </col><col=ffffff>3:42</col>"));
		// AC2 — the cooldown sits underneath the buy limit and wiki insta-buy lines.
		assertTrue("cooldown must be the last line",
			out.indexOf("Limit resets in") > out.indexOf("Wiki insta-buy"));
		assertTrue("wiki insta-buy must still follow the buy limit",
			out.indexOf("Wiki insta-buy") > out.indexOf("Buy limit"));
	}

	@Test
	public void buyDescriptionOmitsCooldownWhenNothingWasBoughtInTheWindow()
	{
		// AC5 — no purchase inside 4h means the caller passes null and no line renders.
		String out = GeOfferDescriptionFormatter.formatBuyDescription(1000, 15, 500_000, null);

		assertTrue("no cooldown line without a recorded purchase",
			!out.contains("Limit resets in"));
		assertTrue("the other lines are unaffected", out.contains("Buy limit"));
	}

	@Test
	public void buyDescriptionOmitsCooldownOnceTheWindowHasElapsed()
	{
		// A stale/expired reset time must not render "0:00" or a negative duration.
		assertTrue("elapsed window renders nothing",
			!GeOfferDescriptionFormatter.formatBuyDescription(1000, 15, 500_000, 0L)
				.contains("Limit resets in"));
		assertTrue("negative remaining renders nothing",
			!GeOfferDescriptionFormatter.formatBuyDescription(1000, 15, 500_000, -5_000L)
				.contains("Limit resets in"));
	}

	@Test
	public void cooldownMinutesAreZeroPaddedAndTruncatedNotRounded()
	{
		// Matches DurationFormatUtils "H:mm": 1h05m, and 59s of a minute is dropped.
		assertEquals("<col=ffb83f>Limit resets in: </col><col=ffffff>1:05</col>",
			GeOfferDescriptionFormatter.formatLimitResetLine(HOUR_MS + 5 * MINUTE_MS + 59_000L));
		assertEquals("sub-minute remainder truncates to 0:00",
			"<col=ffb83f>Limit resets in: </col><col=ffffff>0:00</col>",
			GeOfferDescriptionFormatter.formatLimitResetLine(30_000L));
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
