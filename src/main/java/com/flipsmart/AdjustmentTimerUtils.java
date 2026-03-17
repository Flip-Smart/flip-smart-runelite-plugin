package com.flipsmart;

/**
 * Shared utility for computing adjustment timer delays.
 * Used by both {@link AutoRecommendService} and {@link ManualAdjustmentTracker}
 * to avoid duplicating delay calculation logic.
 */
final class AdjustmentTimerUtils
{
	/** Price threshold for high-value adjustment timer delays */
	static final int HIGH_VALUE_THRESHOLD = 5_000_000;

	private AdjustmentTimerUtils()
	{
	}

	/**
	 * Get the buy-side base adjustment delay based on timeframe and item price.
	 */
	static long getBuyBaseDelayMs(FlipSmartConfig.FlipTimeframe timeframe, int itemPrice)
	{
		boolean highValue = itemPrice >= HIGH_VALUE_THRESHOLD;
		switch (timeframe)
		{
			case ACTIVE:
				return (highValue ? 10 : 5) * 60 * 1000L;
			case THIRTY_MINS:
				return (highValue ? 15 : 10) * 60 * 1000L;
			case TWO_HOURS:
				return (highValue ? 30 : 15) * 60 * 1000L;
			case FOUR_HOURS:
				return (highValue ? 60 : 30) * 60 * 1000L;
			case TWELVE_HOURS:
				return (highValue ? 240 : 60) * 60 * 1000L;
			default:
				return (highValue ? 10 : 5) * 60 * 1000L;
		}
	}

	/**
	 * Get the sell-side base delay — shorter than buy-side because overpriced
	 * sells won't fill regardless of timeframe.
	 */
	static long getSellBaseDelayMs(FlipSmartConfig.FlipTimeframe timeframe, int itemPrice)
	{
		boolean highValue = itemPrice >= HIGH_VALUE_THRESHOLD;
		switch (timeframe)
		{
			case ACTIVE:
				return (highValue ? 10 : 5) * 60 * 1000L;
			case THIRTY_MINS:
				return (highValue ? 10 : 5) * 60 * 1000L;
			case TWO_HOURS:
				return (highValue ? 15 : 10) * 60 * 1000L;
			case FOUR_HOURS:
				return (highValue ? 20 : 10) * 60 * 1000L;
			case TWELVE_HOURS:
				return (highValue ? 30 : 15) * 60 * 1000L;
			default:
				return (highValue ? 10 : 5) * 60 * 1000L;
		}
	}

	/**
	 * Get the buy adjustment delay with risk preference multiplier applied.
	 */
	static long getBuyDelayMs(FlipSmartConfig.FlipTimeframe timeframe, int itemPrice,
		FlipSmartConfig.FlipStyle flipStyle)
	{
		long baseDelay = getBuyBaseDelayMs(timeframe, itemPrice);
		return applyRiskMultiplier(baseDelay, flipStyle);
	}

	/**
	 * Get the sell adjustment delay with risk preference multiplier applied.
	 */
	static long getSellDelayMs(FlipSmartConfig.FlipTimeframe timeframe, int itemPrice,
		FlipSmartConfig.FlipStyle flipStyle)
	{
		long baseDelay = getSellBaseDelayMs(timeframe, itemPrice);
		return applyRiskMultiplier(baseDelay, flipStyle);
	}

	private static long applyRiskMultiplier(long baseDelay, FlipSmartConfig.FlipStyle flipStyle)
	{
		switch (flipStyle)
		{
			case AGGRESSIVE:
				return (long) (baseDelay * 0.7);
			case CONSERVATIVE:
				return (long) (baseDelay * 1.5);
			default:
				return baseDelay;
		}
	}
}
