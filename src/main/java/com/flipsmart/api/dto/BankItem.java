package com.flipsmart.api.dto;

/**
 * Data class for bank snapshot item
 */
public class BankItem
{
	public final int itemId;
	public final int quantity;
	public final int valuePerItem;

	public BankItem(int itemId, int quantity, int valuePerItem)
	{
		this.itemId = itemId;
		this.quantity = quantity;
		this.valuePerItem = valuePerItem;
	}
}
