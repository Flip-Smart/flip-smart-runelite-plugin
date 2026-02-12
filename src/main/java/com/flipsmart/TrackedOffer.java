package com.flipsmart;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Tracks a GE offer's state for transaction detection.
 * Serializable for persistence across sessions via Gson.
 */
@Getter
@NoArgsConstructor // Required for Gson deserialization
public class TrackedOffer
{
	private int itemId;
	private String itemName;
	private boolean isBuy;
	private int totalQuantity;
	private int price;
	private int previousQuantitySold;
	private long createdAtMillis;  // Timestamp when offer was created (for timer display)
	private long completedAtMillis;  // Timestamp when offer completed (0 if not complete)

	public TrackedOffer(int itemId, String itemName, boolean isBuy, int totalQuantity, int price, int quantitySold)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.isBuy = isBuy;
		this.totalQuantity = totalQuantity;
		this.price = price;
		this.previousQuantitySold = quantitySold;
		this.createdAtMillis = System.currentTimeMillis();
		this.completedAtMillis = 0;
	}

	/**
	 * Constructor with explicit timestamp (for login restoration)
	 */
	public TrackedOffer(int itemId, String itemName, boolean isBuy, int totalQuantity, int price, int quantitySold, long createdAtMillis)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.isBuy = isBuy;
		this.totalQuantity = totalQuantity;
		this.price = price;
		this.previousQuantitySold = quantitySold;
		this.createdAtMillis = createdAtMillis;
		this.completedAtMillis = 0;
	}

	/**
	 * Constructor with explicit timestamps (for preserving completion state)
	 */
	public TrackedOffer(int itemId, String itemName, boolean isBuy, int totalQuantity, int price, int quantitySold, long createdAtMillis, long completedAtMillis)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.isBuy = isBuy;
		this.totalQuantity = totalQuantity;
		this.price = price;
		this.previousQuantitySold = quantitySold;
		this.createdAtMillis = createdAtMillis;
		this.completedAtMillis = completedAtMillis;
	}

	/**
	 * Check if this offer has completed (fully filled).
	 */
	public boolean isCompleted()
	{
		return completedAtMillis > 0;
	}

	/**
	 * Update the quantity sold for this offer.
	 */
	public void setPreviousQuantitySold(int quantitySold)
	{
		this.previousQuantitySold = quantitySold;
	}

	/**
	 * Mark this offer as completed.
	 */
	public void setCompletedAtMillis(long completedAtMillis)
	{
		this.completedAtMillis = completedAtMillis;
	}

	/**
	 * Set the creation timestamp.
	 */
	public void setCreatedAtMillis(long createdAtMillis)
	{
		this.createdAtMillis = createdAtMillis;
	}
}
