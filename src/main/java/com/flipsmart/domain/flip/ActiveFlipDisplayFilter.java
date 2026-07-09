package com.flipsmart.domain.flip;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which backend active flips are actually still in progress for the
 * logged-in player, so the Active Flips tab mirrors the real GE window rather
 * than lingering on completed, cancelled, or collected trades.
 *
 * A flip is retained only when its item is backed by live player state: an open
 * GE buy/sell offer, an item collected this session and awaiting sale, or an
 * item currently held in the inventory. The previous "bought within 7 days"
 * recency rule kept fully-sold flips visible for days and is deliberately gone —
 * activeness is a function of present state, not of how recently a buy happened.
 */
public final class ActiveFlipDisplayFilter
{
	private ActiveFlipDisplayFilter()
	{
	}

	/**
	 * @param flips            active flips reported by the backend (may be null)
	 * @param activeItemIds    item ids in a live GE offer or collected this session
	 * @param inventoryItemIds item ids currently in the player's inventory
	 * @return the flips whose item is present in live player state, in input order
	 */
	public static List<ActiveFlip> retain(List<ActiveFlip> flips, Set<Integer> activeItemIds,
		Set<Integer> inventoryItemIds)
	{
		List<ActiveFlip> retained = new ArrayList<>();
		if (flips == null)
		{
			return retained;
		}
		for (ActiveFlip flip : flips)
		{
			if (isBackedByLiveState(flip, activeItemIds, inventoryItemIds))
			{
				retained.add(flip);
			}
		}
		return retained;
	}

	private static boolean isBackedByLiveState(ActiveFlip flip, Set<Integer> activeItemIds,
		Set<Integer> inventoryItemIds)
	{
		int itemId = flip.getItemId();
		return (activeItemIds != null && activeItemIds.contains(itemId))
			|| (inventoryItemIds != null && inventoryItemIds.contains(itemId));
	}
}
