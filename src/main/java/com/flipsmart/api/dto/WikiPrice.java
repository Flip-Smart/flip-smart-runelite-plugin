package com.flipsmart.api.dto;

/**
 * Real-time price data from the wiki API
 */
public class WikiPrice
{
	private static final long WIKI_PRICE_CACHE_DURATION_MS = 60_000; // 1 minute cache

	public final int instaBuy;   // High price - what buyers pay to instant-buy
	public final int instaSell;  // Low price - what sellers receive when instant-selling
	public final long fetchedAt;

	public WikiPrice(int instaBuy, int instaSell)
	{
		this.instaBuy = instaBuy;
		this.instaSell = instaSell;
		this.fetchedAt = System.currentTimeMillis();
	}

	public boolean isExpired()
	{
		return System.currentTimeMillis() - fetchedAt > WIKI_PRICE_CACHE_DURATION_MS;
	}

	/**
	 * Midpoint of instant-buy and instant-sell, used as a safe exit fallback when
	 * a mode's target price is unavailable. Degrades to whichever side is present.
	 */
	public int midPrice()
	{
		if (instaBuy <= 0)
		{
			return Math.max(instaSell, 0);
		}
		if (instaSell <= 0)
		{
			return instaBuy;
		}
		return (instaBuy + instaSell) / 2;
	}
}
