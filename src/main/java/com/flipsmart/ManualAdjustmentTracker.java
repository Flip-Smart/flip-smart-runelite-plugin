package com.flipsmart;

import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Tracks adjustment timers for manual (non-auto-recommend) flip offers.
 * When a manual buy or sell offer becomes stale, calls the backend /flips/adjustment
 * endpoint and prompts the user with a recommended price change.
 *
 * <p>This complements AutoRecommendService's built-in adjustment timers by providing
 * the same staleness detection for manual flips. Unlike auto mode (which uses local
 * recommendation data), manual mode calls the backend API for adjustment recommendations.</p>
 */
@Slf4j
public class ManualAdjustmentTracker
{
	/**
	 * Per-offer state for tracking adjustment timers and counts.
	 */
	private static class OfferAdjustmentState
	{
		final int itemId;
		final String itemName;
		final boolean isBuy;
		final int geSlot;
		long deadlineMs;
		int adjustmentCount;
		int averageBuyPrice;

		OfferAdjustmentState(int itemId, String itemName, boolean isBuy, int geSlot,
			long deadlineMs, int averageBuyPrice)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.isBuy = isBuy;
			this.geSlot = geSlot;
			this.deadlineMs = deadlineMs;
			this.adjustmentCount = 0;
			this.averageBuyPrice = averageBuyPrice;
		}
	}

	private final FlipSmartApiClient apiClient;
	private final FlipSmartConfig config;

	private final Map<Integer, OfferAdjustmentState> trackedOffers = new ConcurrentHashMap<>();

	// Callback to show adjustment prompts in FlipAssistOverlay
	private volatile BiConsumer<String, Integer> onAdjustmentPrompt;

	// Callback to set a FocusedFlip for buy adjustments
	private volatile BiConsumer<FocusedFlip, String> onFocusFlip;

	// Callback to highlight a GE slot for adjustment
	private volatile BiConsumer<Integer, Integer> onHighlightSlot;

	// Callback to clear a GE slot highlight
	private volatile java.util.function.IntConsumer onClearHighlight;

	public ManualAdjustmentTracker(FlipSmartApiClient apiClient, FlipSmartConfig config)
	{
		this.apiClient = apiClient;
		this.config = config;
	}

	public void setOnAdjustmentPrompt(BiConsumer<String, Integer> callback)
	{
		this.onAdjustmentPrompt = callback;
	}

	public void setOnFocusFlip(BiConsumer<FocusedFlip, String> callback)
	{
		this.onFocusFlip = callback;
	}

	public void setOnHighlightSlot(BiConsumer<Integer, Integer> callback)
	{
		this.onHighlightSlot = callback;
	}

	public void setOnClearHighlight(java.util.function.IntConsumer callback)
	{
		this.onClearHighlight = callback;
	}

	/**
	 * Schedule an adjustment timer for a manual buy offer.
	 *
	 * @param itemId Item ID
	 * @param itemName Item display name
	 * @param geSlot GE slot index
	 * @param offerPrice Price of the buy offer
	 */
	public void scheduleBuyAdjustment(int itemId, String itemName, int geSlot, int offerPrice)
	{
		long delay = getAdjustmentDelayMs(config.flipTimeframe(), offerPrice);
		long deadline = System.currentTimeMillis() + delay;
		OfferAdjustmentState state = new OfferAdjustmentState(
			itemId, itemName, true, geSlot, deadline, offerPrice);
		trackedOffers.put(geSlot, state);
		log.info("Manual adjustment timer scheduled for {} (slot {}) in {}m",
			itemName, geSlot, delay / 60000);
	}

	/**
	 * Schedule an adjustment timer for a manual sell offer.
	 *
	 * @param itemId Item ID
	 * @param itemName Item display name
	 * @param geSlot GE slot index
	 * @param offerPrice Price of the sell offer
	 * @param averageBuyPrice Average buy price (cost basis)
	 */
	public void scheduleSellAdjustment(int itemId, String itemName, int geSlot,
		int offerPrice, int averageBuyPrice)
	{
		long delay = getAdjustmentDelayMs(config.flipTimeframe(), offerPrice);
		long deadline = System.currentTimeMillis() + delay;
		OfferAdjustmentState state = new OfferAdjustmentState(
			itemId, itemName, false, geSlot, deadline, averageBuyPrice);
		trackedOffers.put(geSlot, state);
		log.info("Manual sell adjustment timer scheduled for {} (slot {}) in {}m",
			itemName, geSlot, delay / 60000);
	}

	/**
	 * Reset the adjustment timer for a GE slot (e.g., after a partial fill).
	 */
	public void resetTimer(int geSlot, int offerPrice)
	{
		OfferAdjustmentState state = trackedOffers.get(geSlot);
		if (state == null)
		{
			return;
		}
		long delay = getAdjustmentDelayMs(config.flipTimeframe(), offerPrice);
		state.deadlineMs = System.currentTimeMillis() + delay;
		log.debug("Manual adjustment timer reset for slot {} ({}m)", geSlot, delay / 60000);
	}

	/**
	 * Clear the adjustment timer for a GE slot (e.g., on completion or cancellation).
	 */
	public void clearTimer(int geSlot)
	{
		OfferAdjustmentState removed = trackedOffers.remove(geSlot);
		if (removed != null)
		{
			log.debug("Manual adjustment timer cleared for {} (slot {})", removed.itemName, geSlot);
			java.util.function.IntConsumer cb = onClearHighlight;
			if (cb != null)
			{
				cb.accept(geSlot);
			}
		}
	}

	/**
	 * Clear all tracked timers (e.g., on logout).
	 */
	public void clearAll()
	{
		for (int slot : trackedOffers.keySet())
		{
			java.util.function.IntConsumer cb = onClearHighlight;
			if (cb != null)
			{
				cb.accept(slot);
			}
		}
		trackedOffers.clear();
	}

	/**
	 * Check whether a GE slot has a pending adjustment recommendation.
	 */
	public boolean hasAdjustmentPending(int geSlot)
	{
		return trackedOffers.containsKey(geSlot);
	}

	/**
	 * Check all adjustment timers and fire API calls for expired ones.
	 * Called periodically from the plugin's refresh timer.
	 *
	 * @param sessionOffers Current tracked offers from the session (to verify offer still exists)
	 */
	public void checkTimers(Map<Integer, TrackedOffer> sessionOffers)
	{
		if (trackedOffers.isEmpty() || sessionOffers == null)
		{
			return;
		}

		long now = System.currentTimeMillis();
		Iterator<Map.Entry<Integer, OfferAdjustmentState>> iter = trackedOffers.entrySet().iterator();

		while (iter.hasNext())
		{
			Map.Entry<Integer, OfferAdjustmentState> entry = iter.next();
			int geSlot = entry.getKey();
			OfferAdjustmentState state = entry.getValue();

			// Verify the offer still exists and matches
			TrackedOffer offer = sessionOffers.get(geSlot);
			if (offer == null || offer.isCompleted())
			{
				iter.remove();
				continue;
			}

			// Skip if offer has filled (partial fills reset the timer, full fills clear it)
			if (offer.getPreviousQuantitySold() > 0)
			{
				iter.remove();
				continue;
			}

			// Check if timer has expired
			if (now < state.deadlineMs)
			{
				continue;
			}

			// Timer expired — call the API
			processExpiredTimer(state, offer, iter);
		}
	}

	private void processExpiredTimer(OfferAdjustmentState state, TrackedOffer offer,
		Iterator<Map.Entry<Integer, OfferAdjustmentState>> iter)
	{
		long minutesSinceOffer = (System.currentTimeMillis() - offer.getCreatedAtMillis()) / 60000;
		String timeframe = config.flipTimeframe().getApiValue();

		apiClient.getFlipAdjustmentAsync(
			state.itemId,
			state.isBuy,
			offer.getPrice(),
			state.averageBuyPrice,
			(int) minutesSinceOffer,
			state.adjustmentCount,
			offer.getPreviousQuantitySold(),
			offer.getTotalQuantity(),
			timeframe
		).thenAccept(response ->
		{
			if (response == null)
			{
				return;
			}

			if (response.isActionRequired())
			{
				handleAdjustmentRecommendation(state, offer, response);
				state.adjustmentCount++;
			}

			// Reschedule based on response or default
			Integer nextCheck = response.getNextCheckMinutes();
			long delay;
			if (nextCheck != null && nextCheck > 0)
			{
				delay = nextCheck * 60 * 1000L;
			}
			else
			{
				delay = getAdjustmentDelayMs(config.flipTimeframe(), offer.getPrice());
			}
			state.deadlineMs = System.currentTimeMillis() + delay;

		}).exceptionally(e ->
		{
			log.debug("Failed to get adjustment recommendation for {}: {}",
				state.itemName, e.getMessage());
			// Reschedule with default delay on error
			long delay = getAdjustmentDelayMs(config.flipTimeframe(), offer.getPrice());
			state.deadlineMs = System.currentTimeMillis() + delay;
			return null;
		});

		// Don't remove from iterator — we reschedule in the callback
	}

	private void handleAdjustmentRecommendation(OfferAdjustmentState state,
		TrackedOffer offer, FlipAdjustmentResponse response)
	{
		if (response.isReadjustBuy() && response.getRecommendedPrice() != null)
		{
			String msg = String.format("Adjust %s buy price %s → %s gp",
				state.itemName,
				GpUtils.formatGPWithSuffix(offer.getPrice()),
				GpUtils.formatGPWithSuffix(response.getRecommendedPrice()));

			log.info("Manual adjustment: {}", msg);

			// Focus the buy overlay with the new recommended price
			BiConsumer<FocusedFlip, String> focusCallback = onFocusFlip;
			if (focusCallback != null)
			{
				int sellPrice = response.getBreakevenPrice() + 1;
				if (response.getCurrentMargin() != null && response.getCurrentMargin() > 0)
				{
					sellPrice = response.getRecommendedPrice() + response.getCurrentMargin();
				}
				FocusedFlip focus = FocusedFlip.forBuy(
					state.itemId, state.itemName,
					response.getRecommendedPrice(),
					offer.getTotalQuantity(),
					sellPrice,
					config.priceOffset());
				focusCallback.accept(focus, msg);
			}

			// Highlight the GE slot
			BiConsumer<Integer, Integer> highlightCallback = onHighlightSlot;
			if (highlightCallback != null)
			{
				highlightCallback.accept(state.geSlot, response.getRecommendedPrice());
			}
		}
		else if (response.isReadjustSell() && response.getRecommendedPrice() != null)
		{
			String msg = String.format("Adjust %s sell price %s → %s gp",
				state.itemName,
				GpUtils.formatGPWithSuffix(offer.getPrice()),
				GpUtils.formatGPWithSuffix(response.getRecommendedPrice()));

			log.info("Manual adjustment: {}", msg);

			BiConsumer<String, Integer> promptCallback = onAdjustmentPrompt;
			if (promptCallback != null)
			{
				promptCallback.accept(msg, state.itemId);
			}

			BiConsumer<Integer, Integer> highlightCallback = onHighlightSlot;
			if (highlightCallback != null)
			{
				highlightCallback.accept(state.geSlot, response.getRecommendedPrice());
			}
		}
		else if (response.isCancelAndSell())
		{
			String msg = String.format("Margin gone on %s — consider cancelling", state.itemName);
			log.info("Manual adjustment: {}", msg);

			BiConsumer<String, Integer> promptCallback = onAdjustmentPrompt;
			if (promptCallback != null)
			{
				promptCallback.accept(msg, state.itemId);
			}
		}
	}

	/**
	 * Get the adjustment delay based on timeframe and item price.
	 * Mirrors AutoRecommendService.getAdjustmentDelayMs() for consistency.
	 */
	static long getAdjustmentDelayMs(FlipSmartConfig.FlipTimeframe timeframe, int itemPrice)
	{
		boolean highValue = itemPrice >= 5_000_000;
		switch (timeframe)
		{
			case ACTIVE:
				return (highValue ? 10 : 5) * 60 * 1000L;
			case THIRTY_MINS:
				return (highValue ? 15 : 10) * 60 * 1000L;
			case TWO_HOURS:
				return (highValue ? 30 : 15) * 60 * 1000L;
			case FOUR_HOURS:
				return (highValue ? 60 : 30) * 60 * 1000L;
			case TWELVE_HOURS:
				return (highValue ? 240 : 60) * 60 * 1000L;
			default:
				return (highValue ? 10 : 5) * 60 * 1000L;
		}
	}
}
