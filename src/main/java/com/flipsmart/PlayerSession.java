package com.flipsmart;

import com.flipsmart.recommend.CollectOrigin;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

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
	@Setter
	private volatile String rsn;

	@Getter
	@Setter
	private volatile boolean loggedIntoRunescape;

	@Getter
	@Setter
	private volatile int lastLoginTick;

	// =====================
	// Player Inventory
	// =====================

	@Getter
	private volatile int currentCashStack;

	private final Set<Integer> collectedItemIds = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Integer> collectedQuantities = new ConcurrentHashMap<>();
	private final Map<Integer, CollectOrigin> collectOrigins = new ConcurrentHashMap<>();
	private final Map<Integer, Long> collectedAtMillis = new ConcurrentHashMap<>();

	// Items whose flip was started from a Flip Finder recommendation (focused buy).
	// Only these count toward the free-tier Flip Finder cap; manual listings never do.
	private final Set<Integer> flipFinderSourcedItems = ConcurrentHashMap.newKeySet();
	// When each sourced item was marked. A just-marked item is protected from pruning for a
	// short grace window so a still-propagating GE offer (not yet in the offer store) isn't
	// dropped before it lands — otherwise Auto's immediate re-resolve under-counts the cap.
	private final Map<Integer, Long> flipFinderSourcedAtMs = new ConcurrentHashMap<>();
	private static final long FLIP_FINDER_SOURCED_GRACE_MS = 8000;

	// =====================
	// GE State
	// =====================

	private final Map<Integer, Integer> recommendedPrices = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> originalMargins = new ConcurrentHashMap<>();
	// =====================
	// Sync Status
	// =====================

	@Getter
	@Setter
	private volatile boolean offlineSyncCompleted;

	// =====================
	// Feature State
	// =====================

	@Getter
	@Setter
	private volatile boolean bankSnapshotInProgress;

	@Getter
	@Setter
	private volatile long lastBankSnapshotAttempt;

	@Getter
	@Setter
	private volatile long lastFlipFinderRefresh;

	// =====================
	// Auto-Recommend State
	// =====================

	/** Items already notified as stale during auto-recommend (avoid spamming) */
	private final Set<Integer> staleNotifiedAutoRecommendItemIds = ConcurrentHashMap.newKeySet();

	// =====================
	// Session Identity Methods
	// =====================

	public Optional<String> getRsnSafe()
	{
		String currentRsn = this.rsn;
		if (currentRsn != null && !currentRsn.isEmpty())
		{
			return Optional.of(currentRsn);
		}
		return Optional.empty();
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

	public void addCollectedItem(int itemId, int quantity, CollectOrigin origin, long nowMillis)
	{
		addCollectedItem(itemId, quantity);
		if (origin != null)
		{
			collectOrigins.put(itemId, origin);
		}
		collectedAtMillis.put(itemId, nowMillis);
	}

	public int getCollectedQuantity(int itemId)
	{
		Integer qty = collectedQuantities.get(itemId);
		return qty != null ? qty : 0;
	}

	public CollectOrigin getCollectOrigin(int itemId)
	{
		return collectOrigins.get(itemId);
	}

	public long getCollectedAtMillis(int itemId)
	{
		Long ts = collectedAtMillis.get(itemId);
		return ts == null ? 0L : ts;
	}

	public boolean removeCollectedItem(int itemId)
	{
		collectedQuantities.remove(itemId);
		collectOrigins.remove(itemId);
		collectedAtMillis.remove(itemId);
		return collectedItemIds.remove(itemId);
	}

	/** Mark an item as sourced from a Flip Finder recommendation (a focused buy was placed). */
	public void markFlipFinderSourced(int itemId, long nowMs)
	{
		flipFinderSourcedItems.add(itemId);
		flipFinderSourcedAtMs.put(itemId, nowMs);
	}

	public Set<Integer> getFlipFinderSourcedItems()
	{
		return Collections.unmodifiableSet(flipFinderSourcedItems);
	}

	/**
	 * Restore the Flip Finder-sourced set from persistence (survives a client restart).
	 * Restored items get a fresh grace stamp so they are counted while the persisted offers
	 * reload into the store, rather than being pruned before they reappear as active flips.
	 */
	public void restoreFlipFinderSourced(Set<Integer> items, long nowMs)
	{
		flipFinderSourcedItems.clear();
		flipFinderSourcedAtMs.clear();
		if (items != null)
		{
			for (Integer item : items)
			{
				flipFinderSourcedItems.add(item);
				flipFinderSourcedAtMs.put(item, nowMs);
			}
		}
	}

	/**
	 * Count the in-flight Flip Finder flips and prune resolved ones. An item counts if it is
	 * an active flip ({@code activeFlipItemIds} = live-offer ∪ collected) OR was marked within
	 * the grace window (its GE offer may still be propagating into the store). Items that are
	 * neither active nor within grace are dropped — the flip has resolved. The grace prevents
	 * Auto's immediate re-resolve from under-counting a buy it just placed.
	 */
	public int retainAndCountFlipFinderActive(Set<Integer> activeFlipItemIds, long nowMs)
	{
		int count = 0;
		java.util.Iterator<Integer> it = flipFinderSourcedItems.iterator();
		while (it.hasNext())
		{
			int item = it.next();
			Long markedAt = flipFinderSourcedAtMs.get(item);
			boolean withinGrace = markedAt != null && nowMs - markedAt < FLIP_FINDER_SOURCED_GRACE_MS;
			if (activeFlipItemIds.contains(item) || withinGrace)
			{
				count++;
			}
			else
			{
				it.remove();
				flipFinderSourcedAtMs.remove(item);
			}
		}
		return count;
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

	/**
	 * Per-item original margin (#918): the flip's per-unit margin captured when the buy was
	 * placed. This is the fixed baseline the Active Offer Advisor measures decay and the joint
	 * reduction budget against — a property of the active offer, not of the transient
	 * recommendation queue, so it persists until the item is flipped again.
	 */
	public Integer getOriginalMargin(int itemId)
	{
		return originalMargins.get(itemId);
	}

	public void setOriginalMargin(int itemId, int margin)
	{
		originalMargins.put(itemId, margin);
	}

	// =====================
	// Sync Status Methods
	// =====================

	// =====================
	// Feature State Methods
	// =====================




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
		collectOrigins.clear();
		collectedAtMillis.clear();
		recommendedPrices.clear();
		staleNotifiedAutoRecommendItemIds.clear();
		flipFinderSourcedItems.clear();
		flipFinderSourcedAtMs.clear();
	}

	// =====================
	// Query Methods
	// =====================

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
