package com.flipsmart;
import com.flipsmart.util.GeTax;
import com.flipsmart.util.GpUtils;

import java.util.Locale;

/**
 * Pure-function formatters for the GE buy/sell window description replacement
 * (issue #665). Kept separate from {@link GeOfferDescriptionService} so the
 * string building can be unit-tested without RuneLite client state.
 *
 * <p>GE tax and breakeven math is delegated to {@link GeTax} (itself free of
 * plugin/runelite dependencies) so the rate/cap/threshold live in one place.</p>
 */
public final class GeOfferDescriptionFormatter
{
	// Color codes used inline in the returned RuneScript strings.
	// Format is the standard widget hex tag, e.g. <col=00ff00>green</col>.
	static final String COLOR_GREEN = "00ff00";
	static final String COLOR_RED = "ff4040";
	static final String COLOR_WHITE = "ffffff";
	static final String COLOR_LABEL = "ffb83f";  // GE description "highlight" amber

	private GeOfferDescriptionFormatter()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Build the description text for a buy offer.
	 *
	 * <p>Original AC2 just specified Daily Volume; the buy window has been
	 * extended to also surface buy limit and current wiki insta-buy price
	 * because those are the other two decision-relevant numbers a player
	 * looks for at trade-construction time. Each line is gracefully omitted
	 * when the underlying datum is unavailable (limit &le; 0, price &le; 0).</p>
	 *
	 * @param dailyVolume  Daily traded volume, or {@code null} when no data.
	 * @param buyLimit     4h GE buy limit from {@code ItemStats.getGeLimit()},
	 *                     or {@code null}/0 when unknown — line omitted then.
	 * @param wikiInstaBuy Wiki high price (what buyers pay to insta-buy right
	 *                     now — the price-to-match for fast fills), or
	 *                     {@code null}/0 when unknown — line omitted then.
	 * @return RuneScript-formatted description string. Lines separated by
	 *         {@code <br>}. Daily Volume line is always present (rendering
	 *         "N/A" when needed); the other two lines are conditionally
	 *         appended.
	 */
	public static String formatBuyDescription(Integer dailyVolume, Integer buyLimit, Integer wikiInstaBuy)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(formatDailyVolumeLine(dailyVolume));

		if (buyLimit != null && buyLimit > 0)
		{
			sb.append("<br>").append(formatBuyLimitLine(buyLimit));
		}

		if (wikiInstaBuy != null && wikiInstaBuy > 0)
		{
			sb.append("<br>").append(formatWikiInstaBuyLine(wikiInstaBuy));
		}

		return sb.toString();
	}

	static String formatDailyVolumeLine(Integer dailyVolume)
	{
		String volumeStr = (dailyVolume == null) ? "N/A" : formatExact(dailyVolume);
		return colorTag(COLOR_LABEL) + "Daily Volume: </col>" + volumeStr;
	}

	static String formatBuyLimitLine(int buyLimit)
	{
		return colorTag(COLOR_LABEL) + "Buy limit: </col>" + formatExact(buyLimit) + " / 4h";
	}

	static String formatWikiInstaBuyLine(int wikiInstaBuy)
	{
		return colorTag(COLOR_LABEL) + "Wiki insta-buy: </col>"
			+ colorTag(COLOR_WHITE) + formatExact(wikiInstaBuy) + " gp</col>";
	}

	/**
	 * Build the description text for a sell offer (AC3 + AC4 + AC5 + AC6).
	 * Recomputed every time the player changes the entered price or quantity;
	 * the RuneLite-injected script that fires geSellExamineText is responsible
	 * for invoking this on each rebuild.
	 *
	 * @param itemId           The item being sold, needed to honour the GE
	 *                         tax-exempt list (bonds, darts, etc.) so exempt
	 *                         items show pre-tax breakeven and profit.
	 * @param recordedBuyPrice Player's recorded average buy price for the item,
	 *                         or {@code null} if no buy history exists.
	 * @param listedSellPrice  Currently entered sell price per item.
	 * @param quantity         Currently entered quantity.
	 * @return RuneScript-formatted description string with breakeven, tax, and
	 *         color-coded profit lines separated by {@code <br>}.
	 */
	public static String formatSellDescription(
		int itemId,
		Integer recordedBuyPrice,
		int listedSellPrice,
		int quantity)
	{
		StringBuilder sb = new StringBuilder();

		// AC3 — Breakeven (fixed, depends only on recorded buy price + tax)
		sb.append(formatBreakevenLine(itemId, recordedBuyPrice)).append("<br>");

		// AC4 — Tax applied (dynamic with entered price+qty). Item-aware so
		// tax-exempt-list items show 0 tax regardless of price.
		int taxPerItem = calculateTaxPerItem(itemId, listedSellPrice);
		sb.append(formatTaxLine(taxPerItem, quantity)).append("<br>");

		// AC5 + AC6 — Your profit (dynamic with entered price+qty, color-coded)
		sb.append(formatProfitLine(recordedBuyPrice, listedSellPrice, taxPerItem, quantity));

		return sb.toString();
	}

	static String formatBreakevenLine(int itemId, Integer recordedBuyPrice)
	{
		String label = colorTag(COLOR_LABEL) + "Breakeven: </col>";
		if (recordedBuyPrice == null)
		{
			return label + "?";
		}
		int breakeven = calculateBreakevenPrice(itemId, recordedBuyPrice);
		return label + colorTag(COLOR_WHITE) + formatExact(breakeven) + " gp</col>";
	}

	static String formatTaxLine(int taxPerItem, int quantity)
	{
		String label = colorTag(COLOR_LABEL) + "Tax applied: </col>";
		if (quantity <= 1)
		{
			return label + formatExact(taxPerItem) + " gp";
		}
		long totalTax = (long) taxPerItem * (long) quantity;
		return label + formatShortLower(totalTax) + " (" + formatShortLower(taxPerItem) + " per item)";
	}

	static String formatProfitLine(Integer recordedBuyPrice, int listedSellPrice, int taxPerItem, int quantity)
	{
		String label = colorTag(COLOR_LABEL) + "Your profit: </col>";

		if (recordedBuyPrice == null)
		{
			// AC5: profit shown as ? when buy history is unavailable
			return label + colorTag(COLOR_WHITE) + "?</col>";
		}

		int profitPerItem = listedSellPrice - recordedBuyPrice - taxPerItem;
		long totalProfit = (long) profitPerItem * (long) Math.max(quantity, 1);
		String color = colorForProfit(totalProfit);
		String sign = totalProfit > 0 ? "+" : "";

		StringBuilder sb = new StringBuilder(label);
		sb.append(colorTag(color));
		sb.append(sign).append(formatExact(totalProfit)).append(" gp");
		if (quantity > 1)
		{
			String perItemSign = profitPerItem > 0 ? "+" : "";
			sb.append(" (").append(perItemSign).append(formatShortLower(profitPerItem)).append(" per item)");
		}
		sb.append("</col>");
		return sb.toString();
	}

	static int calculateBreakevenPrice(int itemId, int recordedBuyPrice)
	{
		return GeTax.breakevenSellPrice(itemId, recordedBuyPrice);
	}

	static int calculateTaxPerItem(int itemId, int sellPrice)
	{
		return GeTax.taxFor(itemId, sellPrice);
	}

	private static String colorForProfit(long totalProfit)
	{
		if (totalProfit > 0) return COLOR_GREEN;
		if (totalProfit < 0) return COLOR_RED;
		return COLOR_WHITE;
	}

	private static String colorTag(String hex)
	{
		return "<col=" + hex + ">";
	}

	/** Comma-separated exact gp count: {@code 1234567} -> {@code "1,234,567"}. */
	static String formatExact(long value)
	{
		return String.format(Locale.ROOT, "%,d", value);
	}

	/**
	 * Lowercase k/M shorthand matching the AC examples ({@code 100k}, {@code 1.5M}).
	 * Distinct from {@link GpUtils#formatGP} which uses uppercase K.
	 */
	static String formatShortLower(long value)
	{
		long abs = Math.abs(value);
		String sign = value < 0 ? "-" : "";
		if (abs >= 1_000_000)
		{
			return sign + stripTrailingZero(String.format(Locale.ROOT, "%.1f", abs / 1_000_000.0)) + "M";
		}
		if (abs >= 1_000)
		{
			return sign + stripTrailingZero(String.format(Locale.ROOT, "%.1f", abs / 1_000.0)) + "k";
		}
		return Long.toString(value);
	}

	private static String stripTrailingZero(String formatted)
	{
		// "1.0" -> "1", "1.5" -> "1.5"
		if (formatted.endsWith(".0"))
		{
			return formatted.substring(0, formatted.length() - 2);
		}
		return formatted;
	}
}
