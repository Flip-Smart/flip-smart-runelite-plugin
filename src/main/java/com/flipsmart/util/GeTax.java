package com.flipsmart.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Grand Exchange tax helpers.
 *
 * Source of truth for the exempt list is the backend at
 * {@code flip-smart/app/constants.py} (GE_TAX_EXEMPT_ITEM_IDS). The set is
 * mirrored here so the GE-slot hover overlay can compute the player's true
 * post-tax profit without a network round-trip. If the backend list changes,
 * update both.
 *
 * Wiki reference:
 * https://oldschool.runescape.wiki/w/Category:Items_exempt_from_Grand_Exchange_tax
 */
public final class GeTax
{
	private static final double GE_TAX_RATE = 0.02;
	private static final int GE_TAX_CAP = 5_000_000;
	private static final int GE_TAX_EXEMPT_PRICE_THRESHOLD = 50;

	/** Items the GE never taxes regardless of sell price. */
	public static final Set<Integer> EXEMPT_ITEM_IDS;
	static
	{
		Set<Integer> ids = new HashSet<>();
		ids.add(233);    // Pestle and mortar
		ids.add(315);    // Shrimps
		ids.add(329);    // Salmon
		ids.add(347);    // Herring
		ids.add(351);    // Pike
		ids.add(355);    // Mackerel
		ids.add(361);    // Tuna
		ids.add(365);    // Bass
		ids.add(379);    // Lobster
		ids.add(558);    // Mind rune
		ids.add(806);    // Bronze dart
		ids.add(807);    // Iron dart
		ids.add(808);    // Steel dart
		ids.add(882);    // Bronze arrow
		ids.add(884);    // Iron arrow
		ids.add(886);    // Steel arrow
		ids.add(952);    // Spade
		ids.add(1733);   // Needle
		ids.add(1735);   // Shears
		ids.add(1755);   // Chisel
		ids.add(1785);   // Glassblowing pipe
		ids.add(1891);   // Cake
		ids.add(2140);   // Cooked chicken
		ids.add(2142);   // Cooked meat
		ids.add(2309);   // Bread
		ids.add(2327);   // Meat pie
		ids.add(2347);   // Hammer
		ids.add(2552);   // Ring of dueling(8)
		ids.add(3008);   // Energy potion(4)
		ids.add(3853);   // Games necklace(8)
		ids.add(5325);   // Gardening trowel
		ids.add(5329);   // Secateurs
		ids.add(5331);   // Watering can
		ids.add(5341);   // Rake
		ids.add(5343);   // Seed dibber
		ids.add(8007);   // Varrock teleport (tablet)
		ids.add(8008);   // Lumbridge teleport (tablet)
		ids.add(8009);   // Falador teleport (tablet)
		ids.add(8010);   // Camelot teleport (tablet)
		ids.add(8011);   // Ardougne teleport (tablet)
		ids.add(8013);   // Teleport to house (tablet)
		ids.add(8794);   // Saw
		ids.add(13190);  // Old school bond
		ids.add(28790);  // Kourend castle teleport (tablet)
		ids.add(28824);  // Civitas illa fortis teleport
		EXEMPT_ITEM_IDS = Collections.unmodifiableSet(ids);
	}

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
	public static boolean isExempt(int itemId, int sellPrice)
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
	public static int taxFor(int itemId, int sellPrice)
	{
		if (isExempt(itemId, sellPrice))
		{
			return 0;
		}
		return Math.min((int) Math.floor(sellPrice * GE_TAX_RATE), GE_TAX_CAP);
	}

	/**
	 * Per-item GE tax for a given sell price when the item id is not known to the
	 * caller (e.g. the pure-function offer-description formatter). Applies the
	 * price-based exemption (&le; 50gp) and cap, but cannot consult the
	 * exempt-item list. Prefer {@link #taxFor(int, int)} whenever an item id is
	 * available.
	 */
	public static int taxFor(int sellPrice)
	{
		if (sellPrice <= GE_TAX_EXEMPT_PRICE_THRESHOLD)
		{
			return 0;
		}
		return Math.min((int) Math.floor(sellPrice * GE_TAX_RATE), GE_TAX_CAP);
	}

	/**
	 * Item-aware breakeven: for items on the tax-free list the flip breaks even
	 * the moment the sell price covers the buy price (no tax to overcome), so
	 * the breakeven is simply the recorded buy price. Otherwise defers to the
	 * price-only calculation.
	 */
	public static int breakevenSellPrice(int itemId, int recordedBuyPrice)
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
	public static int breakevenSellPrice(int recordedBuyPrice)
	{
		if (recordedBuyPrice <= GE_TAX_EXEMPT_PRICE_THRESHOLD)
		{
			return recordedBuyPrice;
		}
		// Start from the closed-form estimate (overshoots by 1 due to ceiling),
		// walk up past the cap/floor-truncation region, then down to guarantee
		// minimality.
		int candidate = (int) Math.ceil(recordedBuyPrice / (1.0 - GE_TAX_RATE));
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
