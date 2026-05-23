package com.flipsmart;

/**
 * Pure-function formatters for the GE buy/sell window description replacement
 * (issue #665). Kept separate from {@link GeOfferDescriptionService} so the
 * string building can be unit-tested without RuneLite client state.
 *
 * <p>GE tax is computed inline (2%, capped at 5,000,000gp, exempt below 50gp)
 * to keep this class free of plugin/runelite dependencies — the same constants
 * are enforced backend-side.</p>
 */
public final class GeOfferDescriptionFormatter
{
	// Color codes used inline in the returned RuneScript strings.
	// Format is the standard widget hex tag, e.g. <col=00ff00>green</col>.
	static final String COLOR_GREEN = "00ff00";
	static final String COLOR_RED = "ff4040";
	static final String COLOR_WHITE = "ffffff";
	static final String COLOR_LABEL = "ffb83f";  // GE description "highlight" amber

	private static final int GE_TAX_EXEMPT_THRESHOLD = 50;
	private static final double GE_TAX_RATE = 0.02;
	private static final int GE_TAX_CAP = 5_000_000;

	private GeOfferDescriptionFormatter()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Build the description text for a buy offer (AC2).
	 *
	 * @param dailyVolume Daily traded volume, or {@code null} when no data.
	 * @return RuneScript-formatted description string, single line.
	 */
	public static String formatBuyDescription(Integer dailyVolume)
	{
		String volumeStr = (dailyVolume == null) ? "N/A" : formatExact(dailyVolume);
		return colorTag(COLOR_LABEL) + "Daily Volume: </col>" + volumeStr;
	}

	/**
	 * Build the description text for a sell offer (AC3 + AC4 + AC5 + AC6).
	 * Recomputed every time the player changes the entered price or quantity;
	 * the RuneLite-injected script that fires geSellExamineText is responsible
	 * for invoking this on each rebuild.
	 *
	 * @param recordedBuyPrice Player's recorded average buy price for the item,
	 *                         or {@code null} if no buy history exists.
	 * @param listedSellPrice  Currently entered sell price per item.
	 * @param quantity         Currently entered quantity.
	 * @return RuneScript-formatted description string with breakeven, tax, and
	 *         color-coded profit lines separated by {@code <br>}.
	 */
	public static String formatSellDescription(
		Integer recordedBuyPrice,
		int listedSellPrice,
		int quantity)
	{
		StringBuilder sb = new StringBuilder();

		// AC3 — Breakeven (fixed, depends only on recorded buy price + tax)
		sb.append(formatBreakevenLine(recordedBuyPrice));
		sb.append("<br>");

		// AC4 — Tax applied (dynamic with entered price+qty)
		int taxPerItem = calculateTaxPerItem(listedSellPrice);
		sb.append(formatTaxLine(taxPerItem, quantity));
		sb.append("<br>");

		// AC5 + AC6 — Your profit (dynamic with entered price+qty, color-coded)
		sb.append(formatProfitLine(recordedBuyPrice, listedSellPrice, taxPerItem, quantity));

		return sb.toString();
	}

	static String formatBreakevenLine(Integer recordedBuyPrice)
	{
		String label = colorTag(COLOR_LABEL) + "Breakeven: </col>";
		if (recordedBuyPrice == null)
		{
			return label + "?";
		}
		int breakeven = calculateBreakevenPrice(recordedBuyPrice);
		return label + formatExact(breakeven) + " gp";
	}

	static String formatTaxLine(int taxPerItem, int quantity)
	{
		String label = colorTag(COLOR_LABEL) + "Tax applied: </col>";
		if (quantity <= 1)
		{
			return label + formatExact(taxPerItem) + "gp";
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
		sb.append(sign).append(formatExact(totalProfit)).append("gp");
		if (quantity > 1)
		{
			String perItemSign = profitPerItem > 0 ? "+" : "";
			sb.append(" (").append(perItemSign).append(formatShortLower(profitPerItem)).append(" per item)");
		}
		sb.append("</col>");
		return sb.toString();
	}

	static int calculateBreakevenPrice(int recordedBuyPrice)
	{
		if (recordedBuyPrice <= GE_TAX_EXEMPT_THRESHOLD)
		{
			return recordedBuyPrice;
		}
		// Smallest sell price S such that S - floor(S * 0.02) >= recordedBuyPrice.
		// 1) Start from the closed-form estimate (overshoots by 1 due to ceiling).
		// 2) Walk up if the cap region or floor-truncation leaves us short.
		// 3) Walk down to guarantee minimality (the closed form typically yields
		//    S=103 for buy=100 when S=102 already satisfies — without this step
		//    we'd over-quote the breakeven by one gp).
		int candidate = (int) Math.ceil(recordedBuyPrice / (1.0 - GE_TAX_RATE));
		while (candidate - calculateTaxPerItem(candidate) < recordedBuyPrice)
		{
			candidate++;
		}
		while (candidate > recordedBuyPrice
			&& (candidate - 1) - calculateTaxPerItem(candidate - 1) >= recordedBuyPrice)
		{
			candidate--;
		}
		return candidate;
	}

	static int calculateTaxPerItem(int sellPrice)
	{
		if (sellPrice <= GE_TAX_EXEMPT_THRESHOLD)
		{
			return 0;
		}
		return Math.min((int) (sellPrice * GE_TAX_RATE), GE_TAX_CAP);
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
		return String.format("%,d", value);
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
			return sign + stripTrailingZero(String.format("%.1f", abs / 1_000_000.0)) + "M";
		}
		if (abs >= 1_000)
		{
			return sign + stripTrailingZero(String.format("%.1f", abs / 1_000.0)) + "k";
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
