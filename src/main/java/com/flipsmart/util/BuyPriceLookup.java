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
	 * <p>The offer records are the last resort. They carry the same cost basis but the store
	 * never evicts, so they outlive the flip they belong to and can average across positions
	 * already sold — the reason the cycle basis is preferred over them rather than the other way
	 * round. They still matter for a client whose ledger has not yet observed a fill.</p>
	 *
	 * @return the average buy price, or {@code null} if no source knows the item.
	 */
	public static Integer findAverageBuyPriceWithFallback(
		List<ActiveFlip> activeFlips, Integer cycleBasis, List<OfferRecord> offerRecords, int itemId)
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
		return filled > 0 ? (int) Math.round(spent / (double) filled) : null;
	}
}
