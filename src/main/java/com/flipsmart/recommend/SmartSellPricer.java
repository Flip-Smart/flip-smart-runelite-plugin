package com.flipsmart.recommend;

import com.flipsmart.domain.flip.ActiveFlip;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;

/**
 * Smart-sell pricing decisions for active flips. Stateless and free of any UI
 * dependency: every method takes the data it needs as primitives or domain
 * objects and returns a price (or a strategy decision) the panel renders.
 */
@Slf4j
public final class SmartSellPricer
{
	private static final int HIGH_VOLUME_THRESHOLD = 500_000;
	private static final int HIGH_VOLUME_TIME_MINUTES = 10;

	private static final int REGULAR_TIME_MINUTES = 20;

	private static final int HIGH_VALUE_THRESHOLD = 250_000_000;
	private static final int HIGH_VALUE_TIME_MINUTES = 30;

	private SmartSellPricer()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Calculate the sell price threshold time for an active flip.
	 * Returns the number of minutes after which we should switch from
	 * profit-first to loss-minimizing strategy.
	 *
	 * Rules:
	 * - High volume items (&gt;500k daily): 10 minutes
	 * - High value items (&gt;250M buy price): 30 minutes
	 * - Regular items: 20 minutes
	 */
	public static int getSellPriceThresholdMinutes(ActiveFlip flip, Integer dailyVolume)
	{
		if (flip.getAverageBuyPrice() >= HIGH_VALUE_THRESHOLD)
		{
			return HIGH_VALUE_TIME_MINUTES;
		}

		if (dailyVolume != null && dailyVolume >= HIGH_VOLUME_THRESHOLD)
		{
			return HIGH_VOLUME_TIME_MINUTES;
		}

		return REGULAR_TIME_MINUTES;
	}

	/**
	 * Check if an active flip has exceeded its time threshold and should
	 * switch to loss-minimizing sell price.
	 */
	public static boolean shouldUseLossMinimizingPrice(ActiveFlip flip, Integer dailyVolume)
	{
		String buyTimeStr = flip.getLastBuyTime();
		if (buyTimeStr == null || buyTimeStr.isEmpty())
		{
			return false;
		}

		try
		{
			Instant buyTime = Instant.parse(buyTimeStr);
			Duration elapsed = Duration.between(buyTime, Instant.now());
			int thresholdMinutes = getSellPriceThresholdMinutes(flip, dailyVolume);
			return elapsed.toMinutes() >= thresholdMinutes;
		}
		catch (DateTimeParseException e)
		{
			log.debug("Failed to parse buy time: {}", buyTimeStr);
			return false;
		}
	}

	private static final int GE_TAX_EXEMPT_THRESHOLD = 100;
	private static final int GE_TAX_CAP_GP = 5_000_000;
	private static final double GE_TAX_RATE = 0.01;

	/**
	 * Calculate the minimum profitable sell price for an active flip.
	 * This is the sell price at which profit after GE tax is exactly 1 GP.
	 *
	 * OSRS GE tax rules:
	 * - Items priced under 100 GP are tax-exempt.
	 * - Tax = 1% of sell price, capped at 5,000,000 GP.
	 */
	public static int calculateMinProfitableSellPrice(int buyPrice)
	{
		if (buyPrice < GE_TAX_EXEMPT_THRESHOLD)
		{
			return buyPrice + 1;
		}
		int tax = (int) Math.min(Math.ceil(buyPrice * GE_TAX_RATE), GE_TAX_CAP_GP);
		return buyPrice + tax + 1;
	}

	public static Integer calculateSmartSellPrice(ActiveFlip flip, Integer currentMarketPrice)
	{
		int buyPrice = flip.getAverageBuyPrice();
		int minProfitablePrice = calculateMinProfitableSellPrice(buyPrice);

		if (flip.getRecommendedSellPrice() != null && flip.getRecommendedSellPrice() >= minProfitablePrice)
		{
			return flip.getRecommendedSellPrice();
		}

		if (currentMarketPrice != null && currentMarketPrice >= minProfitablePrice)
		{
			return minProfitablePrice;
		}

		if (flip.getRecommendedSellPrice() != null)
		{
			return flip.getRecommendedSellPrice();
		}

		return minProfitablePrice;
	}
}
