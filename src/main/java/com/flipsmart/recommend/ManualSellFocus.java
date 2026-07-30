package com.flipsmart.recommend;

/**
 * Sell pricing for a manual (Auto Mode off) exit, resolved entirely from local state.
 *
 * <p>The backend active-flips list is trimmed to the subscription's display slots, so a
 * held position can be missing from it while the item sits in the player's inventory.
 * A display cap must never stop someone selling what they already own, so the price is
 * sourced locally: the panel's shown price, then the session's stored recommendation,
 * then the offer store's buy basis.</p>
 */
public final class ManualSellFocus
{
	private ManualSellFocus()
	{
	}

	/**
	 * Best locally-known sell price, or null when nothing can price the exit.
	 *
	 * @param panelPrice              price the Active Flips card is showing, if any
	 * @param sessionRecommendedPrice recommendation cached for this item, if any
	 * @param averageBuyPrice         cost basis from the offer store; 0 when unknown
	 */
	public static Integer resolveSellPrice(Integer panelPrice, Integer sessionRecommendedPrice,
										   int averageBuyPrice)
	{
		if (panelPrice != null && panelPrice > 0)
		{
			return panelPrice;
		}
		if (sessionRecommendedPrice != null && sessionRecommendedPrice > 0)
		{
			return sessionRecommendedPrice;
		}
		if (averageBuyPrice > 0)
		{
			return SmartSellPricer.calculateMinProfitableSellPrice(averageBuyPrice);
		}
		return null;
	}
}
