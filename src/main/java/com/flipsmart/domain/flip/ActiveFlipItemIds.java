package com.flipsmart.domain.flip;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Items with a flip still in flight: anything on a GE slot, plus collected lots the player
 * still holds.
 *
 * <p>The collected set is persisted per-RSN and restored at login, and it is never rewritten
 * while empty (so a transient empty during a hop cannot wipe it). An item sold in a later
 * session therefore lingers in that set indefinitely. Counting such an entry keeps a finished
 * flip alive forever, which pins a free user at their slot cap through refreshes and relogs.
 * Inventory is the proof a collected lot is still open.</p>
 */
public final class ActiveFlipItemIds
{
	private ActiveFlipItemIds()
	{
	}

	/**
	 * @param liveOfferItemIds items on a GE slot; always active, since a listed sell is out
	 *                         of the inventory by definition
	 * @param collectedItemIds bought-and-collected lots, possibly stale from a restore
	 * @param inventoryCounts  canonical item id to held quantity
	 * @param inventoryKnown   false when no inventory snapshot is readable (logged out), in
	 *                         which case the collected set is trusted rather than discarded
	 */
	public static Set<Integer> derive(Set<Integer> liveOfferItemIds, Set<Integer> collectedItemIds,
									  Map<Integer, Integer> inventoryCounts, boolean inventoryKnown)
	{
		Set<Integer> active = new HashSet<>();
		if (liveOfferItemIds != null)
		{
			active.addAll(liveOfferItemIds);
		}
		if (collectedItemIds == null)
		{
			return active;
		}
		if (!inventoryKnown)
		{
			active.addAll(collectedItemIds);
			return active;
		}
		Map<Integer, Integer> counts = inventoryCounts == null ? Collections.emptyMap() : inventoryCounts;
		for (Integer itemId : collectedItemIds)
		{
			if (itemId != null && counts.getOrDefault(itemId, 0) > 0)
			{
				active.add(itemId);
			}
		}
		return active;
	}
}
