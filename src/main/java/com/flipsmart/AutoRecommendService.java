package com.flipsmart;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Manages the auto-recommend queue for cycling through flip recommendations.
 *
 * When active, this service automatically focuses recommendations into Flip Assist
 * one-by-one. When the user places a buy order, it advances to the next recommendation.
 * When a buy order completes, it queues the sell side for that item.
 */
@Slf4j
public class AutoRecommendService
{
	private final FlipSmartConfig config;
	private final FlipSmartPlugin plugin;

	// Queue state
	private final List<FlipRecommendation> recommendationQueue = new ArrayList<>();
	@Getter
	private int currentIndex = 0;
	@Getter
	private boolean active = false;

	// Pending sells: items that finished buying and need to be sold
	private final Queue<PendingSell> pendingSells = new LinkedList<>();

	// Callback to update Flip Assist overlay and panel
	private Consumer<FocusedFlip> onFocusChanged;

	// Callback to update status text in the panel
	private Consumer<String> onStatusChanged;

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
	 * Start auto-recommend with the given recommendations.
	 * Filters out items already in GE slots or active flips.
	 */
	public void start(List<FlipRecommendation> recommendations)
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
	public void stop()
	{
		active = false;
		recommendationQueue.clear();
		pendingSells.clear();
		currentIndex = 0;

		// Clear focus
		if (onFocusChanged != null)
		{
			onFocusChanged.accept(null);
		}

		log.info("Auto-recommend stopped");
	}

	/**
	 * Called when the user places a new buy order for the focused item.
	 * Stores the recommended sell price and advances to the next recommendation.
	 */
	public void onBuyOrderPlaced(int itemId)
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
	public void onBuyOrderCompleted(int itemId, String itemName, int quantity, int buyPrice, Integer recommendedSellPrice)
	{
		if (!active)
		{
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
			// Fallback: minimum profitable price (breakeven + 1gp after 2% tax)
			sellPrice = (int) Math.ceil((buyPrice + 1) / 0.98);
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
	public void onSellOrderPlaced(int itemId)
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

		if (onFocusChanged != null)
		{
			onFocusChanged.accept(focus);
		}

		updateStatus(String.format("Auto: %d/%d - %s",
			currentIndex + 1, recommendationQueue.size(), rec.getItemName()));
	}

	/**
	 * Focus the next pending sell item in Flip Assist.
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

		if (onFocusChanged != null)
		{
			onFocusChanged.accept(focus);
		}

		updateStatus(String.format("Auto: Sell %s @ %s gp",
			sell.itemName, GpUtils.formatGPWithSuffix(sell.sellPrice)));
	}

	/**
	 * Get the current recommendation, or null if queue is exhausted.
	 */
	public FlipRecommendation getCurrentRecommendation()
	{
		if (currentIndex >= 0 && currentIndex < recommendationQueue.size())
		{
			return recommendationQueue.get(currentIndex);
		}
		return null;
	}

	/**
	 * Get the total number of recommendations in the queue.
	 */
	public int getQueueSize()
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
	 * Players have 8 GE slots total.
	 */
	private boolean hasAvailableGESlots()
	{
		return plugin.getSession().getTrackedOffers().size() < 8;
	}

	/**
	 * Update the status text in the panel.
	 */
	private void updateStatus(String status)
	{
		log.debug("Auto-recommend status: {}", status);
		if (onStatusChanged != null)
		{
			onStatusChanged.accept(status);
		}
	}
}
