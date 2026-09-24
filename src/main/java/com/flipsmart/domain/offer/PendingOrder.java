package com.flipsmart.domain.offer;

/**
 * Represents a pending buy order in a GE slot.
 */
public class PendingOrder
{
	public final int itemId;
	public final String itemName;
	public final int quantity;        // Total quantity ordered
	public final int quantityFilled;  // How many have been filled so far
	public final long pricePerItem;
	public final Long recommendedSellPrice;
	public final int slot;

	public PendingOrder(int itemId, String itemName, int quantity, int quantityFilled, long pricePerItem, Long recommendedSellPrice, int slot)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.quantity = quantity;
		this.quantityFilled = quantityFilled;
		this.pricePerItem = pricePerItem;
		this.recommendedSellPrice = recommendedSellPrice;
		this.slot = slot;
	}
}
