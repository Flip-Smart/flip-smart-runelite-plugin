package com.flipsmart;
import com.flipsmart.api.dto.FlipAdjustmentRequest;
import com.flipsmart.api.dto.FlipAdjustmentResponse;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.api.dto.FlipFinderResponse;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.util.GpUtils;

import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;

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
	private final OfferStore offerStore;

	private static final String MARGIN_GONE_MSG = "Margin gone on %s — consider cancelling";

	private final Map<Integer, OfferAdjustmentState> trackedOffers = new ConcurrentHashMap<>();

	// Callback to show adjustment prompts in FlipAssistOverlay
	private volatile ObjIntConsumer<String> onAdjustmentPrompt;

	// Callback to set a FocusedFlip for buy adjustments
	private volatile BiConsumer<FocusedFlip, String> onFocusFlip;

	// Callback to highlight a GE slot for adjustment
	private volatile BiConsumer<Integer, Integer> onHighlightSlot;

	// Callback to clear a GE slot highlight
	private volatile java.util.function.IntConsumer onClearHighlight;

	// Callback to highlight an inventory item for sell adjustments
	private volatile java.util.function.IntConsumer onHighlightInventoryItem;

	// Callback to clear an inventory item highlight
	private volatile java.util.function.IntConsumer onClearInventoryItem;

	// Callback to persist adjusted sell price to session
	private volatile BiConsumer<Integer, Integer> onSellPriceAdjusted;

	// Suppliers for ditch logic — needed to fetch replacement recommendations
	private volatile java.util.function.Supplier<Integer> cashStackSupplier;
	private volatile java.util.function.Supplier<String> rsnSupplier;
	private volatile java.util.function.Supplier<Integer> filledSlotsSupplier;
	private volatile java.util.function.Supplier<Boolean> membersWorldSupplier;

	public ManualAdjustmentTracker(FlipSmartApiClient apiClient, FlipSmartConfig config, OfferStore offerStore)
	{
		this.apiClient = apiClient;
		this.config = config;
		this.offerStore = offerStore;
	}

	public void setOnAdjustmentPrompt(ObjIntConsumer<String> callback)
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

	public void setOnHighlightInventoryItem(java.util.function.IntConsumer callback)
	{
		this.onHighlightInventoryItem = callback;
	}

	public void setOnClearInventoryItem(java.util.function.IntConsumer callback)
	{
		this.onClearInventoryItem = callback;
	}

	public void setOnSellPriceAdjusted(BiConsumer<Integer, Integer> callback)
	{
		this.onSellPriceAdjusted = callback;
	}

	public void setCashStackSupplier(java.util.function.Supplier<Integer> supplier)
	{
		this.cashStackSupplier = supplier;
	}

	public void setRsnSupplier(java.util.function.Supplier<String> supplier)
	{
		this.rsnSupplier = supplier;
	}

	public void setFilledSlotsSupplier(java.util.function.Supplier<Integer> supplier)
	{
		this.filledSlotsSupplier = supplier;
	}

	public void setMembersWorldSupplier(java.util.function.Supplier<Boolean> supplier)
	{
		this.membersWorldSupplier = supplier;
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
		long delay = AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
		long deadline = System.currentTimeMillis() + delay;
		OfferAdjustmentState state = new OfferAdjustmentState(
			itemId, itemName, true, geSlot, deadline, offerPrice);
		trackedOffers.put(geSlot, state);
		log.debug("Manual adjustment timer scheduled for {} (slot {}) in {}m",
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
		long delay = AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
		long deadline = System.currentTimeMillis() + delay;
		OfferAdjustmentState state = new OfferAdjustmentState(
			itemId, itemName, false, geSlot, deadline, averageBuyPrice);
		trackedOffers.put(geSlot, state);
		log.debug("Manual sell adjustment timer scheduled for {} (slot {}) in {}m",
			itemName, geSlot, delay / 60000);
	}

	/**
	 * Reset the adjustment timer for a GE slot (e.g., after a partial fill).
	 */
	public void resetTimer(int geSlot)
	{
		OfferAdjustmentState state = trackedOffers.get(geSlot);
		if (state == null)
		{
			return;
		}
		state.deadlineMs = System.currentTimeMillis() + AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
		log.debug("Manual adjustment timer reset for slot {} ({}m)", geSlot,
			AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS / 60000);
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
			if (!removed.isBuy)
			{
				notifyClearInventoryHighlight(removed.itemId);
			}
		}
	}

	/**
	 * Clear all tracked timers (e.g., on logout).
	 */
	public void clearAll()
	{
		for (Map.Entry<Integer, OfferAdjustmentState> entry : trackedOffers.entrySet())
		{
			java.util.function.IntConsumer cb = onClearHighlight;
			if (cb != null)
			{
				cb.accept(entry.getKey());
			}
			if (!entry.getValue().isBuy)
			{
				notifyClearInventoryHighlight(entry.getValue().itemId);
			}
		}
		trackedOffers.clear();
	}

	/**
	 * Check all adjustment timers and fire API calls for expired ones.
	 * Called periodically from the plugin's refresh timer. Offer state is read
	 * from the authoritative {@link OfferStore} keyed by GE slot.
	 */
	public void checkTimers()
	{
		if (trackedOffers.isEmpty())
		{
			return;
		}

		long now = System.currentTimeMillis();
		Iterator<Map.Entry<Integer, OfferAdjustmentState>> iter = trackedOffers.entrySet().iterator();

		while (iter.hasNext())
		{
			Map.Entry<Integer, OfferAdjustmentState> entry = iter.next();
			OfferRecord offer = offerStore.bySlot(entry.getKey());
			OfferAdjustmentState state = entry.getValue();

			if (shouldRemoveTimer(offer))
			{
				iter.remove();
			}
			else if (now >= state.deadlineMs)
			{
				processExpiredTimer(state, offer);
			}
		}
	}

	private boolean shouldRemoveTimer(OfferRecord offer)
	{
		// Only remove if offer is gone (slot freed / collected) or fully filled.
		// Partial fills don't remove the timer — the API decides whether to hold or adjust.
		return offer == null || offer.getState() == OfferState.FILLED;
	}

	private void processExpiredTimer(OfferAdjustmentState state, OfferRecord offer)
	{
		long minutesSinceOffer = (System.currentTimeMillis() - offer.getEffectiveLastActivityAtMillis()) / 60000;
		String timeframe = config.flipTimeframe().getApiValue();
		String style = config.flipStyle().getApiValue();
		String rsn = rsnSupplier != null ? rsnSupplier.get() : null;

		apiClient.getFlipAdjustmentAsync(FlipAdjustmentRequest.builder()
			.itemId(state.itemId)
			.isBuyOffer(state.isBuy)
			.offerPrice(offer.getPrice())
			.averageBuyPrice(state.averageBuyPrice)
			.minutesSinceOffer((int) minutesSinceOffer)
			.adjustmentCount(state.adjustmentCount)
			.quantityFilled(offer.getFilledQuantity())
			.totalQuantity(offer.getTotalQuantity())
			.timeframe(timeframe)
			.rsn(rsn)
			.style(style)
			.build()
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

			// Reschedule using API-provided next_check_minutes (source of truth)
			state.deadlineMs = System.currentTimeMillis()
				+ AdjustmentTimerUtils.nextCheckDelayMs(response.getNextCheckMinutes());

		}).exceptionally(e ->
		{
			log.debug("Failed to get adjustment recommendation for {}: {}",
				state.itemName, e.getMessage());
			// Reschedule with fallback delay on error
			state.deadlineMs = System.currentTimeMillis() + AdjustmentTimerUtils.FALLBACK_CHECK_DELAY_MS;
			return null;
		});

		// Don't remove from iterator — we reschedule in the callback
	}

	private void handleAdjustmentRecommendation(OfferAdjustmentState state,
		OfferRecord offer, FlipAdjustmentResponse response)
	{
		if (response.isReadjustBuy() && response.getRecommendedPrice() != null)
		{
			handleBuyAdjustment(state, offer, response);
		}
		else if (response.isReadjustSell() && response.getRecommendedPrice() != null)
		{
			handleSellAdjustment(state, offer, response);
		}
		else if (response.isCancelAndSell())
		{
			handleCancelAndSell(state, response);
		}
	}

	private void handleCancelAndSell(OfferAdjustmentState state, FlipAdjustmentResponse response)
	{
		if (response.getMessage() != null && !response.getMessage().isEmpty())
		{
			notifyPrompt(response.getMessage(), state.itemId);
		}
		if (response.getRecommendedPrice() != null)
		{
			// Overnight exit that already bought items: surface the sell price to list at.
			notifyHighlight(state.geSlot, response.getRecommendedPrice());
			notifyInventoryHighlight(state.itemId);
			BiConsumer<Integer, Integer> cb = onSellPriceAdjusted;
			if (cb != null)
			{
				cb.accept(state.itemId, response.getRecommendedPrice());
			}
		}
		else
		{
			log.debug("Manual adjustment: Margin gone on {} — fetching replacement", state.itemName);
			fetchReplacementRecommendation(state);
		}
	}

	private void handleBuyAdjustment(OfferAdjustmentState state, OfferRecord offer,
		FlipAdjustmentResponse response)
	{
		String msg = String.format("Adjust %s buy price %s → %s gp",
			state.itemName,
			GpUtils.formatGPWithSuffix(offer.getPrice()),
			GpUtils.formatGPWithSuffix(response.getRecommendedPrice()));

		log.debug("Manual adjustment: {}", msg);

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

		notifyHighlight(state.geSlot, response.getRecommendedPrice());
	}

	private void handleSellAdjustment(OfferAdjustmentState state, OfferRecord offer,
		FlipAdjustmentResponse response)
	{
		String msg = String.format("Adjust %s sell price %s → %s gp",
			state.itemName,
			GpUtils.formatGPWithSuffix(offer.getPrice()),
			GpUtils.formatGPWithSuffix(response.getRecommendedPrice()));

		log.debug("Manual adjustment: {}", msg);
		notifyPrompt(msg, state.itemId);
		notifyHighlight(state.geSlot, response.getRecommendedPrice());
		notifyInventoryHighlight(state.itemId);

		BiConsumer<Integer, Integer> cb = onSellPriceAdjusted;
		if (cb != null)
		{
			cb.accept(state.itemId, response.getRecommendedPrice());
		}
	}

	private void notifyPrompt(String msg, int itemId)
	{
		ObjIntConsumer<String> cb = onAdjustmentPrompt;
		if (cb != null)
		{
			cb.accept(msg, itemId);
		}
	}

	private void notifyHighlight(int geSlot, int price)
	{
		BiConsumer<Integer, Integer> cb = onHighlightSlot;
		if (cb != null)
		{
			cb.accept(geSlot, price);
		}
	}

	private void notifyInventoryHighlight(int itemId)
	{
		java.util.function.IntConsumer cb = onHighlightInventoryItem;
		if (cb != null)
		{
			cb.accept(itemId);
		}
	}

	private void notifyClearInventoryHighlight(int itemId)
	{
		java.util.function.IntConsumer cb = onClearInventoryItem;
		if (cb != null)
		{
			cb.accept(itemId);
		}
	}

	/**
	 * Fetch a replacement flip recommendation when the current flip's margin has evaporated.
	 * Shows a prompt like "Margin gone on [item] — switch to [new item] instead?"
	 */
	private void fetchReplacementRecommendation(OfferAdjustmentState state)
	{
		Integer cashStack = cashStackSupplier != null ? cashStackSupplier.get() : null;
		String rsn = rsnSupplier != null ? rsnSupplier.get() : null;
		Integer filledSlots = filledSlotsSupplier != null ? filledSlotsSupplier.get() : null;
		boolean isMembersWorld = membersWorldSupplier == null || membersWorldSupplier.get();
		String timeframe = config.flipTimeframe().getApiValue();
		String flipStyle = config.flipStyle().getApiValue();

		apiClient.getFlipRecommendationsAsync(cashStack, flipStyle, 1, null, timeframe, rsn, filledSlots, isMembersWorld, false)
			.thenAccept(response -> handleReplacementResponse(response, state))
			.exceptionally(e ->
			{
				log.debug("Failed to fetch replacement recommendation: {}", e.getMessage());
				notifyMarginGone(state);
				return null;
			});
	}

	private void handleReplacementResponse(FlipFinderResponse response,
		OfferAdjustmentState state)
	{
		if (response == null || response.getRecommendations() == null
			|| response.getRecommendations().isEmpty())
		{
			notifyMarginGone(state);
			return;
		}

		FlipRecommendation replacement = response.getRecommendations().get(0);
		if (replacement.getItemId() == state.itemId)
		{
			notifyMarginGone(state);
			return;
		}

		String msg = String.format("Margin gone on %s — switch to %s instead? (%s profit)",
			state.itemName,
			replacement.getItemName(),
			GpUtils.formatGPWithSuffix(replacement.getPotentialProfit()));

		log.debug("Ditch recommendation: {}", msg);

		BiConsumer<FocusedFlip, String> focusCallback = onFocusFlip;
		if (focusCallback != null)
		{
			FocusedFlip focus = FocusedFlip.forBuy(
				replacement.getItemId(),
				replacement.getItemName(),
				replacement.getRecommendedBuyPrice(),
				replacement.getRecommendedQuantity(),
				replacement.getRecommendedSellPrice(),
				config.priceOffset());
			focusCallback.accept(focus, msg);
		}

		notifyHighlight(state.geSlot, 0);
	}

	private void notifyMarginGone(OfferAdjustmentState state)
	{
		notifyPrompt(String.format(MARGIN_GONE_MSG, state.itemName), state.itemId);
	}

}
