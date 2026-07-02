package com.flipsmart.domain.flip;

import com.flipsmart.domain.offer.OfferRecord;

import java.util.List;

/**
 * Applies a collected GE offer to the locally-cached active-flips list so the
 * panel reflects the change before the next API-backed refresh. Deliberately
 * conservative: existing entries are never re-quantified locally (the coalesced
 * refresh reconciles numbers with the backend); this only adds a flip the
 * backend will create from a collected buy, and removes one a fully-filled
 * collected sell completes.
 */
public final class ActiveFlipLocalUpdater
{
	private ActiveFlipLocalUpdater()
	{
	}

	/**
	 * Apply a collected offer to {@code flips}.
	 *
	 * @return true when the list changed
	 */
	public static boolean applyCollect(List<ActiveFlip> flips, OfferRecord record, String nowIso)
	{
		if (record.isBuy())
		{
			return record.getFilledQuantity() > 0 && addIfAbsent(flips, record, nowIso);
		}
		if (record.getFilledQuantity() >= record.getTotalQuantity())
		{
			return flips.removeIf(f -> f.getItemId() == record.getItemId());
		}
		return false;
	}

	private static boolean addIfAbsent(List<ActiveFlip> flips, OfferRecord record, String nowIso)
	{
		for (ActiveFlip existing : flips)
		{
			if (existing.getItemId() == record.getItemId())
			{
				return false;
			}
		}

		int filled = record.getFilledQuantity();
		long spent = record.getSpent();
		int averageBuyPrice = spent > 0 ? (int) (spent / filled) : record.getPrice();

		ActiveFlip flip = new ActiveFlip();
		flip.setItemId(record.getItemId());
		flip.setItemName(record.getItemName());
		flip.setTotalQuantity(filled);
		flip.setOriginalQuantity(filled);
		flip.setAverageBuyPrice(averageBuyPrice);
		flip.setTotalInvested((int) Math.min(spent > 0 ? spent : (long) averageBuyPrice * filled, Integer.MAX_VALUE));
		flip.setFirstBuyTime(nowIso);
		flip.setLastBuyTime(nowIso);
		flip.setTransactionCount(1);
		flip.setPhase("buy");
		flips.add(flip);
		return true;
	}
}
