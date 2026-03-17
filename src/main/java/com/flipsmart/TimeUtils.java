package com.flipsmart;

/**
 * Utility class for time formatting operations shared across overlays.
 */
public final class TimeUtils
{
	private TimeUtils()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Format elapsed time since a given timestamp as H:MM:SS or M:SS
	 *
	 * @param createdAtMillis The timestamp in milliseconds when the event started
	 * @return Formatted elapsed time string
	 */
	public static String formatElapsedTime(long createdAtMillis)
	{
		long elapsed = Math.max(0, System.currentTimeMillis() - createdAtMillis);
		return formatMilliseconds(elapsed);
	}

	/**
	 * Format elapsed time between two timestamps as H:MM:SS or M:SS (for completed offers)
	 *
	 * @param startMillis The timestamp in milliseconds when the event started
	 * @param endMillis The timestamp in milliseconds when the event ended
	 * @return Formatted elapsed time string
	 */
	public static String formatFrozenElapsedTime(long startMillis, long endMillis)
	{
		long elapsed = Math.max(0, endMillis - startMillis);
		return formatMilliseconds(elapsed);
	}

	/**
	 * Format milliseconds as M:SS, H:MM:SS, or Xh Ym for long durations.
	 * Under 1 hour: M:SS (e.g., "5:23")
	 * 1-10 hours: H:MM:SS (e.g., "2:15:03")
	 * Over 10 hours: compact format (e.g., "15h 07m")
	 */
	private static String formatMilliseconds(long elapsed)
	{
		long seconds = (elapsed / 1000) % 60;
		long minutes = (elapsed / 60000) % 60;
		long hours = elapsed / 3600000;

		if (hours >= 10)
		{
			return String.format("%dh %02dm", hours, minutes);
		}
		if (hours > 0)
		{
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format("%d:%02d", minutes, seconds);
	}

	/**
	 * Format elapsed time in short form (e.g., "5m" or "2h")
	 *
	 * @param createdAtMillis The timestamp in milliseconds when the event started
	 * @return Short formatted elapsed time string
	 */
	public static String formatElapsedTimeShort(long createdAtMillis)
	{
		long elapsed = Math.max(0, System.currentTimeMillis() - createdAtMillis);
		long minutes = elapsed / 60000;
		long hours = elapsed / 3600000;

		if (hours > 0)
		{
			return hours + "h";
		}
		return minutes + "m";
	}
}
