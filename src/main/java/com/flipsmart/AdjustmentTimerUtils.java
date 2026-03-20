package com.flipsmart;

/**
 * Shared constants for adjustment timer scheduling.
 *
 * The backend API (/flips/adjustment) is the source of truth for adjustment
 * timing. The plugin uses a short initial delay for the first check, then
 * relies on the API's {@code next_check_minutes} response field to schedule
 * subsequent checks. This allows timing to be tuned server-side without
 * requiring plugin updates.
 */
final class AdjustmentTimerUtils
{
	/** Initial delay before the first adjustment check (5 minutes). */
	static final long INITIAL_CHECK_DELAY_MS = 5 * 60 * 1000L;

	/** Fallback delay if the API doesn't return next_check_minutes (10 minutes). */
	static final long FALLBACK_CHECK_DELAY_MS = 10 * 60 * 1000L;

	private AdjustmentTimerUtils()
	{
	}

	/**
	 * Convert the API's next_check_minutes to milliseconds,
	 * falling back to {@link #FALLBACK_CHECK_DELAY_MS} if null or zero.
	 */
	static long nextCheckDelayMs(Integer nextCheckMinutes)
	{
		if (nextCheckMinutes != null && nextCheckMinutes > 0)
		{
			return nextCheckMinutes * 60 * 1000L;
		}
		return FALLBACK_CHECK_DELAY_MS;
	}
}
