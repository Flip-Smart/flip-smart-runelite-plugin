package com.flipsmart.domain.offer;

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
	private long previousSpent;  // cumulative GP spent/received — long to handle values > 2.1B
	private long createdAtMillis;  // Timestamp when offer was created (for timer display)
	private long completedAtMillis;  // Timestamp when offer completed (0 if not complete)
	private long lastActivityAtMillis;  // Timestamp of most recent fill (for timer display & stale detection)
	private String offerStage;

	public static final String STAGE_INITIAL = "initial";

	public TrackedOffer(int itemId, String itemName, boolean isBuy, int totalQuantity, int price, int quantitySold)
	{
		this(itemId, itemName, isBuy, totalQuantity, price, quantitySold, System.currentTimeMillis());
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
		this.lastActivityAtMillis = createdAtMillis;
		this.offerStage = STAGE_INITIAL;
	}

	/**
	 * Create a TrackedOffer preserving timestamps from an existing offer when available.
	 * Falls back to the current time if no existing offer is present (genuinely new offer).
	 *
	 * To ensure correct timestamps during login burst, callers should preload persisted
	 * offers into the session BEFORE this method is called. See OfflineSyncService.preloadPersistedOffers().
	 */
	public static TrackedOffer createWithPreservedTimestamps(
		int itemId, String itemName, int totalQuantity,
		int price, int quantitySold, TrackedOffer existing,
		GrandExchangeOfferState state)
	{
		// Only preserve timestamps from the same item — a slot reused for a
		// different item must start with a fresh timestamp.
		if (existing != null && existing.getItemId() != itemId)
		{
			existing = null;
		}

		long originalTimestamp = (existing != null && existing.getCreatedAtMillis() > 0)
			? existing.getCreatedAtMillis()
			: System.currentTimeMillis();

		TrackedOffer offer = new TrackedOffer(itemId, itemName, OfferSignal.isBuyState(state), totalQuantity, price, quantitySold, originalTimestamp);

		// Preserve lastActivityAtMillis from existing offer (or default to creation time)
		if (existing != null && existing.getLastActivityAtMillis() > 0)
		{
			offer.setLastActivityAtMillis(existing.getLastActivityAtMillis());
		}

		if (existing != null)
		{
			offer.setOfferStage(existing.getOfferStage());
		}

		if (state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD)
		{
			offer.setCompletedAtMillis(
				(existing != null && existing.getCompletedAtMillis() > 0)
					? existing.getCompletedAtMillis()
					: System.currentTimeMillis());
		}

		return offer;
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
	 * Update the cumulative GP spent/received for this offer.
	 */
	public void setPreviousSpent(long spent)
	{
		this.previousSpent = spent;
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

	/**
	 * Update the last activity timestamp (called on each partial fill).
	 */
	public void setLastActivityAtMillis(long lastActivityAtMillis)
	{
		this.lastActivityAtMillis = lastActivityAtMillis;
	}

	public String getOfferStage()
	{
		return offerStage == null ? STAGE_INITIAL : offerStage;
	}

	public void setOfferStage(String offerStage)
	{
		this.offerStage = offerStage;
	}

	/**
	 * Get the effective last activity time. Falls back to createdAtMillis
	 * for offers persisted before this field was added.
	 */
	public long getEffectiveLastActivityAtMillis()
	{
		return lastActivityAtMillis > 0 ? lastActivityAtMillis : createdAtMillis;
	}
}
