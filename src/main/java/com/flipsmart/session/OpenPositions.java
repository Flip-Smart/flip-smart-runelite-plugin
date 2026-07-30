package com.flipsmart.session;

import com.flipsmart.domain.flip.ActiveFlip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;

/**
 * Derives unsold positions from the Active Flips projection.
 *
 * A projected flip carries the sell offer's original quantity, so valuing it directly also
 * values the units that have already sold — and those are simultaneously counted in realised
 * profit, double-counting every partial fill in projected profit until the offer terminalizes.
 * Subtracting the filled quantity values only what is genuinely still unsold. Awaiting-sale
 * lots have no live sell offer, so their filled lookup is zero and their full quantity stands.
 *
 * {@code filledSellQuantityForItem} sums fills across every live sell offer of an item, not per
 * offer, so two simultaneous sell offers of the same item share one filled pool here — consumed
 * flip-by-flip — instead of each flip subtracting the item's whole filled total independently.
 *
 * Returns new instances; the projected flips are shared with the panel's cards and the
 * backend snapshot and are never mutated here.
 */
public final class OpenPositions
{
	private OpenPositions()
	{
	}

	public static List<OpenPosition> derive(List<ActiveFlip> projected,
											IntUnaryOperator filledSellQuantityForItem)
	{
		if (projected == null || projected.isEmpty())
		{
			return Collections.emptyList();
		}
		Map<Integer, Integer> unclaimedFilledByItem = new ConcurrentHashMap<>();
		List<OpenPosition> out = new ArrayList<>(projected.size());
		for (ActiveFlip flip : projected)
		{
			int itemId = flip.getItemId();
			int unclaimed = unclaimedFilledByItem.computeIfAbsent(itemId,
				id -> Math.max(0, filledSellQuantityForItem.applyAsInt(id)));
			int claimed = Math.min(unclaimed, flip.getTotalQuantity());
			unclaimedFilledByItem.put(itemId, unclaimed - claimed);
			int unsold = flip.getTotalQuantity() - claimed;
			out.add(new OpenPosition(itemId, unsold,
				flip.getAverageBuyPrice(), flip.getRecommendedSellPrice()));
		}
		return out;
	}
}
