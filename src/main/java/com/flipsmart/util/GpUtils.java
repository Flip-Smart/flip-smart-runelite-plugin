package com.flipsmart.util;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for GP (gold pieces) formatting and display.
 */
public final class GpUtils
{
	// number with optional decimal, optional k/m/b suffix (case-insensitive).
	private static final Pattern GP_INPUT = Pattern.compile("^(\\d+(?:\\.\\d+)?)([kmb]?)$");

	private GpUtils()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Apply a k/m/b suffix multiplier to a base value.
	 *
	 * @param base   the numeric base already parsed from user input
	 * @param suffix the suffix character group captured by {@link #GP_INPUT}
	 * @return the multiplied value
	 */
	private static double applyMultiplier(double base, String suffix)
	{
		switch (suffix)
		{
			case "k": return base * 1_000d;
			case "m": return base * 1_000_000d;
			case "b": return base * 1_000_000_000d;
			default:  return base;
		}
	}

	/**
	 * Parse a user-entered GP amount supporting shorthand suffixes.
	 *
	 * <p>Accepts raw integers ({@code 5000000}), comma separators
	 * ({@code 10,000,000}), a trailing {@code gp}, and case-insensitive
	 * {@code k}/{@code m}/{@code b} multipliers including decimals
	 * ({@code 2.5m}, {@code 500K}). Fractional results are floored. Values are
	 * clamped to {@link Integer#MAX_VALUE} (the largest coin stack the game
	 * supports). Blank, zero, negative, or otherwise unparseable input yields
	 * an empty result.
	 *
	 * @param input raw user text (may be null)
	 * @return the resolved GP amount, or empty if the input is invalid
	 */
	public static OptionalInt parseGp(String input)
	{
		if (input == null)
		{
			return OptionalInt.empty();
		}

		String cleaned = input.trim().toLowerCase();
		if (cleaned.endsWith("gp"))
		{
			cleaned = cleaned.substring(0, cleaned.length() - 2).trim();
		}
		cleaned = cleaned.replace(",", "").replace(" ", "");
		if (cleaned.isEmpty())
		{
			return OptionalInt.empty();
		}

		Matcher matcher = GP_INPUT.matcher(cleaned);
		if (!matcher.matches())
		{
			return OptionalInt.empty();
		}

		double base;
		try
		{
			base = Double.parseDouble(matcher.group(1));
		}
		catch (NumberFormatException e)
		{
			return OptionalInt.empty();
		}

		long value = (long) Math.floor(applyMultiplier(base, matcher.group(2)));
		if (value < 1)
		{
			return OptionalInt.empty();
		}
		return OptionalInt.of((int) Math.min(value, Integer.MAX_VALUE));
	}

	/**
	 * Format GP amount for display with K/M suffix.
	 *
	 * @param amount The GP amount to format
	 * @return Formatted string (e.g., "1.5M", "500K", "100")
	 */
	public static String formatGP(long amount)
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
	public static String formatGPWithSuffix(long amount)
	{
		return formatGP(amount) + " gp";
	}

	/**
	 * Format GP amount with sign for display (handles negative amounts).
	 *
	 * @param amount The GP amount to format (can be negative)
	 * @return Formatted string (e.g., "-1.5M", "500K", "100")
	 */
	public static String formatGPSigned(long amount)
	{
		long absAmount = Math.abs(amount);
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
	public static String formatGPExact(long amount)
	{
		return String.format("%,d", amount);
	}
}
