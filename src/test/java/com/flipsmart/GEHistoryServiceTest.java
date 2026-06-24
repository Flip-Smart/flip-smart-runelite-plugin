package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link GEHistoryService}'s widget-text parsing helpers.
 * <p>
 * The package-private {@code parsePerItemPriceFromText} and
 * {@code parseLeadingTotalFromText} entry points let us exercise the
 * parsing logic against raw widget-text shapes without mocking the
 * RuneLite {@code Widget} object.
 * <p>
 * The regression case under {@link #parseEachWidgetMissingSeparatorYieldsTrailingQuantity}
 * locks in the fix for Flip-Smart/flip-smart#689: pre-fix, widget text of
 * the shape {@code "<col=ffb83f>1,149 each</col>"} produced 831,149 because
 * the parser sliced from the {@code '='} inside the color attribute and
 * the orphaned {@code "ffb83f>"} fragment leaked its hex digits into the
 * digit-greedy parse. Stripping HTML tags first removes that failure mode.
 */
public class GEHistoryServiceTest
{
	// Standard yellow GE text color in OSRS interfaces; the hex digits
	// "83" here are the ones that previously bled into the parsed price.
	private static final String GE_YELLOW = "<col=ffb83f>";

	// ----- parsePerItemPriceFromText ----------------------------------

	@Test
	public void parseWellFormedPriceTextReturnsPerItemValue()
	{
		// The shape RuneLite emits for multi-quantity rows: a coins total
		// inside a color span, a <br>, then "= N each" with the per-item
		// price after the '=' sign.
		String text = GE_YELLOW + "1,571,832 coins</col><br>= 1,341 each";
		assertEquals(1341, GEHistoryService.parsePerItemPriceFromText(text));
	}

	@Test
	public void parseEachWidgetMissingSeparatorYieldsTrailingQuantity()
	{
		// REGRESSION GUARD for Flip-Smart/flip-smart#689.
		// When the matched "each" widget contains only the trailing
		// quantity inside a color tag (no plain-text '=' separator),
		// the parser must NOT leak the "83" hex digits out of the
		// color attribute into the parsed number. Pre-fix this
		// returned 831_149; the fix returns 1_149.
		String text = GE_YELLOW + "1,149 each</col>";
		assertEquals(1149, GEHistoryService.parsePerItemPriceFromText(text));
	}

	@Test
	public void parseProductionCorruptionShapeForBonesToPeaches()
	{
		// Synthetic reproduction of the exact prod corruption — a
		// 1,341 gp/unit Bones-to-peaches sell that was filed as
		// 831,149 gp/unit. Confirms the fix produces 1,341 regardless
		// of whether the widget text wraps the per-item value alone.
		String text = GE_YELLOW + "1,341 each</col>";
		assertEquals(1341, GEHistoryService.parsePerItemPriceFromText(text));
	}

	@Test
	public void parseHandlesNestedColorSpans()
	{
		// Defensive: a row whose total and "= each" segments live in
		// two separate color spans must still resolve to the per-item
		// value after the last '=' in the cleaned text.
		String text = GE_YELLOW + "1,571,832 coins</col><br>"
			+ "<col=ff981f>= 1,341 each</col>";
		assertEquals(1341, GEHistoryService.parsePerItemPriceFromText(text));
	}

	@Test
	public void parseEmptyOrNullPriceTextReturnsMinusOne()
	{
		assertEquals(-1, GEHistoryService.parsePerItemPriceFromText(null));
		assertEquals(-1, GEHistoryService.parsePerItemPriceFromText(""));
	}

	// ----- parseLeadingTotalFromText (qty=1 fallback path) ------------

	@Test
	public void parseLeadingTotalIgnoresParenthesizedTaxBreakdown()
	{
		// Used for qty=1 history rows where the per-item price equals
		// the displayed total. The parenthesized tax detail must NOT
		// be concatenated into the parse.
		String text = GE_YELLOW + "1,540,809 coins (1,571,832 - 31,023)</col>";
		assertEquals(1_540_809, GEHistoryService.parseLeadingTotalFromText(text));
	}

	@Test
	public void parseLeadingTotalHandlesNoParenthesizedTail()
	{
		// Many qty=1 rows don't render a tax breakdown at all.
		String text = GE_YELLOW + "2,500,000 coins</col>";
		assertEquals(2_500_000, GEHistoryService.parseLeadingTotalFromText(text));
	}

	@Test
	public void parseLeadingTotalEmptyOrNullReturnsMinusOne()
	{
		assertEquals(-1, GEHistoryService.parseLeadingTotalFromText(null));
		assertEquals(-1, GEHistoryService.parseLeadingTotalFromText(""));
	}

	// ----- matchOfferId (#759 offer_id linkage) -----------------------

	private static OfferRecord offer(long offerId, int itemId, boolean buy, int total, int price)
	{
		return OfferRecord.newOffer(offerId, 0, itemId, "i" + itemId, buy, total, price, 1L);
	}

	@Test
	public void matchOfferId_uniqueMatchReturnsOfferId()
	{
		List<OfferRecord> candidates = Arrays.asList(
			offer(42, 28924, false, 30000, 384),
			offer(7, 4151, true, 5, 2_000_000));
		assertEquals(Long.valueOf(42), GEHistoryService.matchOfferId(candidates, 28924, false, 384));
	}

	@Test
	public void matchOfferId_ambiguousSameItemDirectionPriceReturnsNull()
	{
		List<OfferRecord> candidates = Arrays.asList(
			offer(42, 28924, false, 30000, 384),
			offer(43, 28924, false, 20000, 384));
		assertNull(GEHistoryService.matchOfferId(candidates, 28924, false, 384));
	}

	@Test
	public void matchOfferId_noMatchOnPriceOrDirectionReturnsNull()
	{
		List<OfferRecord> candidates = Collections.singletonList(offer(42, 28924, false, 30000, 384));
		assertNull(GEHistoryService.matchOfferId(candidates, 28924, false, 999));
		assertNull(GEHistoryService.matchOfferId(candidates, 28924, true, 384));
	}

	@Test
	public void matchOfferId_sameOfferAcrossCollectionsIsNotAmbiguous()
	{
		OfferRecord same = offer(42, 28924, false, 30000, 384);
		assertEquals(Long.valueOf(42), GEHistoryService.matchOfferId(Arrays.asList(same, same), 28924, false, 384));
	}
}
