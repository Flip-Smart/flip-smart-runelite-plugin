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
	 * Calculate the minimum profitable sell price for an active flip.
	 * This is the price that would result in zero profit after tax.
	 * Formula: minSellPrice = buyPrice / (1 - taxRate)
	 * Adding 1gp ensures a small profit.
	 *
	 * @return {@code 0} when {@code buyPrice} is non-positive. A missing cost basis is not a
	 *         free position: it has no computable breakeven, and reading it as one yields 1gp.
	 */
	public static int calculateMinProfitableSellPrice(int buyPrice)
	{
		if (buyPrice <= 0)
		{
			return 0;
		}
		// GE tax is 2%, so to break even: sellPrice * 0.98 = buyPrice
		// sellPrice = buyPrice / 0.98
		// Add 1gp to ensure profit
		return (int) Math.ceil(buyPrice / 0.98) + 1;
	}

	/**
	 * Resolve the sell price to show, or {@code null} when no source knows one.
	 *
	 * <p>Returning null is deliberate: callers must drop the prompt rather than surface a
	 * fabricated number. Every price this returns traces to the player's own basis, the
	 * original recommendation, or live market data.</p>
	 */
	public static Integer calculateSmartSellPrice(ActiveFlip flip, Integer currentMarketPrice)
	{
		int buyPrice = flip.getAverageBuyPrice();
		if (buyPrice <= 0)
		{
			return priceWithoutBasis(flip, currentMarketPrice);
		}
		return priceFromBasis(flip, currentMarketPrice, calculateMinProfitableSellPrice(buyPrice));
	}

	private static Integer priceWithoutBasis(ActiveFlip flip, Integer currentMarketPrice)
	{
		Integer target = flip.getRecommendedSellPrice();
		if (isPositive(target))
		{
			return target;
		}
		log.warn("No cost basis for item {} ({}); falling back to market",
			flip.getItemId(), flip.getItemName());
		return isPositive(currentMarketPrice) ? currentMarketPrice : null;
	}

	private static Integer priceFromBasis(ActiveFlip flip, Integer currentMarketPrice, int minProfitablePrice)
	{
		Integer recommended = flip.getRecommendedSellPrice();
		if (atLeast(recommended, minProfitablePrice))
		{
			return recommended;
		}
		if (atLeast(currentMarketPrice, minProfitablePrice))
		{
			return minProfitablePrice;
		}
		return isPositive(recommended) ? recommended : minProfitablePrice;
	}

	private static boolean isPositive(Integer value)
	{
		return value != null && value > 0;
	}

	private static boolean atLeast(Integer value, int threshold)
	{
		return value != null && value >= threshold;
	}
}
