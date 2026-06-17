package com.flipsmart;
import com.flipsmart.domain.offer.TrackedOffer;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
	private final Map<Integer, Integer> collectedQuantities = new ConcurrentHashMap<>();

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

	public void addCollectedItem(int itemId, int quantity)
	{
		collectedItemIds.add(itemId);
		if (quantity > 0)
		{
			collectedQuantities.put(itemId, quantity);
		}
	}

	public int getCollectedQuantity(int itemId)
	{
		Integer qty = collectedQuantities.get(itemId);
		return qty != null ? qty : 0;
	}

	public boolean removeCollectedItem(int itemId)
	{
		collectedQuantities.remove(itemId);
		return collectedItemIds.remove(itemId);
	}

	public void clearCollectedItems()
	{
		collectedItemIds.clear();
		collectedQuantities.clear();
	}

	public void restoreCollectedItems(Set<Integer> items)
	{
		collectedItemIds.clear();
		collectedQuantities.clear();
		if (items != null)
		{
			collectedItemIds.addAll(items);
		}
	}

	public void restoreCollectedItems(Set<Integer> items, Map<Integer, Integer> quantities)
	{
		collectedItemIds.clear();
		collectedQuantities.clear();
		if (items != null)
		{
			collectedItemIds.addAll(items);
		}
		if (quantities != null)
		{
			collectedQuantities.putAll(quantities);
		}
	}

	// =====================
	// GE State Methods
	// =====================

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
	 *
	 * Clears the cached RSN so a subsequent login as a different account does
	 * not reuse the previous account's name. Issue #549 / #556 — without this,
	 * an outgoing API call between logout and the next syncRSN() would still
	 * carry the previous account's RSN.
	 */
	public void onLogout()
	{
		this.loggedIntoRunescape = false;
		this.rsn = null;
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
		collectedQuantities.clear();
		trackedOffers.clear();
		recommendedPrices.clear();
		staleNotifiedAutoRecommendItemIds.clear();
	}

	// =====================
	// Query Methods
	// =====================

	/**
	 * Get a snapshot of collected item IDs for persistence.
	 * Returns a new HashSet to avoid concurrent modification during serialization.
	 */
	public Set<Integer> getCollectedItemsForPersistence()
	{
		return new HashSet<>(collectedItemIds);
	}

	/**
	 * Get a snapshot of collected quantities for persistence.
	 * Returns a new HashMap to avoid concurrent modification during serialization.
	 */
	public Map<Integer, Integer> getCollectedQuantitiesForPersistence()
	{
		return new HashMap<>(collectedQuantities);
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

	public void removeStaleNotified(int itemId)
	{
		staleNotifiedAutoRecommendItemIds.remove(itemId);
	}
}
