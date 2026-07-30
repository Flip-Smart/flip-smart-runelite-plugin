package com.flipsmart.session;

import com.flipsmart.domain.flip.ActiveFlip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
		List<OpenPosition> out = new ArrayList<>(projected.size());
		for (ActiveFlip flip : projected)
		{
			int filled = Math.max(0, filledSellQuantityForItem.applyAsInt(flip.getItemId()));
			int unsold = Math.max(0, flip.getTotalQuantity() - filled);
			out.add(new OpenPosition(flip.getItemId(), unsold,
				flip.getAverageBuyPrice(), flip.getRecommendedSellPrice()));
		}
		return out;
	}
}
