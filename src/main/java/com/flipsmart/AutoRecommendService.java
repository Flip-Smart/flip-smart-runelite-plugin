package com.flipsmart;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Manages the auto-recommend queue for cycling through flip recommendations.
 *
 * When active, this service automatically focuses recommendations into Flip Assist
 * one-by-one. When the user places a buy order, it advances to the next recommendation.
 * When a buy order completes, it uses session state (collectedItemIds + recommendedPrices)
 * to guide the user through the sell side.
 *
 * Thread safety: All public methods are synchronized. Callbacks are dispatched
 * on the Swing EDT via SwingUtilities.invokeLater.
 */
@Slf4j
public class AutoRecommendService
{
	/** How long before a buy offer is considered stale (15 minutes) */
	private static final long INACTIVITY_TIMEOUT_MS = 15 * 60 * 1000L;
	/** Maximum age of persisted state before it's considered stale (30 minutes) */
	static final long MAX_PERSISTED_AGE_MS = 30 * 60 * 1000L;

	private final FlipSmartConfig config;
	private final FlipSmartPlugin plugin;

	// Queue state - guarded by synchronized(this)
	private final List<FlipRecommendation> recommendationQueue = new ArrayList<>();
	private int currentIndex;
	private volatile boolean active;

	// Timestamp of last queue refresh for staleness checks
	private volatile long lastQueueRefreshMillis;

	// Callback to update Flip Assist overlay and panel
	private volatile Consumer<FocusedFlip> onFocusChanged;

	// Callback to update status text in the panel
	private volatile Consumer<String> onStatusChanged;

	// Callback when the queue advances (for panel highlight updates)
	private volatile Runnable onQueueAdvanced;

	// Callback when auto-recommend stops itself (e.g., all slots full)
	private volatile Runnable onAutoStopped;

	/**
	 * Serializable snapshot of auto-recommend state for persistence.
	 * Package-private for Gson serialization.
	 */
	static class PersistedState
	{
		boolean active;
		List<FlipRecommendation> queue;
		int currentIndex;
		long savedAtMillis;
	}

	public AutoRecommendService(FlipSmartConfig config, FlipSmartPlugin plugin)
	{
		this.config = config;
		this.plugin = plugin;
	}

	public void setOnFocusChanged(Consumer<FocusedFlip> callback)
	{
		this.onFocusChanged = callback;
	}

	public void setOnStatusChanged(Consumer<String> callback)
	{
		this.onStatusChanged = callback;
	}

	public void setOnQueueAdvanced(Runnable callback)
	{
		this.onQueueAdvanced = callback;
	}

	public void setOnAutoStopped(Runnable callback)
	{
		this.onAutoStopped = callback;
	}

	public boolean isActive()
	{
		return active;
	}

	public long getLastQueueRefreshMillis()
	{
		return lastQueueRefreshMillis;
	}

	// =====================
	// Lifecycle
	// =====================

	/**
	 * Start auto-recommend with the given recommendations.
	 * Filters out items already in GE slots or active flips,
	 * then sorts by volume ascending (slowest-filling items first).
	 */
	public synchronized void start(List<FlipRecommendation> recommendations)
	{
		if (recommendations == null || recommendations.isEmpty())
		{
			updateStatus("Auto: No recommendations available");
			return;
		}

		// getActiveFlipItemIds() includes all GE buy/sell items + collected items
		Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();

		recommendationQueue.clear();
		plugin.getSession().clearStaleNotifications();
		currentIndex = 0;

		for (FlipRecommendation rec : recommendations)
		{
			if (!activeItemIds.contains(rec.getItemId()))
			{
				recommendationQueue.add(rec);
			}
		}

		if (recommendationQueue.isEmpty())
		{
			updateStatus("Auto: All recommendations already in GE");
			return;
		}

		// Sort by volume ascending - slowest items listed first
		recommendationQueue.sort(Comparator.comparingDouble(FlipRecommendation::getVolumePerHour));

		active = true;
		lastQueueRefreshMillis = System.currentTimeMillis();
		log.info("Auto-recommend started with {} items in queue (sorted by volume asc)", recommendationQueue.size());
		focusCurrent();
	}

	/**
	 * Stop auto-recommend and clear the queue.
	 */
	public synchronized void stop()
	{
		active = false;
		recommendationQueue.clear();
		plugin.getSession().clearStaleNotifications();
		currentIndex = 0;

		invokeFocusCallback(null);

		log.info("Auto-recommend stopped");
	}

	// =====================
	// GE Event Handlers
	// =====================

	/**
	 * Called when the user places a new buy order for the focused item.
	 */
	public synchronized void onBuyOrderPlaced(int itemId)
	{
		if (!active)
		{
			return;
		}

		FlipRecommendation current = getCurrentRecommendation();
		if (current == null || current.getItemId() != itemId)
		{
			return;
		}

		plugin.setRecommendedSellPrice(itemId, current.getRecommendedSellPrice());

		log.info("Auto-recommend: Buy order placed for {} - advancing to next", current.getItemName());
		advanceToNext();
	}

	/**
	 * Called when a buy order completes (fully bought).
	 * Prompts user to collect items. Sell price is already stored in session
	 * via setRecommendedSellPrice() when the buy order was placed.
	 */
	public synchronized void onBuyOrderCompleted(String itemName)
	{
		if (!active)
		{
			return;
		}

		log.info("Auto-recommend: Buy complete for {} - collect from GE to sell", itemName);
		updateStatus(String.format("Auto: Collect %s from GE", itemName));

		// If we're not currently focused on a buy recommendation, focus sell
		FlipRecommendation currentRec = getCurrentRecommendation();
		if (currentRec == null || !hasAvailableGESlots())
		{
			focusNextCollectedItemSell();
		}
	}

	/**
	 * Called when a sell order is placed for an item.
	 */
	public synchronized void onSellOrderPlaced(int itemId)
	{
		if (!active)
		{
			return;
		}

		log.info("Auto-recommend: Sell order placed for item {} - checking next action", itemId);

		if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
		{
			focusCurrent();
		}
		else if (hasCollectedItemsToSell())
		{
			focusNextCollectedItemSell();
		}
		else if (currentIndex >= recommendationQueue.size())
		{
			updateStatus("Auto: Queue complete");
		}
	}

	/**
	 * Called when a sell order fully completes (SOLD state).
	 * Prompts user to collect profit.
	 */
	public synchronized void onSellOrderCompleted(int itemId)
	{
		if (!active)
		{
			return;
		}

		log.info("Auto-recommend: Sell completed for item {} - collect profit", itemId);
		updateStatus("Auto: Collect profit from GE");
	}

	/**
	 * Called when a GE slot becomes empty (user collected items or GP).
	 * Re-evaluates focus based on current state using session's collected items.
	 *
	 * @param itemId The item that was collected
	 * @param wasBuy Whether the collected offer was a buy order
	 * @param itemName The item name (from the collected TrackedOffer, since it's already removed from session)
	 * @param quantity The filled quantity (from the collected TrackedOffer)
	 */
	public synchronized void onOfferCollected(int itemId, boolean wasBuy, String itemName, int quantity)
	{
		if (!active)
		{
			return;
		}

		if (wasBuy)
		{
			// User collected bought items - check if they need to sell
			// Session collectedItemIds is updated by the plugin before this call
			PlayerSession session = plugin.getSession();
			boolean needsSell = session.getCollectedItemIds().contains(itemId)
				&& session.getRecommendedPrice(itemId) != null;

			if (needsSell)
			{
				log.info("Auto-recommend: Buy collected for {} x{} - focusing sell", itemName, quantity);
				focusSellForItem(itemId, itemName, quantity);
			}
			else if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
			{
				focusCurrent();
			}
		}
		else
		{
			// User collected sell GP - advance to next action
			log.info("Auto-recommend: Sell collected for {} - advancing", itemName);
			if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
			{
				focusCurrent();
			}
			else if (hasCollectedItemsToSell())
			{
				focusNextCollectedItemSell();
			}
			else if (currentIndex >= recommendationQueue.size() && !hasCollectedItemsToSell())
			{
				updateStatus("Auto: Queue complete - all flips done!");
			}
		}
	}

	// =====================
	// Queue Refresh (AC4)
	// =====================

	/**
	 * Refresh the recommendation queue with new data from the API.
	 * Preserves the currently focused item and updates what comes next.
	 */
	public synchronized void refreshQueue(List<FlipRecommendation> newRecommendations)
	{
		if (!active || newRecommendations == null)
		{
			return;
		}

		FlipRecommendation currentRec = getCurrentRecommendation();

		// Filter new recommendations - getActiveFlipItemIds() includes all GE items + collected items
		Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();

		List<FlipRecommendation> filtered = new ArrayList<>();
		for (FlipRecommendation rec : newRecommendations)
		{
			if (!activeItemIds.contains(rec.getItemId()))
			{
				filtered.add(rec);
			}
		}

		// Sort by volume ascending
		filtered.sort(Comparator.comparingDouble(FlipRecommendation::getVolumePerHour));

		// Rebuild queue preserving current item at position 0
		recommendationQueue.clear();
		if (currentRec != null)
		{
			recommendationQueue.add(currentRec);
			// Add remaining items, excluding the current one
			for (FlipRecommendation rec : filtered)
			{
				if (rec.getItemId() != currentRec.getItemId())
				{
					recommendationQueue.add(rec);
				}
			}
			currentIndex = 0;
		}
		else
		{
			recommendationQueue.addAll(filtered);
			currentIndex = 0;
		}

		lastQueueRefreshMillis = System.currentTimeMillis();
		plugin.getSession().clearStaleNotifications();

		log.info("Auto-recommend: Queue refreshed with {} items", recommendationQueue.size());
		updateStatus(String.format("Auto: %d/%d - %s",
			currentIndex + 1, recommendationQueue.size(),
			currentRec != null ? currentRec.getItemName() : "Refreshed"));
	}

	// =====================
	// Inactivity Detection
	// =====================

	/**
	 * Check for buy offers that haven't filled within the inactivity timeout.
	 * Updates status to prompt user action. Does NOT auto-cancel.
	 */
	public synchronized void checkInactiveOffers(
		Map<Integer, TrackedOffer> trackedOffers,
		List<FlipRecommendation> currentRecommendations)
	{
		if (!active || trackedOffers == null)
		{
			return;
		}

		long now = System.currentTimeMillis();
		PlayerSession session = plugin.getSession();

		// Find the first stale buy offer that hasn't been notified yet
		TrackedOffer staleOffer = findFirstStaleOffer(trackedOffers, session, now);
		if (staleOffer == null)
		{
			return;
		}

		int itemId = staleOffer.getItemId();
		session.addStaleNotified(itemId);

		boolean stillRecommended = currentRecommendations != null
			&& currentRecommendations.stream().anyMatch(r -> r.getItemId() == itemId);

		if (stillRecommended)
		{
			updateStatus(String.format("Auto: %s slow fill - consider relisting at updated price",
				staleOffer.getItemName()));
		}
		else
		{
			updateStatus(String.format("Auto: %s no longer recommended - consider cancelling",
				staleOffer.getItemName()));
		}

		long age = now - staleOffer.getCreatedAtMillis();
		log.info("Auto-recommend: Stale offer detected for {} (age: {}m, still recommended: {})",
			staleOffer.getItemName(), age / 60000, stillRecommended);
	}

	/**
	 * Find the first tracked buy offer that is stale and hasn't been notified yet.
	 */
	private TrackedOffer findFirstStaleOffer(
		Map<Integer, TrackedOffer> trackedOffers,
		PlayerSession session,
		long now)
	{
		for (TrackedOffer offer : trackedOffers.values())
		{
			if (!offer.isBuy() || offer.getCompletedAtMillis() > 0)
			{
				continue;
			}

			long age = now - offer.getCreatedAtMillis();
			if (age >= INACTIVITY_TIMEOUT_MS && !session.isStaleNotified(offer.getItemId()))
			{
				return offer;
			}
		}
		return null;
	}

	// =====================
	// Persistence (AC7)
	// =====================

	/**
	 * Create a serializable snapshot of the current state.
	 */
	public synchronized PersistedState getStateForPersistence()
	{
		PersistedState state = new PersistedState();
		state.active = active;
		state.queue = new ArrayList<>(recommendationQueue);
		state.currentIndex = currentIndex;
		state.savedAtMillis = System.currentTimeMillis();
		return state;
	}

	/**
	 * Restore state from a persisted snapshot.
	 * Only restores if the state is not stale.
	 *
	 * @param state The persisted state to restore
	 * @param maxAgeMs Maximum age in milliseconds before considered stale
	 * @return true if state was restored
	 */
	public synchronized boolean restoreState(PersistedState state, long maxAgeMs)
	{
		if (state == null || !state.active)
		{
			return false;
		}

		long age = System.currentTimeMillis() - state.savedAtMillis;
		if (age > maxAgeMs)
		{
			log.info("Auto-recommend: Persisted state is stale ({}m old), not restoring", age / 60000);
			return false;
		}

		if (state.queue == null || state.queue.isEmpty())
		{
			return false;
		}

		recommendationQueue.clear();
		recommendationQueue.addAll(state.queue);
		currentIndex = Math.min(state.currentIndex, recommendationQueue.size() - 1);
		currentIndex = Math.max(0, currentIndex);

		plugin.getSession().clearStaleNotifications();
		active = true;
		lastQueueRefreshMillis = state.savedAtMillis;

		log.info("Auto-recommend: Restored state with {} items in queue, index {}",
			recommendationQueue.size(), currentIndex);

		focusCurrent();
		return true;
	}

	// =====================
	// Internal Navigation
	// =====================

	private void advanceToNext()
	{
		currentIndex++;

		while (currentIndex < recommendationQueue.size())
		{
			FlipRecommendation next = recommendationQueue.get(currentIndex);
			if (!plugin.getActiveFlipItemIds().contains(next.getItemId()))
			{
				break;
			}
			currentIndex++;
		}

		if (currentIndex >= recommendationQueue.size())
		{
			if (hasCollectedItemsToSell())
			{
				focusNextCollectedItemSell();
			}
			else
			{
				updateStatus("Auto: All recommendations listed");
			}
			return;
		}

		if (!hasAvailableGESlots())
		{
			log.info("Auto-recommend: All GE slots full - stopping auto-recommend");
			stop();
			invokeAutoStoppedCallback();
			return;
		}

		focusCurrent();
	}

	private void focusCurrent()
	{
		FlipRecommendation rec = getCurrentRecommendation();
		if (rec == null)
		{
			return;
		}

		int priceOffset = config.priceOffset();
		FocusedFlip focus = FocusedFlip.forBuy(
			rec.getItemId(),
			rec.getItemName(),
			rec.getRecommendedBuyPrice(),
			rec.getRecommendedQuantity(),
			rec.getRecommendedSellPrice(),
			priceOffset
		);

		invokeFocusCallback(focus);
		invokeQueueAdvancedCallback();

		updateStatus(String.format("Auto: %d/%d - %s",
			currentIndex + 1, recommendationQueue.size(), rec.getItemName()));
	}

	/**
	 * Focus the sell side for a specific item with known name and quantity.
	 * Used when we have direct info from the just-collected TrackedOffer.
	 */
	private void focusSellForItem(int itemId, String itemName, int quantity)
	{
		PlayerSession session = plugin.getSession();
		Integer sellPrice = session.getRecommendedPrice(itemId);

		if (sellPrice == null || sellPrice <= 0)
		{
			log.warn("Auto-recommend: No recommended sell price for {} ({})", itemName, itemId);
			focusNextCollectedItemSell();
			return;
		}

		int priceOffset = config.priceOffset();
		FocusedFlip focus = FocusedFlip.forSell(
			itemId,
			itemName,
			sellPrice,
			quantity,
			priceOffset
		);

		invokeFocusCallback(focus);
		invokeQueueAdvancedCallback();

		updateStatus(String.format("Auto: Sell %s @ %s gp",
			itemName, GpUtils.formatGPWithSuffix(sellPrice)));
	}

	/**
	 * Focus the sell side for the next collected item that needs selling.
	 * Uses session state to find items and falls back to recommendation queue
	 * for item name/quantity since TrackedOffer may have been removed.
	 */
	private void focusNextCollectedItemSell()
	{
		int sellableItemId = findNextSellableCollectedItem();
		if (sellableItemId < 0)
		{
			// No collected items need selling - check buy queue
			if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
			{
				focusCurrent();
			}
			else
			{
				updateStatus("Auto: Waiting for offers to complete");
			}
			return;
		}

		FlipRecommendation rec = findRecommendationForItem(sellableItemId);
		if (rec == null)
		{
			log.warn("Auto-recommend: Cannot find item name for collected item {}", sellableItemId);
			return;
		}

		Integer sellPrice = plugin.getSession().getRecommendedPrice(sellableItemId);
		int priceOffset = config.priceOffset();
		FocusedFlip focus = FocusedFlip.forSell(
			sellableItemId,
			rec.getItemName(),
			sellPrice,
			rec.getRecommendedQuantity(),
			priceOffset
		);

		invokeFocusCallback(focus);
		invokeQueueAdvancedCallback();

		updateStatus(String.format("Auto: Sell %s @ %s gp",
			rec.getItemName(), GpUtils.formatGPWithSuffix(sellPrice)));
	}

	/**
	 * Find the next collected item that needs selling.
	 * Returns the item ID, or -1 if none found.
	 */
	private int findNextSellableCollectedItem()
	{
		PlayerSession session = plugin.getSession();

		for (int itemId : session.getCollectedItemIds())
		{
			Integer sellPrice = session.getRecommendedPrice(itemId);
			boolean hasSellPrice = sellPrice != null && sellPrice > 0;
			if (hasSellPrice && !session.hasActiveSellSlotForItem(itemId))
			{
				return itemId;
			}
		}

		return -1;
	}

	/**
	 * Find a recommendation matching the given item ID from the queue.
	 */
	private FlipRecommendation findRecommendationForItem(int itemId)
	{
		for (FlipRecommendation rec : recommendationQueue)
		{
			if (rec.getItemId() == itemId)
			{
				return rec;
			}
		}
		return null;
	}

	/**
	 * Check if there are collected items that still need to be sold.
	 */
	private boolean hasCollectedItemsToSell()
	{
		return findNextSellableCollectedItem() >= 0;
	}

	// =====================
	// Callbacks
	// =====================

	private void invokeFocusCallback(FocusedFlip focus)
	{
		Consumer<FocusedFlip> callback = onFocusChanged;
		if (callback != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> callback.accept(focus));
		}
	}

	private void invokeQueueAdvancedCallback()
	{
		Runnable callback = onQueueAdvanced;
		if (callback != null)
		{
			javax.swing.SwingUtilities.invokeLater(callback);
		}
	}

	private void invokeAutoStoppedCallback()
	{
		Runnable callback = onAutoStopped;
		if (callback != null)
		{
			javax.swing.SwingUtilities.invokeLater(callback);
		}
	}

	private void updateStatus(String status)
	{
		log.debug("Auto-recommend status: {}", status);
		Consumer<String> callback = onStatusChanged;
		if (callback != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> callback.accept(status));
		}
	}

	// =====================
	// Queries
	// =====================

	public synchronized FlipRecommendation getCurrentRecommendation()
	{
		if (currentIndex >= 0 && currentIndex < recommendationQueue.size())
		{
			return recommendationQueue.get(currentIndex);
		}
		return null;
	}

	public synchronized int getCurrentIndex()
	{
		return currentIndex;
	}

	public synchronized int getQueueSize()
	{
		return recommendationQueue.size();
	}

	private boolean hasAvailableGESlots()
	{
		return plugin.getSession().hasAvailableGESlots();
	}
}
