package com.flipsmart.session;

/**
 * An unsold position held during the session: the quantity still to be sold, plus the
 * basis and target used to value it. Deliberately not an {@code ActiveFlip} — a flip's
 * quantity is the offer's original size, which over-values a partially-filled sell.
 */
public final class OpenPosition
{
	public final int itemId;
	public final int unsoldQuantity;
	public final int averageBuyPrice;
	public final Integer recommendedSellPrice;

	public OpenPosition(int itemId, int unsoldQuantity, int averageBuyPrice, Integer recommendedSellPrice)
	{
		this.itemId = itemId;
		this.unsoldQuantity = unsoldQuantity;
		this.averageBuyPrice = averageBuyPrice;
		this.recommendedSellPrice = recommendedSellPrice;
	}
}
