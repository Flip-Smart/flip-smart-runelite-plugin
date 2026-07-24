package com.flipsmart.util;
import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.offer.OfferRecord;

import java.util.List;

/**
 * Single source of truth for resolving the player's recorded average buy price
 * for an item from their current active flips. Used by the GE slot-hover tooltip
 * (showing live P&amp;L) and the offer-setup window description (issue #665).
 */
public final class BuyPriceLookup
{
	private BuyPriceLookup()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Find the player's recorded average buy price for {@code itemId} from a
	 * snapshot of their current active flips.
	 *
	 * @return The recorded average buy price, or {@code null} if no active flip
	 *         exists for the item (or all matching flips have a non-positive price).
	 */
	public static Integer findAverageBuyPrice(List<ActiveFlip> activeFlips, int itemId)
	{
		if (activeFlips == null)
		{
			return null;
		}
		for (ActiveFlip flip : activeFlips)
		{
			if (flip.getItemId() == itemId && flip.getAverageBuyPrice() > 0)
			{
				return flip.getAverageBuyPrice();
			}
		}
		return null;
	}

	/**
	 * Resolve the recorded average buy price from the active-flips snapshot, and
	 * when that is absent, fall back to the local {@link OfferRecord} store
	 * (#1089 D4). The active-flips list is backend-sourced and can be empty for
	 * reasons unrelated to whether the player holds the item — a refresh race,
	 * the free-tier slot trim, or a plain gap — and when it is, breakeven/margin/
	 * profit rendered "?" despite the buy sitting in the OfferStore. The offer
	 * records carry the same cost basis, so the derived stats stay populated.
	 *
	 * @return the average buy price, or {@code null} if neither source knows the
	 *         item.
	 */
	public static Integer findAverageBuyPriceWithFallback(
		List<ActiveFlip> activeFlips, List<OfferRecord> offerRecords, int itemId)
	{
		Integer fromFlips = findAverageBuyPrice(activeFlips, itemId);
		if (fromFlips != null)
		{
			return fromFlips;
		}
		return averageBuyPriceFromOffers(offerRecords, itemId);
	}

	/**
	 * Quantity-weighted average buy price across the item's filled buy offers,
	 * or {@code null} when none carry a fill. Sells and other items are ignored.
	 */
	static Integer averageBuyPriceFromOffers(List<OfferRecord> offerRecords, int itemId)
	{
		if (offerRecords == null)
		{
			return null;
		}
		long spent = 0;
		long filled = 0;
		for (OfferRecord r : offerRecords)
		{
			if (r != null && r.isBuy() && r.getItemId() == itemId && r.getFilledQuantity() > 0)
			{
				spent += r.getSpent();
				filled += r.getFilledQuantity();
			}
		}
		return filled > 0 ? (int) (spent / filled) : null;
	}
}
