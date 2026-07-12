package com.flipsmart.ui.panel;

import com.flipsmart.util.GpUtils;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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

	private static final String FORMAT_BUY_SELL = "Buy: %s | Sell: %s";
	private static final String FORMAT_PROFIT_COST = "Profit: %s | Cost: %s";
	private static final String FORMAT_MARGIN_ROI = "Margin: %s (%.1f%% ROI)";
	private static final String FORMAT_MARGIN_ROI_LOSS = "Margin: %s (%.1f%% ROI) - Loss";
	private static final String FORMAT_LIQUIDITY = "Liquidity: %.0f (%s) | %s";
	private static final String FORMAT_VOLUME = "Volume: %s/day";
	private static final String FORMAT_RISK = "Risk: %.0f (%s)";
	private static final String FORMAT_MARKET_BUY_SELL = "Buy: %s | Sell: %s";
	private static final String FORMAT_CURRENT_MARGIN = "Current Margin: %s gp (%.1f%% ROI)";
	private static final String FORMAT_CURRENT_PROFIT = "Current Profit: %s";
	private static final String FORMAT_PROFIT_POTENTIAL = "Profit Potential: %s | Cost: %s";
	private static final String FORMAT_TAX_SPLIT = "Tax: %s | %s";
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
	public static String formatGP(int amount)
	{
		return GpUtils.formatGPSigned(amount);
	}

	/**
	 * Format GP amount with commas for exact input (e.g., "1,234,567")
	 */
	public static String formatGPExact(int amount)
	{
		return GpUtils.formatGPExact(amount);
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
	 * Format margin text with ROI for display
	 */
	public static String formatMarginText(int marginPerItem, double roi, boolean isLoss)
	{
		String marginText = Math.abs(marginPerItem) >= 1000
			? formatGP(marginPerItem)
			: formatGPExact(marginPerItem);

		if (Double.isInfinite(roi) || Double.isNaN(roi))
		{
			return String.format("Margin: %s (pending)", marginText);
		}

		if (isLoss)
		{
			return String.format(FORMAT_MARGIN_ROI_LOSS, marginText, roi);
		}
		return String.format(FORMAT_MARGIN_ROI, marginText, roi);
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

	/**
	 * Format buy/sell prices text for display
	 */
	public static String formatBuySellText(int buyPrice, Integer sellPrice)
	{
		String sellText = sellPrice != null && sellPrice > 0
			? formatGPExact(sellPrice)
			: "N/A";
		return String.format(FORMAT_BUY_SELL, formatGPExact(buyPrice), sellText);
	}

	/** Row 1: market low labeled "Buy", market high labeled "Sell". */
	public static String formatMarketBuySellText(int low, int high)
	{
		return String.format(FORMAT_MARKET_BUY_SELL, formatGPExact(low), formatGPExact(high));
	}

	/** Current Margin: gross market spread (signed, " gp") with ROI percentage. */
	public static String formatCurrentMarginText(int marginGp, double roiPercent)
	{
		return String.format(FORMAT_CURRENT_MARGIN, signedShort(marginGp), roiPercent);
	}

	/** Current Profit: realized net on units sold so far (signed short form, no suffix). */
	public static String formatCurrentProfitText(long netProfit)
	{
		return String.format(FORMAT_CURRENT_PROFIT, signedShort(clampInt(netProfit)));
	}

	/** Profit Potential | Cost: profit is signed short; cost is unsigned short. */
	public static String formatProfitPotentialText(long profit, long cost)
	{
		return String.format(FORMAT_PROFIT_POTENTIAL, GpUtils.formatGPSigned(clampInt(profit)), formatGP(clampInt(cost)));
	}

	/** Tax: per-item (exact) | total (short). */
	public static String formatTaxSplitText(int perItem, long total)
	{
		return String.format(FORMAT_TAX_SPLIT, formatGPExact(perItem), formatGP(clampInt(total)));
	}

	/** Short-form gp with an explicit '+' on positive values ("+6", "-6", "0"). */
	private static String signedShort(int v)
	{
		return (v > 0 ? "+" : "") + GpUtils.formatGPSigned(v);
	}

	/** Saturating cast of a long into the int range for the gp formatters. */
	private static int clampInt(long v)
	{
		if (v > Integer.MAX_VALUE)
		{
			return Integer.MAX_VALUE;
		}
		if (v < Integer.MIN_VALUE)
		{
			return Integer.MIN_VALUE;
		}
		return (int) v;
	}

	/**
	 * Draw a bar chart icon onto a 14x14 image with the given colors.
	 */
	public static BufferedImage drawChartIcon(Color barColor, Color baselineColor)
	{
		BufferedImage icon = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setComposite(AlphaComposite.Clear);
		g.fillRect(0, 0, 14, 14);
		g.setComposite(AlphaComposite.SrcOver);

		g.setColor(barColor);
		g.fillRect(1, 9, 3, 4);   // Short bar
		g.fillRect(5, 5, 3, 8);   // Medium bar
		g.fillRect(9, 2, 3, 11);  // Tall bar

		g.setColor(baselineColor);
		g.drawLine(0, 13, 13, 13);

		g.dispose();
		return icon;
	}

	/**
	 * Draw a small clock face onto a 12x12 image with the given color.
	 */
	public static BufferedImage drawClockIcon(Color color)
	{
		BufferedImage icon = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setComposite(AlphaComposite.Clear);
		g.fillRect(0, 0, 12, 12);
		g.setComposite(AlphaComposite.SrcOver);

		g.setColor(color);
		g.setStroke(new BasicStroke(1.2f));
		g.drawOval(1, 1, 9, 9);     // Face
		g.drawLine(6, 6, 6, 3);     // Minute hand
		g.drawLine(6, 6, 8, 7);     // Hour hand

		g.dispose();
		return icon;
	}

	/**
	 * Draw a gear/cogwheel icon onto a {@code size}x{@code size} image with the given color.
	 */
	public static BufferedImage drawGearIcon(Color color, int size)
	{
		BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setColor(color);
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		double cx = size / 2.0;
		double cy = size / 2.0;
		double toothTip = size / 2.0 - 1.0;
		double body = toothTip - 2.5;
		int teeth = 8;

		for (int i = 0; i < teeth; i++)
		{
			double a = Math.PI * 2 * i / teeth;
			int x1 = (int) Math.round(cx + Math.cos(a) * body);
			int y1 = (int) Math.round(cy + Math.sin(a) * body);
			int x2 = (int) Math.round(cx + Math.cos(a) * toothTip);
			int y2 = (int) Math.round(cy + Math.sin(a) * toothTip);
			g.drawLine(x1, y1, x2, y2);
		}

		int bodyDia = (int) Math.round(body * 2);
		g.fillOval((int) Math.round(cx - body), (int) Math.round(cy - body), bodyDia, bodyDia);

		double hole = body * 0.45;
		int holeDia = (int) Math.round(hole * 2);
		g.setComposite(AlphaComposite.Clear);
		g.fillOval((int) Math.round(cx - hole), (int) Math.round(cy - hole), holeDia, holeDia);

		g.dispose();
		return icon;
	}

	/**
	 * Draw a ban/circle-slash icon onto a 14x14 image with the given color.
	 */
	public static BufferedImage drawBlockIcon(Color color)
	{
		BufferedImage icon = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setComposite(AlphaComposite.Clear);
		g.fillRect(0, 0, 14, 14);
		g.setComposite(AlphaComposite.SrcOver);

		g.setColor(color);
		g.setStroke(new BasicStroke(1.5f));
		g.drawOval(1, 1, 11, 11);
		g.drawLine(3, 11, 11, 3);

		g.dispose();
		return icon;
	}

	/**
	 * Draw a circular-arrow refresh icon onto a 14x14 image with the given color.
	 */
	public static BufferedImage drawRefreshIcon(Color color)
	{
		BufferedImage icon = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setComposite(AlphaComposite.Clear);
		g.fillRect(0, 0, 14, 14);
		g.setComposite(AlphaComposite.SrcOver);

		g.setColor(color);
		g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		// ~300-degree arc leaves a gap at the top-right for the arrowhead
		g.drawArc(2, 2, 9, 9, 60, 300);
		// arrowhead at the arc's open end
		g.drawLine(11, 3, 11, 6);
		g.drawLine(11, 3, 8, 3);

		g.dispose();
		return icon;
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
