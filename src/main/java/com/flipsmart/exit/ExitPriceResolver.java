package com.flipsmart.exit;

import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.util.GeTax;

/** Resolves the target exit sell price for a slot, per mode, with an AC8 mid-price fallback. */
public final class ExitPriceResolver
{
	private ExitPriceResolver()
	{
	}

	public static int resolve(ExitTradesMode mode, int itemId, int buyBasis, int backendBreakeven, WikiPrice price)
	{
		if (mode == ExitTradesMode.BREAKEVEN)
		{
			// Prefer the backend's exit-at-breakeven (source of truth for cost basis + tax);
			// fall back to the client-side tax calc only when the backend price is unavailable.
			if (backendBreakeven > 0)
			{
				return backendBreakeven;
			}
			if (buyBasis > 0)
			{
				return GeTax.breakevenSellPrice(itemId, buyBasis);
			}
		}
		if (mode == ExitTradesMode.INSTANT && price != null && price.instaSell > 0)
		{
			return price.instaSell;
		}
		return price == null ? 0 : price.midPrice();
	}
}
