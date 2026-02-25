package com.flipsmart;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

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
	private static final String MSG_WAITING_FOR_FLIPS = "Waiting for flips";

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

	// Callback to update the Flip Assist overlay message (when no flip is focused)
	// ObjIntConsumer<message, itemId> — itemId <= 0 means no icon
	private volatile ObjIntConsumer<String> onOverlayMessageChanged;

	// Pending re-buy state: when a partially-filled buy is cancelled, track remaining qty
	private int pendingReBuyItemId = -1;
	private int pendingReBuyRemainingQty;

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

	public void setOnOverlayMessageChanged(ObjIntConsumer<String> callback)
	{
		this.onOverlayMessageChanged = callback;
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
		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return;
		}

		if (plugin.getApiClient().isRsnBlocked())
		{
			updateStatus("Auto: Subscribe to Premium for this account");
			return;
		}

		if (!plugin.isPremium() && !session.hasAvailableGESlots(plugin.getFlipSlotLimit()))
		{
			updateStatus("Auto: Upgrade to Premium for more flip slots");
			return;
		}

		if (recommendations == null || recommendations.isEmpty())
		{
			updateStatus("Auto: No recommendations available");
			return;
		}

		// getActiveFlipItemIds() includes all GE buy/sell items + collected items
		Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();

		recommendationQueue.clear();
		session.clearStaleNotifications();
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
		pendingReBuyItemId = -1;
		pendingReBuyRemainingQty = 0;
		PlayerSession session = plugin.getSession();
		if (session != null)
		{
			session.clearStaleNotifications();
		}
		currentIndex = 0;

		invokeFocusCallback(null);

		log.info("Auto-recommend stopped");
	}

	// =====================
	// Login Re-evaluation
	// =====================

	/**
	 * Re-evaluate the current state after login + offline sync.
	 * If auto-recommend was restored, check if collected items need selling
	 * or if GE slots opened up for new buys.
	 */
	public synchronized void reevaluateAfterLogin()
	{
		if (!active)
		{
			return;
		}

		log.info("Auto-recommend: Re-evaluating after login");
		focusNextAvailableAction();
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

		// Clear pending re-buy if this buy matches
		if (pendingReBuyItemId == itemId)
		{
			log.info("Auto-recommend: Re-buy placed for item {} - clearing pending re-buy state", itemId);
			pendingReBuyItemId = -1;
			pendingReBuyRemainingQty = 0;
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
	public synchronized void onBuyOrderCompleted(int itemId, String itemName)
	{
		if (!active)
		{
			return;
		}

		log.info("Auto-recommend: Buy complete for {} - collect from GE to sell", itemName);
		updateStatus("Auto: Collect " + itemName + " from GE");

		// When no GE slots are available, clear the buy focus and show collect overlay.
		// The hint box only displays when focusedFlip is null.
		// onOfferCollected will transition to sell when user actually collects.
		if (!hasAvailableGESlots())
		{
			invokeFocusCallback(null);
			invokeOverlayMessageCallback(itemName, itemId);
		}
	}

	/**
	 * Override the current auto-recommend focus to show a sell overlay for a collected item.
	 * Called when the user selects an inventory item to sell during auto-recommend.
	 * This is a temporary override — after the sell is placed, focusNextAvailableAction()
	 * resumes normal queue processing.
	 */
	public synchronized void overrideFocusForSell(int itemId, String itemName)
	{
		if (!active)
		{
			return;
		}

		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return;
		}

		Integer sellPrice = session.getRecommendedPrice(itemId);
		if (sellPrice == null || sellPrice <= 0)
		{
			log.warn("Auto-recommend: Cannot override focus for {} - no sell price available", itemName);
			return;
		}

		int collectedQty = session.getCollectedQuantity(itemId);
		// Fall back to recommendation quantity if collected qty not tracked
		if (collectedQty <= 0)
		{
			FlipRecommendation rec = findRecommendationForItem(itemId);
			collectedQty = rec != null ? rec.getRecommendedQuantity() : 0;
		}

		if (collectedQty <= 0)
		{
			log.warn("Auto-recommend: Cannot override focus for {} - no quantity available", itemName);
			return;
		}

		int priceOffset = config.priceOffset();
		FocusedFlip focus = FocusedFlip.forSell(itemId, itemName, sellPrice, collectedQty, priceOffset);

		invokeFocusCallback(focus);
		updateStatus(String.format("Auto: Sell %s @ %s gp", itemName, GpUtils.formatGPWithSuffix(sellPrice)));

		log.info("Auto-recommend: Override focus for sell {} x{} @ {} gp", itemName, collectedQty, sellPrice);
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

		// Priority: sell collected items first, then buy new ones
		if (hasCollectedItemsToSell())
		{
			focusNextCollectedItemSell();
		}
		else if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
		{
			focusCurrent();
		}
		else if (currentIndex >= recommendationQueue.size())
		{
			// Clear sell focus so the old sell overlay doesn't persist
			invokeFocusCallback(null);
			updateStatus("Auto: Queue complete");
			invokeOverlayMessageCallback("All flips listed - waiting for sells");
		}
		else
		{
			// Slots full after placing sell, wait for something to complete
			promptCollection();
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
	 * Called when any GE offer is cancelled. Routes to the appropriate handler
	 * based on offer type and fill state.
	 *
	 * @param itemId        the cancelled item
	 * @param wasBuy        true if the cancelled offer was a buy
	 * @param filledQuantity how many items were filled before cancellation
	 * @param totalQuantity  the original order quantity
	 */
	public synchronized void onOfferCancelled(int itemId, boolean wasBuy, int filledQuantity, int totalQuantity)
	{
		if (!active)
		{
			return;
		}

		if (wasBuy && filledQuantity > 0)
		{
			// Partial-fill buy cancel — delegate to re-buy flow
			FlipRecommendation rec = findRecommendationForItem(itemId);
			String itemName = rec != null ? rec.getItemName() : "Item " + itemId;
			onBuyOrderCancelled(itemId, itemName, filledQuantity, totalQuantity);
		}
		else
		{
			// Zero-fill buy cancel or any sell cancel — slot freed, serve next recommendation
			log.info("Auto-recommend: Offer cancelled (wasBuy={}, filled={}) - re-evaluating", wasBuy, filledQuantity);
			focusNextAvailableAction();
		}
	}

	/**
	 * Called when a buy order is cancelled with partial fills.
	 * Stores pending re-buy state so the user can re-list for the remaining quantity.
	 */
	public synchronized void onBuyOrderCancelled(int itemId, String itemName, int filledQuantity, int totalQuantity)
	{
		if (!active)
		{
			return;
		}

		int remaining = totalQuantity - filledQuantity;
		if (remaining <= 0)
		{
			log.info("Auto-recommend: Buy cancelled for {} but fully filled - no re-buy needed", itemName);
			return;
		}

		pendingReBuyItemId = itemId;
		pendingReBuyRemainingQty = remaining;

		log.info("Auto-recommend: Buy cancelled for {} - {} filled, {} remaining. Prompting re-buy.",
			itemName, filledQuantity, remaining);

		// Find the recommendation for this item to get the buy price
		FlipRecommendation rec = findRecommendationForItem(itemId);
		int buyPrice = rec != null ? rec.getRecommendedBuyPrice() : 0;

		if (buyPrice > 0)
		{
			int priceOffset = config.priceOffset();
			FocusedFlip focus = FocusedFlip.forBuy(
				itemId,
				itemName,
				buyPrice,
				remaining,
				rec.getRecommendedSellPrice(),
				priceOffset
			);
			invokeFocusCallback(focus);
			updateStatus(String.format("Auto: Re-buy %s x%s @ %s gp",
				itemName, GpUtils.formatGPWithSuffix(remaining), GpUtils.formatGPWithSuffix(buyPrice)));
		}
		else
		{
			updateStatus(String.format("Auto: Re-buy %s x%s", itemName, GpUtils.formatGPWithSuffix(remaining)));
		}
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
			handleBuyCollected(itemId, itemName, quantity);
		}
		else
		{
			log.info("Auto-recommend: Sell collected for {} - advancing", itemName);
			focusNextAvailableAction();
		}
	}

	/**
	 * Handle collection of a completed buy offer.
	 * If there's a pending re-buy for this item, focus re-buy instead of sell.
	 * Priority: re-buy (if pending) > sell THIS item > sell OTHER collected items > buy next > wait.
	 */
	private void handleBuyCollected(int itemId, String itemName, int quantity)
	{
		log.info("Auto-recommend: Buy collected for {} x{} - checking sell", itemName, quantity);

		// If this item has a pending re-buy, focus re-buy instead of sell
		if (pendingReBuyItemId == itemId && pendingReBuyRemainingQty > 0)
		{
			log.info("Auto-recommend: Pending re-buy for {} x{} - showing re-buy overlay instead of sell",
				itemName, pendingReBuyRemainingQty);

			FlipRecommendation rec = findRecommendationForItem(itemId);
			int buyPrice = rec != null ? rec.getRecommendedBuyPrice() : 0;

			if (buyPrice > 0)
			{
				int priceOffset = config.priceOffset();
				FocusedFlip focus = FocusedFlip.forBuy(
					itemId,
					itemName,
					buyPrice,
					pendingReBuyRemainingQty,
					rec.getRecommendedSellPrice(),
					priceOffset
				);
				invokeFocusCallback(focus);
				updateStatus(String.format("Auto: Re-buy %s x%s @ %s gp",
					itemName, GpUtils.formatGPWithSuffix(pendingReBuyRemainingQty),
					GpUtils.formatGPWithSuffix(buyPrice)));
			}
			else
			{
				updateStatus(String.format("Auto: Re-buy %s x%s",
					itemName, GpUtils.formatGPWithSuffix(pendingReBuyRemainingQty)));
			}
			return;
		}

		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return;
		}

		ensureSellPriceAvailable(itemId);
		boolean isCollected = session.getCollectedItemIds().contains(itemId);
		Integer sellPrice = session.getRecommendedPrice(itemId);
		log.info("Auto-recommend: Buy collected check - itemId={}, isCollected={}, sellPrice={}",
			itemId, isCollected, sellPrice);

		if (isCollected && sellPrice != null)
		{
			log.info("Auto-recommend: Focusing sell for {} x{}", itemName, quantity);
			focusSellForItem(itemId, itemName, quantity);
			return;
		}

		focusNextAvailableAction();
	}

	/**
	 * Determine the next action based on current state.
	 * Priority: sell collected items > buy next from queue > wait.
	 */
	private void focusNextAvailableAction()
	{
		if (hasCollectedItemsToSell())
		{
			focusNextCollectedItemSell();
		}
		else if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
		{
			focusCurrent();
		}
		else
		{
			invokeFocusCallback(null);
			invokeOverlayMessageCallback(MSG_WAITING_FOR_FLIPS);
		}
	}

	/**
	 * Ensure a sell price is available for an item. If the stored price was lost
	 * (e.g., plugin restart), try to recover it from the recommendation queue.
	 */
	private void ensureSellPriceAvailable(int itemId)
	{
		PlayerSession session = plugin.getSession();
		if (session.getRecommendedPrice(itemId) != null)
		{
			return;
		}

		FlipRecommendation rec = findRecommendationForItem(itemId);
		if (rec != null && rec.getRecommendedSellPrice() > 0)
		{
			log.info("Auto-recommend: Recovering sell price for item {} from queue ({})",
				itemId, rec.getRecommendedSellPrice());
			plugin.setRecommendedSellPrice(itemId, rec.getRecommendedSellPrice());
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
				// Clear focus so stale buy/sell overlay doesn't persist
				invokeFocusCallback(null);
				updateStatus("Auto: All recommendations listed");
				invokeOverlayMessageCallback("All flips listed - waiting for sells");
			}
			return;
		}

		if (!hasAvailableGESlots())
		{
			log.info("Auto-recommend: All GE slots full - waiting for collection");
			promptCollection();
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

		// Never show a buy recommendation when all GE slots are occupied
		if (!hasAvailableGESlots())
		{
			promptCollection();
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
	 * for item name since TrackedOffer may have been removed.
	 * Uses actual collected quantity when available, falling back to recommendation quantity.
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
				// Clear any stale focus so the hint box can show
				invokeFocusCallback(null);
				promptCollection();
			}
			return;
		}

		FlipRecommendation rec = findRecommendationForItem(sellableItemId);
		if (rec == null)
		{
			log.warn("Auto-recommend: Cannot find item name for collected item {}", sellableItemId);
			updateStatus("Auto: Collected item no longer in queue - sell manually");
			return;
		}

		Integer sellPrice = plugin.getSession().getRecommendedPrice(sellableItemId);
		if (sellPrice == null || sellPrice <= 0)
		{
			log.warn("Auto-recommend: No recommended sell price for collected item {} ({})", rec.getItemName(), sellableItemId);
			updateStatus(String.format("Auto: No sell price for %s - sell manually", rec.getItemName()));
			return;
		}

		// Use actual collected quantity if available, fall back to recommendation
		PlayerSession session = plugin.getSession();
		int collectedQty = session.getCollectedQuantity(sellableItemId);
		int sellQuantity = collectedQty > 0 ? collectedQty : rec.getRecommendedQuantity();

		int priceOffset = config.priceOffset();
		FocusedFlip focus = FocusedFlip.forSell(
			sellableItemId,
			rec.getItemName(),
			sellPrice,
			sellQuantity,
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

	/**
	 * Show a status message prompting the user to collect completed offers.
	 * Called when all GE slots are full but auto is still active.
	 */
	private void promptCollection()
	{
		// Clear the buy overlay so it doesn't show a buy instruction when slots are full
		invokeFocusCallback(null);

		List<TrackedOffer> completed = plugin.getSession().getCompletedOffers();
		if (!completed.isEmpty())
		{
			TrackedOffer first = completed.get(0);
			if (first.isBuy())
			{
				updateStatus("Auto: Collect " + first.getItemName() + " from GE");
				invokeOverlayMessageCallback(first.getItemName(), first.getItemId());
			}
			else
			{
				updateStatus("Auto: Collect profit from GE");
				invokeOverlayMessageCallback("Collect profit from GE");
			}
		}
		else
		{
			updateStatus("Auto: Waiting for flips");
			invokeOverlayMessageCallback(MSG_WAITING_FOR_FLIPS);
		}
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

	private void invokeOverlayMessageCallback(String message)
	{
		invokeOverlayMessageCallback(message, 0);
	}

	private void invokeOverlayMessageCallback(String message, int itemId)
	{
		ObjIntConsumer<String> callback = onOverlayMessageChanged;
		if (callback != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> callback.accept(message, itemId));
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
		PlayerSession session = plugin.getSession();
		return session != null && session.hasAvailableGESlots(plugin.getFlipSlotLimit());
	}
}
