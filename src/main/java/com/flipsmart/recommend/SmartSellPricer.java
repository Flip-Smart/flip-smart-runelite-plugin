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

	/** A sell below this share of the live market price is corrupt, not a loss-cut. */
	private static final double MIN_PLAUSIBLE_MARKET_FRACTION = 0.2;

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
	 * @return {@code 0} when {@code buyPrice} is non-positive. A missing cost basis is
	 *         not a free position — it has no computable breakeven, and reading it as
	 *         one yields 1gp, a price the player can act on and lose the position at.
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
	 * Last line of defence before a sell price reaches the player: a price this far under
	 * the live market cannot have come from a real basis or a real target, whatever path
	 * produced it. Cutting losses is legitimate and stays well above the bound.
	 *
	 * @return {@code false} whenever {@code marketPrice} is unknown — there is nothing to judge against.
	 */
	public static boolean isImplausibleSellPrice(int sellPrice, Integer marketPrice)
	{
		if (marketPrice == null || marketPrice <= 0)
		{
			return false;
		}
		return sellPrice <= 0 || sellPrice < marketPrice * MIN_PLAUSIBLE_MARKET_FRACTION;
	}

	/**
	 * Resolve the price to offer the player, or {@code null} when no source knows one.
	 *
	 * <p>Returning null is deliberate: callers must drop the prompt rather than surface a
	 * fabricated number. Every price this returns traces to the player's own basis, the
	 * original recommendation, or live market data.</p>
	 */
	public static Integer calculateSmartSellPrice(ActiveFlip flip, Integer currentMarketPrice)
	{
		int buyPrice = flip.getAverageBuyPrice();
		Integer recommended = flip.getRecommendedSellPrice();

		if (buyPrice <= 0)
		{
			if (recommended != null && recommended > 0)
			{
				return recommended;
			}
			boolean haveMarket = currentMarketPrice != null && currentMarketPrice > 0;
			if (log.isWarnEnabled())
				log.warn("No cost basis for item {} ({}); falling back to market {}",
					flip.getItemId(), flip.getItemName(), haveMarket ? currentMarketPrice : "(unknown)");
			return haveMarket ? currentMarketPrice : null;
		}

		int minProfitablePrice = calculateMinProfitableSellPrice(buyPrice);

		if (recommended != null && recommended >= minProfitablePrice)
		{
			return recommended;
		}

		if (currentMarketPrice != null && currentMarketPrice >= minProfitablePrice)
		{
			return minProfitablePrice;
		}

		if (recommended != null)
		{
			return recommended;
		}

		return minProfitablePrice;
	}
}
