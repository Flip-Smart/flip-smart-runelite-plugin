package com.flipsmart;

import lombok.Getter;

/**
 * Represents a single entry from the in-game GE History tab (interface 383).
 */
@Getter
public class GEHistoryEntry
{
	private final int itemId;
	private final boolean isBuy;
	private final int quantity;
	private final int pricePerItem;

	public GEHistoryEntry(int itemId, boolean isBuy, int quantity, int pricePerItem)
	{
		this.itemId = itemId;
		this.isBuy = isBuy;
		this.quantity = quantity;
		this.pricePerItem = pricePerItem;
	}

	@Override
	public String toString()
	{
		return String.format("GEHistoryEntry{%s itemId=%d, qty=%d, price=%d}",
			isBuy ? "BUY" : "SELL", itemId, quantity, pricePerItem);
	}
}
