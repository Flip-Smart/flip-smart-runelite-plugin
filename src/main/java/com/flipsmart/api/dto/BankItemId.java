package com.flipsmart.api.dto;

/**
 * Data class for an inventory or gear item.
 * Backend prices these server-side, so no value_per_item is sent.
 */
public class BankItemId
{
	public final int itemId;
	public final int quantity;

	public BankItemId(int itemId, int quantity)
	{
		this.itemId = itemId;
		this.quantity = quantity;
	}
}
