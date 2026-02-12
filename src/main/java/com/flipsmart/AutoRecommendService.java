package com.flipsmart;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.Set;
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

	private final FlipSmartConfig config;
	private final FlipSmartPlugin plugin;

	// Queue state - guarded by synchronized(this)
	private final List<FlipRecommendation> recommendationQueue = new ArrayList<>();
	private int currentIndex;
	private volatile boolean active;

	// Pending sells: items that finished buying and need to be sold
	private final Queue<PendingSell> pendingSells = new ConcurrentLinkedQueue<>();

	// Callback to update Flip Assist overlay and panel
	private volatile Consumer<FocusedFlip> onFocusChanged;

	// Callback to update status text in the panel
	private volatile Consumer<String> onStatusChanged;

	/**
	 * Represents an item that completed buying and is waiting to be sold
	 */
	private static class PendingSell
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

	public AutoRecommendService(FlipSmartConfig config, FlipSmartPlugin plugin)
	{
		this.config = config;
		this.plugin = plugin;
	}

	/**
	 * Set the callback for when the focused flip changes.
	 */
	public void setOnFocusChanged(Consumer<FocusedFlip> callback)
	{
		this.onFocusChanged = callback;
	}

	/**
	 * Set the callback for status text updates.
	 */
	public void setOnStatusChanged(Consumer<String> callback)
	{
		this.onStatusChanged = callback;
	}

	/**
	 * Whether the auto-recommend service is currently active.
	 */
	public boolean isActive()
	{
		return active;
	}

	/**
	 * Start auto-recommend with the given recommendations.
	 * Filters out items already in GE slots or active flips.
	 */
	public synchronized void start(List<FlipRecommendation> recommendations)
	{
		if (recommendations == null || recommendations.isEmpty())
		{
			updateStatus("Auto: No recommendations available");
			return;
		}

		// Filter out items already in GE or being tracked
		Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();
		Set<Integer> geBuyItemIds = plugin.getCurrentGEBuyItemIds();

		recommendationQueue.clear();
		pendingSells.clear();
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

		active = true;
		log.info("Auto-recommend started with {} items in queue", recommendationQueue.size());
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
		currentIndex = 0;

		// Clear focus
		invokeFocusCallback(null);

		log.info("Auto-recommend stopped");
	}

	/**
	 * Called when the user places a new buy order for the focused item.
	 * Stores the recommended sell price and advances to the next recommendation.
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

		// Store the recommended sell price so it's available when the buy completes
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

		// Calculate sell price
		int sellPrice;
		if (recommendedSellPrice != null && recommendedSellPrice > 0)
		{
			sellPrice = recommendedSellPrice;
		}
		else
		{
			// Fallback: minimum profitable price (breakeven + 1gp after GE tax)
			sellPrice = (int) Math.ceil((buyPrice + 1) / GE_TAX_RATE);
		}

		pendingSells.add(new PendingSell(itemId, itemName, sellPrice, quantity));
		log.info("Auto-recommend: Buy complete for {} x{} - queued sell at {} gp", itemName, quantity, sellPrice);

		// If we're not currently focused on a buy recommendation (all listed),
		// focus on the first pending sell
		FlipRecommendation currentRec = getCurrentRecommendation();
		if (currentRec == null || !hasAvailableGESlots())
		{
			focusNextPendingSell();
		}
	}

	/**
	 * Called when a sell order is placed for an item.
	 * Removes it from pending sells and advances.
	 */
	public synchronized void onSellOrderPlaced(int itemId)
	{
		if (!active)
		{
			return;
		}

		// Remove from pending sells
		pendingSells.removeIf(ps -> ps.itemId == itemId);

		log.info("Auto-recommend: Sell order placed for item {} - checking next action", itemId);

		// Decide what to focus next
		if (hasAvailableGESlots() && currentIndex < recommendationQueue.size())
		{
			// More buy recommendations and slots available
			focusCurrent();
		}
		else if (!pendingSells.isEmpty())
		{
			// Focus next pending sell
			focusNextPendingSell();
		}
		else if (currentIndex >= recommendationQueue.size())
		{
			updateStatus("Auto: Queue complete");
		}
	}

	/**
	 * Advance to the next recommendation in the queue.
	 * Must be called while holding the lock (from synchronized method).
	 */
	private void advanceToNext()
	{
		currentIndex++;

		// Skip items that became active while we were processing
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
			// No more buy recommendations
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

	/**
	 * Focus the current recommendation in Flip Assist.
	 * Must be called while holding the lock (from synchronized method).
	 */
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

		updateStatus(String.format("Auto: %d/%d - %s",
			currentIndex + 1, recommendationQueue.size(), rec.getItemName()));
	}

	/**
	 * Focus the next pending sell item in Flip Assist.
	 * Must be called while holding the lock (from synchronized method).
	 */
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

		updateStatus(String.format("Auto: Sell %s @ %s gp",
			sell.itemName, GpUtils.formatGPWithSuffix(sell.sellPrice)));
	}

	/**
	 * Invoke the focus callback on the Swing EDT.
	 */
	private void invokeFocusCallback(FocusedFlip focus)
	{
		Consumer<FocusedFlip> callback = onFocusChanged;
		if (callback != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> callback.accept(focus));
		}
	}

	/**
	 * Get the current recommendation, or null if queue is exhausted.
	 */
	public synchronized FlipRecommendation getCurrentRecommendation()
	{
		if (currentIndex >= 0 && currentIndex < recommendationQueue.size())
		{
			return recommendationQueue.get(currentIndex);
		}
		return null;
	}

	/**
	 * Get the current index in the queue.
	 */
	public synchronized int getCurrentIndex()
	{
		return currentIndex;
	}

	/**
	 * Get the total number of recommendations in the queue.
	 */
	public synchronized int getQueueSize()
	{
		return recommendationQueue.size();
	}

	/**
	 * Get the number of pending sells.
	 */
	public int getPendingSellCount()
	{
		return pendingSells.size();
	}

	/**
	 * Check if there are available GE slots for new buy orders.
	 */
	private boolean hasAvailableGESlots()
	{
		return plugin.getSession().getTrackedOffers().size() < MAX_GE_SLOTS;
	}

	/**
	 * Update the status text in the panel via the Swing EDT.
	 */
	private void updateStatus(String status)
	{
		log.debug("Auto-recommend status: {}", status);
		Consumer<String> callback = onStatusChanged;
		if (callback != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> callback.accept(status));
		}
	}
}
