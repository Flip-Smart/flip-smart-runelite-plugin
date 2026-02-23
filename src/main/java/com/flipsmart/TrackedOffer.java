package com.flipsmart;

import lombok.Getter;
import lombok.NoArgsConstructor;
import net.runelite.api.GrandExchangeOfferState;

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
		this(itemId, itemName, isBuy, totalQuantity, price, quantitySold, System.currentTimeMillis(), 0);
	}

	/**
	 * Constructor with explicit timestamp (for login restoration)
	 */
	public TrackedOffer(int itemId, String itemName, boolean isBuy, int totalQuantity, int price, int quantitySold, long createdAtMillis)
	{
		this(itemId, itemName, isBuy, totalQuantity, price, quantitySold, createdAtMillis, 0);
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
	 * Check if a GE offer state represents a buy-side offer.
	 */
	public static boolean isBuyState(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING ||
			state == GrandExchangeOfferState.BOUGHT ||
			state == GrandExchangeOfferState.CANCELLED_BUY;
	}

	/**
	 * Create a TrackedOffer preserving timestamps from an existing offer when available.
	 * Falls back to the current time if no existing offer is present.
	 */
	public static TrackedOffer createWithPreservedTimestamps(
		int itemId, String itemName, int totalQuantity,
		int price, int quantitySold, TrackedOffer existing,
		GrandExchangeOfferState state)
	{
		long originalTimestamp = (existing != null && existing.getCreatedAtMillis() > 0)
			? existing.getCreatedAtMillis()
			: System.currentTimeMillis();
		long completedTimestamp = 0;

		if (state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD)
		{
			completedTimestamp = (existing != null && existing.getCompletedAtMillis() > 0)
				? existing.getCompletedAtMillis()
				: System.currentTimeMillis();
		}

		return new TrackedOffer(itemId, itemName, isBuyState(state), totalQuantity, price, quantitySold, originalTimestamp, completedTimestamp);
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
