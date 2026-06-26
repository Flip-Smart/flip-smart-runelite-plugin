package com.flipsmart.api.dto;

/**
 * Data class for transaction request parameters (use Builder to construct)
 */
public class TransactionRequest
{
	public final int itemId;
	public final String itemName;
	public final boolean isBuy;
	public final int quantity;
	public final int pricePerItem;
	public final Integer geSlot;
	public final Integer recommendedSellPrice;
	public final String rsn;
	public final Integer totalQuantity;
	public final String idempotencyKey;
	public final Long offerId;

	private TransactionRequest(Builder builder)
	{
		this.itemId = builder.itemId;
		this.itemName = builder.itemName;
		this.isBuy = builder.isBuy;
		this.quantity = builder.quantity;
		this.pricePerItem = builder.pricePerItem;
		this.geSlot = builder.geSlot;
		this.recommendedSellPrice = builder.recommendedSellPrice;
		this.rsn = builder.rsn;
		this.totalQuantity = builder.totalQuantity;
		this.idempotencyKey = builder.idempotencyKey;
		this.offerId = builder.offerId;
	}

	public static Builder builder(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem)
	{
		return new Builder(itemId, itemName, isBuy, quantity, pricePerItem);
	}

	public static class Builder
	{
		private final int itemId;
		private final String itemName;
		private final boolean isBuy;
		private final int quantity;
		private final int pricePerItem;
		private Integer geSlot;
		private Integer recommendedSellPrice;
		private String rsn;
		private Integer totalQuantity;
		private String idempotencyKey;
		private Long offerId;

		private Builder(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.isBuy = isBuy;
			this.quantity = quantity;
			this.pricePerItem = pricePerItem;
		}

		public Builder geSlot(Integer geSlot) { this.geSlot = geSlot; return this; }
		public Builder recommendedSellPrice(Integer price) { this.recommendedSellPrice = price; return this; }
		public Builder rsn(String rsn) { this.rsn = rsn; return this; }
		public Builder totalQuantity(Integer qty) { this.totalQuantity = qty; return this; }
		public Builder idempotencyKey(String key) { this.idempotencyKey = key; return this; }
		public Builder offerId(Long offerId) { this.offerId = offerId; return this; }

		public TransactionRequest build() { return new TransactionRequest(this); }
	}
}
