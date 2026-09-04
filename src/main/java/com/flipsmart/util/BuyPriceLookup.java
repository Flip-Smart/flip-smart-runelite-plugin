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

	/**
	 * Resolve the recorded average buy price with no held-quantity hint — the offer-records
	 * fallback averages every recorded buy for the item. Retained for callers that cannot cheaply
	 * supply the held quantity; prefer the overload below, which scopes the fallback to the units
	 * still held so it cannot average across a lot already sold.
	 */
	public static Integer findAverageBuyPriceWithFallback(
		List<ActiveFlip> activeFlips, Integer cycleBasis, List<OfferRecord> offerRecords, int itemId)
	{
		return findAverageBuyPriceWithFallback(activeFlips, cycleBasis, offerRecords, itemId, 0);
	}

	/**
	 * Resolve the recorded average buy price, in descending order of authority.
	 *
	 * <p>The backend-sourced active-flips snapshot wins when present. It can be empty for reasons
	 * unrelated to whether the player holds the item — a refresh race, the free-tier trim, or a
	 * plain gap — and when it is, breakeven and profit used to render "?" despite the buy sitting
	 * locally.</p>
	 *
	 * <p>{@code cycleBasis} is the ledger's average for the currently-open round trip. It comes
	 * next because it is scoped to the position the player actually holds: a cycle closes when
	 * holdings return to zero, taking its basis with it.</p>
	 *
	 * <p>The offer records are the last resort. The store never evicts, so they outlive the flip
	 * they belong to and — averaged wholesale — describe stock already sold. {@code
	 * heldQuantity} (from the ledger) bounds the fallback to the most-recent buys making up the
	 * units still held; a non-positive value means the holding is unknown (cold start / pre-ledger),
	 * where every record is the only basis available.</p>
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
	 * Quantity-weighted average buy price for the item's filled buy offers. When {@code
	 * heldQuantity} is positive, only the most-recent buys (highest {@code offerId} first — ids are
	 * monotonic) covering that many units are counted, pro-rating the lot that straddles the
	 * boundary, so the basis describes the stock still held rather than positions already sold. A
	 * non-positive {@code heldQuantity} averages every recorded buy. {@code null} when none carry a
	 * fill. Sells and other items are ignored.
	 */
	static Integer averageBuyPriceFromOffers(List<OfferRecord> offerRecords, int itemId, int heldQuantity)
	{
		if (offerRecords == null)
		{
			return null;
		}
		List<OfferRecord> buys = filterBuys(offerRecords, itemId);
		return heldQuantity <= 0 ? calculateSimpleAverage(buys) : calculateWeightedAverageForHolding(buys, heldQuantity);
	}

	private static List<OfferRecord> filterBuys(List<OfferRecord> offerRecords, int itemId)
	{
		List<OfferRecord> buys = new ArrayList<>();
		for (OfferRecord r : offerRecords)
		{
			if (r != null && r.isBuy() && r.getItemId() == itemId && r.getFilledQuantity() > 0)
			{
				buys.add(r);
			}
		}
		return buys;
	}

	private static Integer calculateSimpleAverage(List<OfferRecord> buys)
	{
		long spent = 0;
		long filled = 0;
		for (OfferRecord r : buys)
		{
			spent += r.getSpent();
			filled += r.getFilledQuantity();
		}
		return filled > 0 ? (int) Math.round(spent / (double) filled) : null;
	}

	private static Integer calculateWeightedAverageForHolding(List<OfferRecord> buys, int heldQuantity)
	{
		buys.sort(Comparator.comparingLong(OfferRecord::getOfferId).reversed());
		double spent = 0;
		long filled = 0;
		int remaining = heldQuantity;
		for (OfferRecord r : buys)
		{
			if (remaining <= 0)
			{
				break;
			}
			int take = Math.min(r.getFilledQuantity(), remaining);
			spent += r.getSpent() * (take / (double) r.getFilledQuantity());
			filled += take;
			remaining -= take;
		}
		return filled > 0 ? (int) Math.round(spent / (double) filled) : null;
	}
}
