package com.flipsmart.util;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Grand Exchange tax helpers.
 *
 * Source of truth for the exempt list is the backend at
 * {@code flip-smart/app/constants.py} (GE_TAX_EXEMPT_ITEM_IDS). The set is
 * mirrored here so the GE-slot hover overlay can compute the player's true
 * post-tax profit without a network round-trip. If the backend list changes,
 * update both. Drift is guarded by GeTaxExemptParityTest against a pinned set
 * shared with the backend's parity test.
 *
 * Wiki reference:
 * https://oldschool.runescape.wiki/w/Category:Items_exempt_from_Grand_Exchange_tax
 */
public final class GeTax
{
	private static final double GE_TAX_RATE = 0.02;
	public static final int GE_TAX_CAP = 5_000_000;
	private static final int GE_TAX_EXEMPT_PRICE_THRESHOLD = 50;

	/** Items the GE never taxes regardless of sell price. */
	public static final Set<Integer> EXEMPT_ITEM_IDS = ItemUtils.resourceRows("/ge_tax_exempt.txt")
		.map(r -> Integer.parseInt(r[0])).collect(Collectors.toUnmodifiableSet());

	private GeTax()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Whether the given item id and sell price is exempt from GE tax.
	 *
	 * Two reasons an item is exempt: sell price &le; 50gp, or the item id is
	 * on the GE tax-free list.
	 */
	public static boolean isExempt(int itemId, long sellPrice)
	{
		if (sellPrice <= GE_TAX_EXEMPT_PRICE_THRESHOLD)
		{
			return true;
		}
		return isExemptItem(itemId);
	}

	private static boolean isExemptItem(int itemId)
	{
		return EXEMPT_ITEM_IDS.contains(itemId);
	}

	/**
	 * Per-item GE tax for a given sell price. Returns 0 for exempt items, and
	 * caps at 5,000,000gp per item for high-value flips. Matches Jagex's
	 * floored 2% calc.
	 */
	public static int taxFor(int itemId, long sellPrice)
	{
		if (isExempt(itemId, sellPrice))
		{
			return 0;
		}
		return (int) Math.min((long) Math.floor(sellPrice * GE_TAX_RATE), GE_TAX_CAP);
	}

	/**
	 * Per-item GE tax for a given sell price when the item id is not known to the
	 * caller (e.g. the pure-function offer-description formatter). Applies the
	 * price-based exemption (&le; 50gp) and cap, but cannot consult the
	 * exempt-item list. Prefer {@link #taxFor(int, long)} whenever an item id is
	 * available.
	 */
	public static int taxFor(long sellPrice)
	{
		if (sellPrice <= GE_TAX_EXEMPT_PRICE_THRESHOLD)
		{
			return 0;
		}
		return (int) Math.min((long) Math.floor(sellPrice * GE_TAX_RATE), GE_TAX_CAP);
	}

	/**
	 * Item-aware breakeven: for items on the tax-free list the flip breaks even
	 * the moment the sell price covers the buy price (no tax to overcome), so
	 * the breakeven is simply the recorded buy price. Otherwise defers to the
	 * price-only calculation.
	 */
	public static long breakevenSellPrice(int itemId, long recordedBuyPrice)
	{
		if (isExemptItem(itemId))
		{
			return recordedBuyPrice;
		}
		return breakevenSellPrice(recordedBuyPrice);
	}

	/**
	 * Smallest sell price S such that {@code S - taxFor(S) >= recordedBuyPrice},
	 * i.e. the price at which a flip first breaks even after GE tax. Returns the
	 * buy price unchanged for tax-exempt (&le; 50gp) inputs.
	 */
	public static long breakevenSellPrice(long recordedBuyPrice)
	{
		if (recordedBuyPrice <= GE_TAX_EXEMPT_PRICE_THRESHOLD)
		{
			return recordedBuyPrice;
		}
		// Closed-form estimate, bounded by buy + cap so capped prices skip a multi-million-step walk down.
		long candidate = Math.min((long) Math.ceil(recordedBuyPrice / (1.0 - GE_TAX_RATE)), recordedBuyPrice + GE_TAX_CAP);
		while (candidate - taxFor(candidate) < recordedBuyPrice)
		{
			candidate++;
		}
		while (candidate > recordedBuyPrice
			&& (candidate - 1) - taxFor(candidate - 1) >= recordedBuyPrice)
		{
			candidate--;
		}
		return candidate;
	}
}
