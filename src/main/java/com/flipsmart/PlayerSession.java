package com.flipsmart;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized state management for a player's flip session.
 * Thread-safe: All mutable collections use concurrent implementations,
 * and primitive fields use volatile for cross-thread visibility.
 */
@Slf4j
public class PlayerSession
{
	// =====================
	// Session Identity
	// =====================

	@Getter
	private volatile String rsn;

	@Getter
	private volatile boolean loggedIntoRunescape;

	@Getter
	private volatile int lastLoginTick;

	// =====================
	// Player Inventory
	// =====================

	@Getter
	private volatile int currentCashStack;

	private final Set<Integer> collectedItemIds = ConcurrentHashMap.newKeySet();

	// =====================
	// GE State
	// =====================

	private final Map<Integer, TrackedOffer> trackedOffers = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> recommendedPrices = new ConcurrentHashMap<>();

	// =====================
	// Sync Status
	// =====================

	@Getter
	private volatile boolean offlineSyncCompleted;

	// =====================
	// Feature State
	// =====================

	@Getter
	private volatile boolean bankSnapshotInProgress;

	@Getter
	private volatile long lastBankSnapshotAttempt;

	@Getter
	private volatile long lastFlipFinderRefresh;

	// =====================
	// Auto-Recommend State
	// =====================

	/** Items already notified as stale during auto-recommend (avoid spamming) */
	private final Set<Integer> staleNotifiedAutoRecommendItemIds = ConcurrentHashMap.newKeySet();

	// =====================
	// Session Identity Methods
	// =====================

	public void setRsn(String rsn)
	{
		this.rsn = rsn;
	}

	public Optional<String> getRsnSafe()
	{
		String currentRsn = this.rsn;
		if (currentRsn != null && !currentRsn.isEmpty())
		{
			return Optional.of(currentRsn);
		}
		return Optional.empty();
	}

	public void setLoggedIntoRunescape(boolean loggedIn)
	{
		this.loggedIntoRunescape = loggedIn;
	}

	public void setLastLoginTick(int tick)
	{
		this.lastLoginTick = tick;
	}

	// =====================
	// Player Inventory Methods
	// =====================

	public void setCashStack(int amount)
	{
		this.currentCashStack = amount;
	}

	public Set<Integer> getCollectedItemIds()
	{
		return Collections.unmodifiableSet(collectedItemIds);
	}

	public void addCollectedItem(int itemId)
	{
		collectedItemIds.add(itemId);
	}

	public boolean removeCollectedItem(int itemId)
	{
		return collectedItemIds.remove(itemId);
	}

	public void clearCollectedItems()
	{
		collectedItemIds.clear();
	}

	public void restoreCollectedItems(Set<Integer> items)
	{
		collectedItemIds.clear();
		if (items != null)
		{
			collectedItemIds.addAll(items);
		}
	}

	// =====================
	// GE State Methods
	// =====================

	public Map<Integer, TrackedOffer> getTrackedOffers()
	{
		return Collections.unmodifiableMap(trackedOffers);
	}

	public TrackedOffer getTrackedOffer(int slot)
	{
		return trackedOffers.get(slot);
	}

	public void putTrackedOffer(int slot, TrackedOffer offer)
	{
		trackedOffers.put(slot, offer);
	}

	public TrackedOffer removeTrackedOffer(int slot)
	{
		return trackedOffers.remove(slot);
	}

	public void clearTrackedOffers()
	{
		trackedOffers.clear();
	}

	public void restoreTrackedOffers(Map<Integer, TrackedOffer> offers)
	{
		trackedOffers.clear();
		if (offers != null)
		{
			trackedOffers.putAll(offers);
		}
	}

	/**
	 * Get a snapshot of tracked offers for persistence.
	 * Returns a new HashMap to avoid concurrent modification during serialization.
	 */
	public Map<Integer, TrackedOffer> getOffersForPersistence()
	{
		return new HashMap<>(trackedOffers);
	}

	// =====================
	// Recommended Prices Methods
	// =====================

	public Integer getRecommendedPrice(int itemId)
	{
		return recommendedPrices.get(itemId);
	}

	public void setRecommendedPrice(int itemId, int price)
	{
		recommendedPrices.put(itemId, price);
		log.debug("Stored recommended sell price for item {}: {}", itemId, price);
	}

	public void removeRecommendedPrice(int itemId)
	{
		recommendedPrices.remove(itemId);
	}

	public void clearRecommendedPrices()
	{
		recommendedPrices.clear();
	}

	// =====================
	// Sync Status Methods
	// =====================

	public void setOfflineSyncCompleted(boolean completed)
	{
		this.offlineSyncCompleted = completed;
	}

	// =====================
	// Feature State Methods
	// =====================

	public void setBankSnapshotInProgress(boolean inProgress)
	{
		this.bankSnapshotInProgress = inProgress;
	}

	public void setLastBankSnapshotAttempt(long timestamp)
	{
		this.lastBankSnapshotAttempt = timestamp;
	}

	public void setLastFlipFinderRefresh(long timestamp)
	{
		this.lastFlipFinderRefresh = timestamp;
	}

	// =====================
	// Lifecycle Methods
	// =====================

	/**
	 * Handle login state change (LOGGING_IN or HOPPING).
	 * Resets sync flags and records login tick.
	 */
	public void onLoginStateChange(int loginTick)
	{
		this.lastLoginTick = loginTick;
		this.offlineSyncCompleted = false;
		log.debug("Login state change detected, setting lastLoginTick to {}", loginTick);
	}

	/**
	 * Handle logout state change (LOGIN_SCREEN).
	 */
	public void onLogout()
	{
		this.loggedIntoRunescape = false;
	}

	/**
	 * Handle successful login (LOGGED_IN state).
	 */
	public void onLoggedIn()
	{
		this.loggedIntoRunescape = true;
	}

	/**
	 * Clear all session state (for shutdown).
	 */
	public void clear()
	{
		this.rsn = null;
		this.loggedIntoRunescape = false;
		this.lastLoginTick = 0;
		this.currentCashStack = 0;
		this.offlineSyncCompleted = false;
		this.bankSnapshotInProgress = false;
		this.lastBankSnapshotAttempt = 0;
		this.lastFlipFinderRefresh = 0;
		collectedItemIds.clear();
		trackedOffers.clear();
		recommendedPrices.clear();
		staleNotifiedAutoRecommendItemIds.clear();
	}

	// =====================
	// Query Methods
	// =====================

	/**
	 * Get the set of item IDs currently in GE buy slots.
	 */
	public Set<Integer> getCurrentGEBuyItemIds()
	{
		Set<Integer> itemIds = new HashSet<>();

		for (TrackedOffer offer : trackedOffers.values())
		{
			if (offer.isBuy())
			{
				itemIds.add(offer.getItemId());
			}
		}

		return itemIds;
	}

	/**
	 * Get all active flip item IDs - items that should show as active flips.
	 * This includes:
	 * 1. Items currently in GE buy slots (pending or filled)
	 * 2. Items currently in GE sell slots (pending sale)
	 * 3. Items collected from GE in this session (waiting to be sold)
	 */
	public Set<Integer> getActiveFlipItemIds()
	{
		Set<Integer> itemIds = new HashSet<>();

		// Add items currently in ANY GE slots (buy OR sell)
		for (TrackedOffer offer : trackedOffers.values())
		{
			itemIds.add(offer.getItemId());
		}

		// Add items collected from GE (waiting to be sold)
		itemIds.addAll(collectedItemIds);

		return itemIds;
	}

	/**
	 * Check if an item has an active sell slot in the GE.
	 */
	public boolean hasActiveSellSlotForItem(int itemId)
	{
		for (TrackedOffer offer : trackedOffers.values())
		{
			if (offer.getItemId() == itemId && !offer.isBuy())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Check if an item has an active buy slot in the GE.
	 */
	public boolean hasActiveBuySlotForItem(int itemId)
	{
		for (TrackedOffer offer : trackedOffers.values())
		{
			if (offer.getItemId() == itemId && offer.isBuy())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Check if there are available GE slots for new offers.
	 */
	public boolean hasAvailableGESlots()
	{
		return trackedOffers.size() < 8;
	}

	/**
	 * Get tracked offers that have completed (BOUGHT or SOLD state, ready to collect).
	 */
	public List<TrackedOffer> getCompletedOffers()
	{
		List<TrackedOffer> completed = new ArrayList<>();
		for (TrackedOffer offer : trackedOffers.values())
		{
			if (offer.isCompleted())
			{
				completed.add(offer);
			}
		}
		return completed;
	}

	/**
	 * Get a snapshot of collected item IDs for persistence.
	 * Returns a new HashSet to avoid concurrent modification during serialization.
	 */
	public Set<Integer> getCollectedItemsForPersistence()
	{
		return new HashSet<>(collectedItemIds);
	}

	// =====================
	// Auto-Recommend Stale Notification Methods
	// =====================

	public boolean isStaleNotified(int itemId)
	{
		return staleNotifiedAutoRecommendItemIds.contains(itemId);
	}

	public void addStaleNotified(int itemId)
	{
		staleNotifiedAutoRecommendItemIds.add(itemId);
	}

	public void clearStaleNotifications()
	{
		staleNotifiedAutoRecommendItemIds.clear();
	}
}
