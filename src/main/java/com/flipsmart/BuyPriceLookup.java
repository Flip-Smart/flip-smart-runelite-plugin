package com.flipsmart;

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
}
