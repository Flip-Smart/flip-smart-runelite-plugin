package com.flipsmart;

/**
 * Utility class for GP (gold pieces) formatting and display.
 */
public final class GpUtils
{
	private GpUtils()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Format GP amount for display with K/M suffix.
	 *
	 * @param amount The GP amount to format
	 * @return Formatted string (e.g., "1.5M", "500K", "100")
	 */
	public static String formatGP(int amount)
	{
		if (amount >= 1_000_000)
		{
			return String.format("%.1fM", amount / 1_000_000.0);
		}
		else if (amount >= 1_000)
		{
			return String.format("%.1fK", amount / 1_000.0);
		}
		return String.valueOf(amount);
	}

	/**
	 * Format GP amount with " gp" suffix.
	 *
	 * @param amount The GP amount to format
	 * @return Formatted string (e.g., "1.5M gp")
	 */
	public static String formatGPWithSuffix(int amount)
	{
		return formatGP(amount) + " gp";
	}

	/**
	 * Format GP amount with sign for display (handles negative amounts).
	 *
	 * @param amount The GP amount to format (can be negative)
	 * @return Formatted string (e.g., "-1.5M", "500K", "100")
	 */
	public static String formatGPSigned(int amount)
	{
		int absAmount = Math.abs(amount);
		String sign = amount < 0 ? "-" : "";

		if (absAmount >= 1_000_000)
		{
			return String.format("%s%.1fM", sign, absAmount / 1_000_000.0);
		}
		else if (absAmount >= 1_000)
		{
			return String.format("%s%.1fK", sign, absAmount / 1_000.0);
		}
		return String.valueOf(amount);
	}

	/**
	 * Format ROI percentage for display.
	 *
	 * @param roiPercent The ROI percentage
	 * @return Formatted string (e.g., "5.2%")
	 */
	public static String formatROI(double roiPercent)
	{
		return String.format("%.1f%%", roiPercent);
	}

	/**
	 * Format GP amount with commas for exact display.
	 *
	 * @param amount The GP amount to format
	 * @return Formatted string with commas (e.g., "1,234,567")
	 */
	public static String formatGPExact(int amount)
	{
		return String.format("%,d", amount);
	}
}
