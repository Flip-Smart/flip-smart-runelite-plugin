package com.flipsmart.api.dto;

/**
 * Single GE History row, used by recordHistoryBackfillBatchAsync.
 */
public class HistoryBackfillEntry
{
	public final int itemId;
	public final String itemName;
	public final boolean isBuy;
	public final int quantity;
	public final int pricePerItem;

	public HistoryBackfillEntry(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.isBuy = isBuy;
		this.quantity = quantity;
		this.pricePerItem = pricePerItem;
	}
}
