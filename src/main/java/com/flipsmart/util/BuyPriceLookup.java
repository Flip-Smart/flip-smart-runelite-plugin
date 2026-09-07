package com.flipsmart.util;
import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.offer.OfferRecord;

import java.util.ArrayList;
import java.util.Comparator;
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

	/** Convenience overload with no held-quantity hint; the records fallback averages every buy. */
	public static Integer findAverageBuyPriceWithFallback(
		List<ActiveFlip> activeFlips, Integer cycleBasis, List<OfferRecord> offerRecords, int itemId)
	{
		return findAverageBuyPriceWithFallback(activeFlips, cycleBasis, offerRecords, itemId, 0);
	}

	/**
	 * Resolve the recorded average buy price, in descending order of authority: the backend
	 * active-flips snapshot, then the ledger's open-cycle basis, then the offer-record fallback.
	 * The record store never evicts, so it can describe stock already sold; {@code heldQuantity}
	 * (from the ledger) bounds the fallback to the most-recent buys covering what's still held.
	 * Non-positive means the holding is unknown (cold start / pre-ledger), so every record counts.
	 *
	 * @return the average buy price, or {@code null} if no source knows the item.
	 */
	public static Integer findAverageBuyPriceWithFallback(
		List<ActiveFlip> activeFlips, Integer cycleBasis, List<OfferRecord> offerRecords, int itemId,
		int heldQuantity)
	{
		Integer fromFlips = findAverageBuyPrice(activeFlips, itemId);
		if (fromFlips != null)
		{
			return fromFlips;
		}
		if (cycleBasis != null && cycleBasis > 0)
		{
			return cycleBasis;
		}
		return averageBuyPriceFromOffers(offerRecords, itemId, heldQuantity);
	}

	/**
	 * Quantity-weighted average buy price for the item's filled buy offers, walking the most-recent
	 * buys first (by monotonic offerId). A positive {@code heldQuantity} stops once that many units
	 * are covered, pro-rating the lot that straddles the boundary, so the basis describes the stock
	 * still held rather than positions already sold; a non-positive value counts every recorded buy.
	 * {@code null} when none carry a fill. Sells and other items are ignored.
	 */
	static Integer averageBuyPriceFromOffers(List<OfferRecord> offerRecords, int itemId, int heldQuantity)
	{
		List<OfferRecord> buys = filledBuys(offerRecords, itemId);
		buys.sort(Comparator.comparingLong(OfferRecord::getOfferId).reversed());
		long remaining = heldQuantity > 0 ? heldQuantity : Long.MAX_VALUE;
		double spent = 0;
		long filled = 0;
		for (OfferRecord r : buys)
		{
			if (remaining <= 0)
			{
				break;
			}
			long take = Math.min(r.getFilledQuantity(), remaining);
			spent += r.getSpent() * (take / (double) r.getFilledQuantity());
			filled += take;
			remaining -= take;
		}
		return filled > 0 ? (int) Math.round(spent / filled) : null;
	}

	private static List<OfferRecord> filledBuys(List<OfferRecord> offerRecords, int itemId)
	{
		List<OfferRecord> buys = new ArrayList<>();
		if (offerRecords != null)
		{
			for (OfferRecord r : offerRecords)
			{
				if (r != null && r.isBuy() && r.getItemId() == itemId && r.getFilledQuantity() > 0)
				{
					buys.add(r);
				}
			}
		}
		return buys;
	}
}
