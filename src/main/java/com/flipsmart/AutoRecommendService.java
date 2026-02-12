package com.flipsmart;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Manages the auto-recommend queue for cycling through flip recommendations.
 *
 * When active, this service automatically focuses recommendations into Flip Assist
 * one-by-one. When the user places a buy order, it advances to the next recommendation.
 * When a buy order completes, it queues the sell side for that item.
 *
 * Thread safety: All public methods are synchronized. Callbacks are dispatched
 * on the Swing EDT via SwingUtilities.invokeLater.
 */
@Slf4j
public class AutoRecommendService
{
	/** Maximum number of GE slots available per player */
	private static final int MAX_GE_SLOTS = 8;
	/** GE tax rate applied to sell transactions */
	private static final double GE_TAX_RATE = 0.98;
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

	// Pending sells: items that finished buying and need to be sold
	private final Queue<PendingSell> pendingSells = new ConcurrentLinkedQueue<>();

	// Timestamp of last queue refresh for staleness checks
	private volatile long lastQueueRefreshMillis;

	// Items already notified as stale (avoid spamming)
	private final Set<Integer> staleNotifiedItemIds = ConcurrentHashMap.newKeySet();

	// Callback to update Flip Assist overlay and panel
	private volatile Consumer<FocusedFlip> onFocusChanged;

	// Callback to update status text in the panel
	private volatile Consumer<String> onStatusChanged;

	// Callback when the queue advances (for panel highlight updates)
	private volatile Runnable onQueueAdvanced;

	/**
	 * Represents an item that completed buying and is waiting to be sold.
	 * Package-private for Gson serialization.
	 */
	static class PendingSell
	{
		final int itemId;
		final String itemName;
		final int sellPrice;
		final int quantity;

		PendingSell(int itemId, String itemName, int sellPrice, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.sellPrice = sellPrice;
			this.quantity = quantity;
		}
	}

	/**
	 * Serializable snapshot of auto-recommend state for persistence.
	 * Package-private for Gson serialization.
	 */
	static class PersistedState
	{
		boolean active;
		List<FlipRecommendation> queue;
		int currentIndex;
		List<PendingSell> pendingSells;
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

		Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();
		Set<Integer> geBuyItemIds = plugin.getCurrentGEBuyItemIds();

		recommendationQueue.clear();
		pendingSells.clear();
		staleNotifiedItemIds.clear();
		currentIndex = 0;

		for (FlipRecommendation rec : recommendations)
		{
			if (!activeItemIds.contains(rec.getItemId()) && !geBuyItemIds.contains(rec.getItemId()))
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
		pendingSells.clear();
		staleNotifiedItemIds.clear();
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
	 * Queues the sell for this item.
	 */
	public synchronized void onBuyOrderCompleted(int itemId, String itemName, int quantity, int buyPrice, Integer recommendedSellPrice)
	{
		if (!active)
		{
			return;
		}

		if (quantity <= 0 || buyPrice <= 0)
		{
			log.warn("Auto-recommend: Invalid buy completion params - qty={}, price={}", quantity, buyPrice);
			return;
		}

		int sellPrice;
		if (recommendedSellPrice != null && recommendedSellPrice > 0)
		{
			sellPrice = recommendedSellPrice;
		}
		else
		{
			sellPrice = (int) Math.ceil((buyPrice + 1) / GE_TAX_RATE);
		}

		pendingSells.add(new PendingSell(itemId, itemName, sellPrice, quantity));
		log.info("Auto-recommend: Buy complete for {} x{} - queued sell at {} gp", itemName, quantity, sellPrice);

		updateStatus(String.format("Auto: Collect %s from GE", itemName));

		// If we're not currently focused on a buy recommendation, focus sell
		FlipRecommendation currentRec = getCurrentRecommendation();
		if (currentRec == null || !hasAvailableGESlots())
		{
			focusNextPendingSell();
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

		pendingSells.removeIf(ps -> ps.itemId == itemId);

		log.info("Auto-recommend: Sell order placed for item {} - checking next action", itemId);

		if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
		{
			focusCurrent();
		}
		else if (!pendingSells.isEmpty())
		{
			focusNextPendingSell();
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
	 * Re-evaluates focus based on current state.
	 */
	public synchronized void onOfferCollected(int itemId, boolean wasBuy)
	{
		if (!active)
		{
			return;
		}

		if (wasBuy)
		{
			// User collected bought items - focus the sell side if pending
			boolean hasPendingSell = pendingSells.stream().anyMatch(ps -> ps.itemId == itemId);
			if (hasPendingSell)
			{
				log.info("Auto-recommend: Buy collected for {} - focusing sell", itemId);
				focusNextPendingSell();
			}
			else if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
			{
				focusCurrent();
			}
		}
		else
		{
			// User collected sell GP - advance to next action
			log.info("Auto-recommend: Sell collected for {} - advancing", itemId);
			if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
			{
				focusCurrent();
			}
			else if (!pendingSells.isEmpty())
			{
				focusNextPendingSell();
			}
			else if (currentIndex >= recommendationQueue.size() && pendingSells.isEmpty())
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

		// Filter new recommendations
		Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();
		Set<Integer> geBuyItemIds = plugin.getCurrentGEBuyItemIds();

		List<FlipRecommendation> filtered = new ArrayList<>();
		for (FlipRecommendation rec : newRecommendations)
		{
			if (!activeItemIds.contains(rec.getItemId()) && !geBuyItemIds.contains(rec.getItemId()))
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
		staleNotifiedItemIds.clear();

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

		for (TrackedOffer offer : trackedOffers.values())
		{
			if (!offer.isBuy() || offer.getCompletedAtMillis() > 0)
			{
				continue;
			}

			int itemId = offer.getItemId();
			if (staleNotifiedItemIds.contains(itemId))
			{
				continue;
			}

			long age = now - offer.getCreatedAtMillis();
			if (age < INACTIVITY_TIMEOUT_MS)
			{
				continue;
			}

			// Item is stale - check if it's still recommended
			boolean stillRecommended = currentRecommendations != null
				&& currentRecommendations.stream().anyMatch(r -> r.getItemId() == itemId);

			staleNotifiedItemIds.add(itemId);

			if (stillRecommended)
			{
				updateStatus(String.format("Auto: %s slow fill - consider relisting at updated price",
					offer.getItemName()));
			}
			else
			{
				updateStatus(String.format("Auto: %s no longer recommended - consider cancelling",
					offer.getItemName()));
			}

			log.info("Auto-recommend: Stale offer detected for {} (age: {}m, still recommended: {})",
				offer.getItemName(), age / 60000, stillRecommended);
			// Only notify about one stale item at a time
			break;
		}
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
		state.pendingSells = new ArrayList<>(pendingSells);
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

		pendingSells.clear();
		if (state.pendingSells != null)
		{
			pendingSells.addAll(state.pendingSells);
		}

		staleNotifiedItemIds.clear();
		active = true;
		lastQueueRefreshMillis = state.savedAtMillis;

		log.info("Auto-recommend: Restored state with {} items in queue, index {}, {} pending sells",
			recommendationQueue.size(), currentIndex, pendingSells.size());

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
			if (!pendingSells.isEmpty())
			{
				focusNextPendingSell();
			}
			else
			{
				updateStatus("Auto: All recommendations listed");
			}
			return;
		}

		if (!hasAvailableGESlots())
		{
			updateStatus("Auto: All GE slots full");
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

	private void focusNextPendingSell()
	{
		PendingSell sell = pendingSells.peek();
		if (sell == null)
		{
			return;
		}

		int priceOffset = config.priceOffset();
		FocusedFlip focus = FocusedFlip.forSell(
			sell.itemId,
			sell.itemName,
			sell.sellPrice,
			sell.quantity,
			priceOffset
		);

		invokeFocusCallback(focus);
		invokeQueueAdvancedCallback();

		updateStatus(String.format("Auto: Sell %s @ %s gp",
			sell.itemName, GpUtils.formatGPWithSuffix(sell.sellPrice)));
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

	public int getPendingSellCount()
	{
		return pendingSells.size();
	}

	private boolean hasAvailableGESlots()
	{
		return plugin.getSession().getTrackedOffers().size() < MAX_GE_SLOTS;
	}
}
