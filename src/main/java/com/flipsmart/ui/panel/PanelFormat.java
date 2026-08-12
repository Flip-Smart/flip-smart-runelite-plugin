package com.flipsmart.ui.panel;

import com.flipsmart.util.GpUtils;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import net.runelite.client.util.ImageUtil;
import javax.swing.JPanel;

/**
 * Pure presentation helpers for the flip panel cards: GP/text formatting,
 * risk colours, colour math and the small Java2D icon glyphs. No Swing widget
 * state, no panel fields - everything is a static function of its inputs.
 */
public final class PanelFormat
{
	private static final Color COLOR_PROFIT_GREEN = new Color(100, 255, 100);
	private static final Color COLOR_YELLOW = new Color(255, 255, 100);
	private static final Color COLOR_LOSS_RED = new Color(255, 100, 100);

	private static final String FORMAT_PROFIT_COST = "Profit: %s | Cost: %s";
	private static final String FORMAT_MARGIN_ROI = "Margin: %s (%.1f%% ROI)";
	private static final String FORMAT_MARGIN_ROI_LOSS = "Margin: %s (%.1f%% ROI) - Loss";
	private static final String FORMAT_LIQUIDITY = "Liquidity: %.0f (%s) | %s";
	private static final String FORMAT_VOLUME = "Volume: %s/day";
	private static final String FORMAT_RISK = "Risk: %.0f (%s)";
	// Value colours for the active-flip card HTML rows (hex, no leading #).
	private static final String HEX_PRICE_LOW = "6fb1ff";    // market low (buy side)
	private static final String HEX_PRICE_HIGH = "ffab54";   // market high (sell side)
	private static final String HEX_PROFIT = "5ee66e";       // green: profit
	private static final String HEX_LOSS = "ff6b6b";         // red: loss
	private static final String HEX_PROFIT_LABEL = "ffce54"; // gold: "Current Profit" label
	private static final String HEX_MUTED = "9aa0a8";        // gray: secondary label
	private static final String UNKNOWN_RATING = "Unknown";
	private static final String LIQUIDITY_NA = "Liquidity: N/A";
	private static final String VOLUME_NA = "Volume: N/A";
	private static final String RISK_NA = "Risk: N/A";

	private PanelFormat()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Format GP amount for display
	 */
	public static String formatGP(long amount)
	{
		return GpUtils.formatGPSigned(amount);
	}

	/**
	 * Format GP amount with commas for exact input (e.g., "1,234,567")
	 */
	public static String formatGPExact(long amount)
	{
		return GpUtils.formatGPExact(amount);
	}

	/**
	 * Load a UI icon from resources. These were previously drawn with Graphics2D; the PNGs
	 * are captures of that exact output, so swapping to assets changed no pixels while
	 * removing the drawing code from the plugin-hub token budget (resources are not counted).
	 */
	private static final Map<String, BufferedImage> ICON_CACHE = new ConcurrentHashMap<>();

	public static BufferedImage icon(String name)
	{
		// Cached: hoverSwap invokes its supplier on every hover event, so an uncached
		// lookup would decode the PNG on the EDT each time the pointer crosses an icon.
		return ICON_CACHE.computeIfAbsent(name,
			key -> ImageUtil.loadImageResource(PanelFormat.class, "/icons/" + key + ".png"));
	}

	public static Color getRiskColor(double score)
	{
		if (score <= 20)
		{
			return COLOR_PROFIT_GREEN; // Green
		}
		else if (score <= 40)
		{
			return new Color(150, 255, 100); // Yellow-green
		}
		else if (score <= 60)
		{
			return COLOR_YELLOW; // Yellow
		}
		else
		{
			return COLOR_LOSS_RED; // Red
		}
	}

	public static Color brightenColor(Color color, int amount)
	{
		return new Color(
			Math.min(255, color.getRed() + amount),
			Math.min(255, color.getGreen() + amount),
			Math.min(255, color.getBlue() + amount)
		);
	}

	/**
	 * Escape HTML special characters in a string.
	 * Used when embedding text in HTML labels.
	 */
	public static String escapeHtml(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;");
	}

	/**
	 * Format liquidity text for display
	 */
	public static String formatLiquidityText(Double score, String rating, Double volumePerHour)
	{
		if (score == null)
		{
			return LIQUIDITY_NA;
		}
		String displayRating = rating != null ? rating : UNKNOWN_RATING;
		String volText = volumePerHour != null ? formatGP(volumePerHour.intValue()) + "/hr" : "";
		return String.format(FORMAT_LIQUIDITY, score, displayRating, volText);
	}

	/**
	 * Format daily volume text for display
	 */
	public static String formatVolumeText(int dailyVolume)
	{
		if (dailyVolume <= 0)
		{
			return VOLUME_NA;
		}
		return String.format(FORMAT_VOLUME, formatGP(dailyVolume));
	}

	/**
	 * Format risk text for display
	 */
	public static String formatRiskText(Double score, String rating)
	{
		if (score == null)
		{
			return RISK_NA;
		}
		String displayRating = rating != null ? rating : UNKNOWN_RATING;
		return String.format(FORMAT_RISK, score, displayRating);
	}

	/**
	 * Format profit and cost text for display
	 */
	public static String formatProfitCostText(int totalProfit, int totalCost)
	{
		String profitText = Math.abs(totalProfit) >= 1000
			? formatGP(totalProfit)
			: formatGPExact(totalProfit);
		return String.format(FORMAT_PROFIT_COST, profitText, formatGP(totalCost));
	}

	/** Which side of the flip a card shows, coloured to match the price row. */
	public static String actionBuyingHtml()
	{
		return htmlRow("Action: " + bold(coloured(HEX_PRICE_LOW, "Buying")));
	}

	public static String actionSellingHtml()
	{
		return htmlRow("Action: " + bold(coloured(HEX_PRICE_HIGH, "Selling")));
	}

	/** Top "live" price row: market low (blue) | market high (orange), both bold; label stays plain. */
	public static String livePriceHtml(int low, int high)
	{
		return htmlRow("Live Price: " + bold(coloured(HEX_PRICE_LOW, formatGPExact(low)))
			+ " | " + bold(coloured(HEX_PRICE_HIGH, formatGPExact(high))));
	}

	/** Buy/Sell row styled like the live-price row: buy price blue, sell price orange, both bold. */
	public static String buySellHtml(int buyPrice, Integer sellPrice)
	{
		String sellSpan = sellPrice != null && sellPrice > 0
			? bold(coloured(HEX_PRICE_HIGH, formatGPExact(sellPrice)))
			: "N/A";
		return htmlRow("Buy: " + bold(coloured(HEX_PRICE_LOW, formatGPExact(buyPrice)))
			+ " | Sell: " + sellSpan);
	}

	/** Live Margin: gross market spread coloured green (profit) / red (loss), with ROI. No "+" prefix. */
	public static String liveMarginHtml(int margin, double roiPercent)
	{
		String colour = margin < 0 ? HEX_LOSS : HEX_PROFIT;
		return htmlRow("Live Margin: "
			+ coloured(colour, GpUtils.formatGPSigned(margin) + String.format(" (%.1f%% ROI)", roiPercent)));
	}

	/** Current Profit: gold label + realized value coloured green (profit) / red (loss). */
	public static String currentProfitHtml(long realizedNet)
	{
		String colour = realizedNet < 0 ? HEX_LOSS : HEX_PROFIT;
		return htmlRow(coloured(HEX_PROFIT_LABEL, "Current Profit: ")
			+ coloured(colour, GpUtils.formatGPSigned(realizedNet)));
	}

	/** Max Potential Profit: muted label + value coloured green (profit) / red (loss). */
	public static String maxPotentialProfitHtml(long maxProfit)
	{
		String colour = maxProfit < 0 ? HEX_LOSS : HEX_PROFIT;
		return htmlRow(coloured(HEX_MUTED, "Max Potential Profit: ")
			+ coloured(colour, GpUtils.formatGPSigned(maxProfit)));
	}

	/** Tax: whole row in the muted secondary colour, matching the Potential label. */
	public static String taxHtml(long total)
	{
		return htmlRow(coloured(HEX_MUTED, "Tax: " + formatGP(total)));
	}

	/** Qty: progress/total in the muted secondary colour (e.g. "Qty: 3/5"). */
	public static String qtyHtml(int done, long total)
	{
		return htmlRow(coloured(HEX_MUTED, "Qty: " + done + "/" + total));
	}

	/** Wrap card-row content as a Swing HTML label body. */
	private static String htmlRow(String inner)
	{
		return "<html>" + inner + "</html>";
	}

	/** Colour a span of text with the given hex (no leading #). */
	private static String coloured(String hex, String text)
	{
		return "<font color='#" + hex + "'>" + text + "</font>";
	}

	/** Emphasise a span. The live prices are the numbers players actually read. */
	private static String bold(String text)
	{
		return "<b>" + text + "</b>";
	}

	/**
	 * Shared setup for a transparent icon canvas: antialiasing on, cleared to fully
	 * transparent so the icon draws over a blank background instead of opaque black.
	 */
	private static Graphics2D createTransparentIconGraphics(BufferedImage icon)
	{
		Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setComposite(AlphaComposite.Clear);
		g.fillRect(0, 0, icon.getWidth(), icon.getHeight());
		g.setComposite(AlphaComposite.SrcOver);
		return g;
	}







	/**
	 * Get the base background color for a panel (either price indicator color or default).
	 * Checks for stored client property first, falls back to default color.
	 */
	public static Color getBaseBackgroundColor(JPanel panel, Color defaultColor)
	{
		Object stored = panel.getClientProperty("baseBackgroundColor");
		if (stored instanceof Color)
		{
			return (Color) stored;
		}
		return defaultColor;
	}
}
