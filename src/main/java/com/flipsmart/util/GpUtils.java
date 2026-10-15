package com.flipsmart.util;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for GP (gold pieces) formatting and display.
 */
public final class GpUtils
{
	// number with optional decimal, optional k/m/b suffix (case-insensitive).
	private static final Pattern GP_INPUT = Pattern.compile("^(\\d+(?:\\.\\d+)?)([kmb]?)$");
	private static final long[] GP_TIERS = {1_000_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L};
	private static final String GP_SUFFIXES = "TBMK";

	private GpUtils()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Digits pulled out of GE interface text, which arrives with commas, spaces and
	 * trailing words like "coins". Returns 0 for null, empty, digit-free or
	 * over-long input rather than throwing -- callers treat 0 as "unknown".
	 */
	public static long parseDigits(String text)
	{
		if (text == null || text.isEmpty())
		{
			return 0;
		}
		try
		{
			String numericOnly = text.replaceAll("\\D", "");
			return numericOnly.isEmpty() ? 0 : Long.parseLong(numericOnly);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
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
	 * clamped to a full coin stack plus a full platinum token stack. Blank, zero, negative, or otherwise unparseable input yields
	 * an empty result.
	 *
	 * @param input raw user text (may be null)
	 * @return the resolved GP amount, or empty if the input is invalid
	 */
	public static OptionalLong parseGp(String input)
	{
		if (input == null)
		{
			return OptionalLong.empty();
		}

		String cleaned = input.trim().toLowerCase();
		if (cleaned.endsWith("gp"))
		{
			cleaned = cleaned.substring(0, cleaned.length() - 2).trim();
		}
		cleaned = cleaned.replace(",", "").replace(" ", "");
		if (cleaned.isEmpty())
		{
			return OptionalLong.empty();
		}

		Matcher matcher = GP_INPUT.matcher(cleaned);
		if (!matcher.matches())
		{
			return OptionalLong.empty();
		}

		double base;
		try
		{
			base = Double.parseDouble(matcher.group(1));
		}
		catch (NumberFormatException e)
		{
			return OptionalLong.empty();
		}

		long value = (long) Math.floor(applyMultiplier(base, matcher.group(2)));
		if (value < 1)
		{
			return OptionalLong.empty();
		}
		return OptionalLong.of(Math.min(value, Integer.MAX_VALUE * 1_001L));
	}

	/**
	 * Format GP amount for display with K/M/B/T suffix.
	 *
	 * @param amount The GP amount to format
	 * @return Formatted string (e.g., "1.5M", "500K", "100")
	 */
	public static String formatGP(long amount)
	{
		return amount < 1_000 ? String.valueOf(amount) : formatGPSigned(amount);
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
		for (int i = 0; i < GP_TIERS.length; i++)
		{
			if (absAmount >= GP_TIERS[i])
			{
				return String.format("%s%.1f%c", amount < 0 ? "-" : "", absAmount / (double) GP_TIERS[i], GP_SUFFIXES.charAt(i));
			}
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
