package com.flipsmart;
import com.flipsmart.api.dto.FlipAdjustmentRequest;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.api.dto.FlipAdjustmentResponse;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.recommend.AdjustmentService;
import com.flipsmart.recommend.AdjustmentService.SellAdjustmentState;
import com.flipsmart.recommend.RecommendationQueue;
import com.flipsmart.recommend.StaleOfferQueue;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.util.GpUtils;

import com.flipsmart.FlipAssistOverlay.FlipAssistStep;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.ObjIntConsumer;

/**
 * Manages the auto-recommend queue for cycling through flip recommendations.
 *
 * When active, this service automatically focuses recommendations into Flip Assist
 * one-by-one. When the user places a buy order, it advances to the next recommendation.
 * When a buy order completes, it uses session state (collectedItemIds + recommendedPrices)
 * to guide the user through the sell side.
 *
 * Thread safety: This class is the sole synchronization point. All public methods
 * are synchronized on {@code this}. Its three collaborators ({@link RecommendationQueue},
 * {@link StaleOfferQueue}, {@link AdjustmentService}) hold no locks of their own and are
 * only ever touched while this monitor is held. Callbacks are dispatched on the Swing
 * EDT via SwingUtilities.invokeLater.
 */
@Slf4j
public class AutoRecommendService
{
	/** How long before a buy offer is considered stale (15 minutes) */
	private static final long INACTIVITY_TIMEOUT_MS = 15 * 60 * 1000L;
	/** Maximum age of persisted state before it's considered stale (30 minutes) */
	static final long MAX_PERSISTED_AGE_MS = 30 * 60 * 1000L;
	private static final String MSG_WAITING_FOR_FLIPS = "Waiting for flips";
	private static final String MSG_SELL_FORMAT = "Auto: Sell %s @ %s";
	private static final String MSG_BUY_FORMAT = "Auto: Buy %s @ %s";


	private final FlipSmartConfig config;
	private final FlipSmartPlugin plugin;
	private final OfferStore offerStore;

	// Buy queue + cursor + item names + offer-screen lock + last-refresh timestamp.
	private final RecommendationQueue queue = new RecommendationQueue();

	// Stale-offer queue + resell prices/net + prompted set.
	private final StaleOfferQueue staleOffers = new StaleOfferQueue();

	// Buy/sell adjustment timers + buy-price cost basis.
	private final AdjustmentService adjustments = new AdjustmentService();

	private final List<Runnable> deferredActions = new ArrayList<>();

	private volatile boolean active;

	// Callback to update Flip Assist overlay and panel
	private volatile Consumer<FocusedFlip> onFocusChanged;

	// Callback to update status text in the panel
	private volatile Consumer<String> onStatusChanged;

	// Callback when the queue advances (for panel highlight updates)
	private volatile Runnable onQueueAdvanced;

	// Callback when skip exhausts the queue (to trigger a refresh)
	private volatile Runnable onQueueExhausted;

	// Callback to update the Flip Assist overlay message (when no flip is focused)
	// ObjIntConsumer<message, itemId> — itemId <= 0 means no icon
	private volatile ObjIntConsumer<String> onOverlayMessageChanged;

	private volatile java.util.function.IntConsumer onStaleOfferPrompted;

	// Clears all GE slot adjustment highlights when the stale-offer queue drains,
	// so a slot highlight never lingers without a matching prompt.
	private volatile Runnable onClearAllHighlights;

	// Provider for the panel's displayed (smart) sell price — preferred over session's stored price
	private volatile IntFunction<Integer> displayedSellPriceProvider;

	// Last overlay message sent — readable by the overlay as a fallback when the
	// async callback result gets lost due to race conditions
	private volatile String lastOverlayMessage;

	// Currently-focused collected item sell prompt — used by skip() to remove stuck items
	private volatile int focusedCollectedItemId = -1;


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

		// Buy prices stored when buy orders were placed — used as cost basis for sell adjustments.
		// Persisted because backend doesn't track the exact price the user paid.
		Map<Integer, Integer> buyPrices;
	}

	public AutoRecommendService(FlipSmartConfig config, FlipSmartPlugin plugin, OfferStore offerStore)
	{
		this.config = config;
		this.plugin = plugin;
		this.offerStore = offerStore;
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

	public void setOnQueueExhausted(Runnable callback)
	{
		this.onQueueExhausted = callback;
	}

	public void setOnOverlayMessageChanged(ObjIntConsumer<String> callback)
	{
		this.onOverlayMessageChanged = callback;
	}

	public void setOnStaleOfferPrompted(java.util.function.IntConsumer callback)
	{
		this.onStaleOfferPrompted = callback;
	}

	public void setOnClearAllHighlights(Runnable callback)
	{
		this.onClearAllHighlights = callback;
	}

	public void setDisplayedSellPriceProvider(IntFunction<Integer> provider)
	{
		this.displayedSellPriceProvider = provider;
	}

	public boolean isActive()
	{
		return active;
	}

	public String getLastOverlayMessage()
	{
		return lastOverlayMessage;
	}

	public long getLastQueueRefreshMillis()
	{
		return queue.getLastQueueRefreshMillis();
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

		if (recommendations == null || recommendations.isEmpty())
		{
			updateStatus("Auto: No recommendations available");
			return;
		}

		// getActiveFlipItemIds() includes all GE buy/sell items + collected items
		Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();

		queue.clear();
		session.clearStaleNotifications();
		queue.setCurrentIndex(0);

		queue.addFilteredByActive(recommendations, activeItemIds);

		if (queue.isEmpty())
		{
			updateStatus("Auto: All recommendations already in GE");
			return;
		}

		// Sort by volume ascending - slowest items listed first
		queue.sortByVolumeAscending();

		active = true;
		queue.setLastQueueRefreshMillis(System.currentTimeMillis());
		log.debug("Auto-recommend started with {} items in queue (sorted by volume asc)", queue.size());
		focusCurrent();

		// Schedule adjustment timers
		// (reevaluateAfterLogin may have fired before auto was active)
		rescheduleAdjustmentTimersAfterLogin();
		rescheduleSellAdjustmentTimersAfterLogin();

		// Immediately check timers that are already past deadline (don't wait for 2-min cycle)
		ensureAllOffersHaveTimers();
	}

	/**
	 * Stop auto-recommend and clear the queue.
	 */
	public synchronized void stop()
	{
		active = false;
		lastOverlayMessage = null;
		queue.clearAll();
		adjustments.clear();
		staleOffers.clear();
		deferredActions.clear();
		PlayerSession session = plugin.getSession();
		if (session != null)
		{
			session.clearStaleNotifications();
		}
		queue.setCurrentIndex(0);
		focusedCollectedItemId = -1;

		invokeFocusCallback(null);

		log.debug("Auto-recommend stopped");
	}

	// =====================
	// Login Re-evaluation
	// =====================

	/**
	 * Re-evaluate auto-recommend state after login.
	 * If auto-recommend was restored, check if collected items need selling
	 * or if GE slots opened up for new buys.
	 * @return true if there are stale buy offers that need an immediate adjustment check
	 */
	public synchronized boolean reevaluateAfterLogin()
	{
		if (!active)
		{
			return false;
		}

		log.debug("Auto-recommend: Re-evaluating after login");
		focusNextAvailableAction();

		// Reschedule adjustment timers from persisted offer timestamps
		boolean hasStaleOffers = rescheduleAdjustmentTimersAfterLogin();
		rescheduleSellAdjustmentTimersAfterLogin();

		// If offers are already past their threshold, show a "checking" status
		// instead of "Waiting for flips" and schedule an early timer check
		if (hasStaleOffers)
		{
			updateStatus("Auto: Checking stale offers...");
			invokeOverlayMessageCallback("Checking stale offers...");
		}

		return hasStaleOffers;
	}

	// =====================
	// GE Event Handlers
	// =====================

	/**
	 * Called when the user places a new buy order for the focused item.
	 */
	public synchronized void onBuyOrderPlaced(int itemId)
	{
		staleOffers.removePrompted(itemId);
		staleOffers.removeOffer(itemId);
		if (!active)
		{
			return;
		}

		FlipRecommendation current = getCurrentRecommendation();
		if (current == null || current.getItemId() != itemId)
		{
			// Non-focused buy: store sell price from queue but don't advance
			FlipRecommendation rec = findRecommendationForItem(itemId);
			if (rec != null && rec.getRecommendedSellPrice() > 0)
			{
				plugin.setRecommendedSellPrice(itemId, rec.getRecommendedSellPrice());
				adjustments.putBuyPrice(itemId, rec.getRecommendedBuyPrice());
				scheduleAdjustmentTimer(itemId, rec.getRecommendedBuyPrice());
				log.debug("Auto-recommend: Non-focused buy for item {} - stored sell price {} from queue",
					itemId, rec.getRecommendedSellPrice());
			}

			if (!hasAvailableGESlots())
			{
				log.debug("Auto-recommend: Non-focused buy filled last GE slot - clearing focus");
				promptCollection();
			}
			return;
		}

		plugin.setRecommendedSellPrice(itemId, current.getRecommendedSellPrice());
		adjustments.putBuyPrice(itemId, current.getRecommendedBuyPrice());

		scheduleAdjustmentTimer(itemId, current.getRecommendedBuyPrice());

		log.debug("Auto-recommend: Buy order placed for {} - advancing to next", current.getItemName());
		advanceToNext();
	}

	/**
	 * Called when a buy order completes (fully bought).
	 * Routes through focusNextAvailableAction so the collect prompt is surfaced
	 * immediately regardless of slot availability. Sell price is already stored
	 * in session via setRecommendedSellPrice() when the buy order was placed.
	 * onOfferCollected will transition to sell when user actually collects.
	 */
	public synchronized void onBuyOrderCompleted(int itemId, String itemName)
	{
		if (!active)
		{
			return;
		}

		clearAdjustmentTimer(itemId);
		log.debug("Auto-recommend: Buy complete for {} - routing for collect", itemName);
		focusNextAvailableAction();
	}

	/**
	 * Override the current auto-recommend focus to show a sell overlay for a collected item.
	 * Called when the user selects an inventory item to sell during auto-recommend.
	 * This is a temporary override — after the sell is placed, focusNextAvailableAction()
	 * resumes normal queue processing.
	 *
	 * @return {@link SellFocusResult} indicating the outcome of the focus attempt.
	 */
	/**
	 * Outcome of {@link #overrideFocusForSell(int, String)}. The caller's retry
	 * logic depends on the distinction: {@link #UNAVAILABLE} means the
	 * collect/inventory/session state has not settled yet and the attempt should
	 * be repeated on a later tick, whereas {@link #ALREADY_SELLING} is a settled,
	 * terminal outcome.
	 */
	public enum SellFocusResult
	{
		/** A sell focus was set on the overlay. */
		FOCUSED,
		/** A live sell offer for this item already exists; left as-is. */
		ALREADY_SELLING,
		/** Price or quantity not resolvable yet (state not settled); retry later. */
		UNAVAILABLE
	}

	public synchronized SellFocusResult overrideFocusForSell(int itemId, String itemName)
	{
		if (!active)
		{
			return SellFocusResult.UNAVAILABLE;
		}

		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return SellFocusResult.UNAVAILABLE;
		}

		// Don't re-show sell overlay if this item already has an active sell order
		if (offerStore.hasActiveSellOfferForItem(itemId))
		{
			log.debug("Auto-recommend: Sell already active for {} - ignoring override", itemName);
			return SellFocusResult.ALREADY_SELLING;
		}

		Integer sellPrice = resolveBestSellPrice(itemId);
		if (sellPrice == null || sellPrice <= 0)
		{
			// Try to recover sell price from recommendation queue
			FlipRecommendation rec = findRecommendationForItem(itemId);
			if (rec != null && rec.getRecommendedSellPrice() > 0)
			{
				sellPrice = rec.getRecommendedSellPrice();
				plugin.setRecommendedSellPrice(itemId, sellPrice);
				log.debug("Auto-recommend: Recovered sell price for {} from queue ({})", itemName, sellPrice);
			}
			else
			{
				log.debug("Auto-recommend: No sell price yet for {} - state not settled, will retry", itemName);
				return SellFocusResult.UNAVAILABLE;
			}
		}

		int collectedQty = resolveSellQuantity(itemId);

		if (collectedQty <= 0)
		{
			log.debug("Auto-recommend: No quantity yet for {} - state not settled, will retry", itemName);
			return SellFocusResult.UNAVAILABLE;
		}

		int priceOffset = config.priceOffset();
		FocusedFlip focus = FocusedFlip.forSell(itemId, itemName, sellPrice, collectedQty, priceOffset);

		invokeFocusCallback(focus);
		updateStatus(String.format(MSG_SELL_FORMAT, itemName, GpUtils.formatGPWithSuffix(sellPrice)));

		log.debug("Auto-recommend: Override focus for sell {} x{} @ {} gp", itemName, collectedQty, sellPrice);
		return SellFocusResult.FOCUSED;
	}

	/**
	 * Override the auto-recommend focus to show a buy overlay for the item whose offer
	 * setup screen is open, when it is a current queue recommendation. Lets the buy the
	 * player is setting up take priority over a pending collect/history prompt while the
	 * offer screen is up. Temporary — focus returns to the queue when the offer screen
	 * closes (lock release via refreshFocusAfterUnlock) or the buy is placed.
	 *
	 * @return true if a buy overlay was focused, false if the item is not a surfaceable
	 *         queued recommendation (caller leaves the overlay as-is).
	 */
	public synchronized boolean overrideFocusForBuy(int itemId)
	{
		if (!active)
		{
			return false;
		}
		// Already live on the GE — the player is past the buy stage for this item.
		if (plugin.getActiveFlipItemIds().contains(itemId))
		{
			return false;
		}
		FlipRecommendation rec = findRecommendationForItem(itemId);
		if (rec == null)
		{
			return false;
		}
		focusBuyOverlay(itemId, rec.getItemName(),
			rec.getRecommendedBuyPrice(), rec.getRecommendedQuantity(), rec.getRecommendedSellPrice(),
			String.format(MSG_BUY_FORMAT, rec.getItemName(),
				GpUtils.formatGPWithSuffix(rec.getRecommendedBuyPrice())));
		log.debug("Auto-recommend: Override focus for buy {} @ {} gp", rec.getItemName(), rec.getRecommendedBuyPrice());
		return true;
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

		log.debug("Auto-recommend: Sell order placed for item {} - checking next action", itemId);

		// Schedule sell adjustment timer
		scheduleSellAdjustmentTimer(itemId);

		// Route through focusNextAvailableAction to check stale queue
		focusNextAvailableAction();
	}

	/**
	 * Called when a sell order fully completes (SOLD state).
	 * Advances to next action — buy if slots available, otherwise prompts collection.
	 */
	public synchronized void onSellOrderCompleted(int itemId)
	{
		if (!active)
		{
			return;
		}

		clearSellAdjustmentTimer(itemId);
		adjustments.removeBuyPrice(itemId);
		log.debug("Auto-recommend: Sell completed for item {} - checking next action", itemId);
		focusNextAvailableAction();
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
	public synchronized void onOfferCancelled(int itemId, String itemName, boolean wasBuy, int filledQuantity, int totalQuantity)
	{
		if (itemName != null)
		{
			queue.putItemName(itemId, itemName);
		}
		staleOffers.removePrompted(itemId);
		staleOffers.removeOffer(itemId);
		if (!active)
		{
			return;
		}

		clearAdjustmentTimer(itemId);
		clearSellAdjustmentTimer(itemId);

		if (wasBuy && filledQuantity > 0)
		{
			trackPartialBuyCancel(itemId, filledQuantity, totalQuantity);
		}
		else if (!wasBuy)
		{
			trackSellCancel(itemId, filledQuantity, totalQuantity);
		}
		else
		{
			log.debug("Auto-recommend: Offer cancelled (wasBuy={}, filled={}/{}) - re-evaluating",
				wasBuy, filledQuantity, totalQuantity);
		}

		// A cancel frees a slot — rewind so a new recommendation surfaces immediately,
		// but skip the just-cancelled item so we don't re-recommend what was dropped.
		focusNextAvailableAction(true, itemId);
	}

	private void trackPartialBuyCancel(int itemId, int filledQuantity, int totalQuantity)
	{
		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return;
		}

		session.addCollectedItem(itemId, filledQuantity);
		ensureSellPriceAvailable(itemId);
		ensureSellPriceFallback(itemId);

		log.debug("Auto-recommend: Partial buy cancelled for item {} ({}/{} filled) - tracked for sell",
			itemId, filledQuantity, totalQuantity);
	}

	private void trackSellCancel(int itemId, int filledQuantity, int totalQuantity)
	{
		int remainingQuantity = totalQuantity - filledQuantity;
		if (remainingQuantity <= 0)
		{
			return;
		}

		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return;
		}

		session.addCollectedItem(itemId, remainingQuantity);
		updateSellPriceFromQueueOrFallback(itemId);
		log.debug("Auto-recommend: Sell cancelled for item {} ({}/{} sold) - tracked {} for re-sell",
			itemId, filledQuantity, totalQuantity, remainingQuantity);
	}

	/**
	 * If no sell price is available (e.g., stale item not in recommendation queue),
	 * use the wiki insta-buy price as a fallback so the sell prompt isn't skipped.
	 */
	private void ensureSellPriceFallback(int itemId)
	{
		PlayerSession session = plugin.getSession();
		if (session == null || session.getRecommendedPrice(itemId) != null)
		{
			return;
		}

		WikiPrice wikiPrice = plugin.getWikiPrice(itemId);
		if (wikiPrice != null && wikiPrice.instaBuy > 0)
		{
			plugin.setRecommendedSellPrice(itemId, wikiPrice.instaBuy);
			log.debug("Auto-recommend: Using wiki insta-buy {} as sell price fallback for item {}",
				wikiPrice.instaBuy, itemId);
		}
	}


	/**
	 * Called when a GE slot becomes empty (user collected items or GP).
	 * Re-evaluates focus based on current state using session's collected items.
	 *
	 * @param itemId The item that was collected
	 * @param wasBuy Whether the collected offer was a buy order
	 * @param itemName The item name (from the collected offer, since it's already removed from session)
	 * @param quantity The filled quantity (from the collected offer)
	 */
	public synchronized void onOfferCollected(int itemId, boolean wasBuy, String itemName, int quantity)
	{
		staleOffers.removeOffer(itemId);
		if (!active)
		{
			return;
		}

		if (wasBuy)
		{
			clearAdjustmentTimer(itemId);
			handleBuyCollected(itemId, itemName, quantity);
		}
		else
		{
			clearSellAdjustmentTimer(itemId);
			adjustments.removeBuyPrice(itemId);
			log.debug("Auto-recommend: Sell collected for {} - advancing", itemName);
			// Collecting a sell frees the slot — rewind so a new buy surfaces.
			focusNextAvailableAction(true);
		}
	}

	/**
	 * Handle collection of a completed buy offer.
	 * Priority: sell THIS item > sell OTHER collected items > buy next > wait.
	 */
	private void handleBuyCollected(int itemId, String itemName, int quantity)
	{
		log.debug("Auto-recommend: Buy collected for {} x{} - checking sell", itemName, quantity);

		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return;
		}

		ensureSellPriceAvailable(itemId);
		boolean isCollected = session.getCollectedItemIds().contains(itemId);
		Integer sellPrice = session.getRecommendedPrice(itemId);
		log.debug("Auto-recommend: Buy collected check - itemId={}, isCollected={}, sellPrice={}",
			itemId, isCollected, sellPrice);

		if (isCollected && sellPrice != null)
		{
			log.debug("Auto-recommend: Focusing sell for {} x{}", itemName, quantity);
			focusSellForItem(itemId, itemName, quantity);
			return;
		}

		// Nothing to sell for this collected buy — the slot is free, so rewind to a buy.
		focusNextAvailableAction(true);
	}

	/**
	 * Determine the next action based on current state.
	 * Priority: collect completed GE offers > sell collected items > resolve
	 * stale offers > buy next from queue > wait.
	 */
	private void focusNextAvailableAction()
	{
		focusNextAvailableAction(false, -1);
	}

	private void focusNextAvailableAction(boolean rewindToFirstAvailableBuy)
	{
		focusNextAvailableAction(rewindToFirstAvailableBuy, -1);
	}

	/**
	 * @param rewindToFirstAvailableBuy when a buy is the next action, rewind
	 * {@code currentIndex} to the first still-valid queue item instead of relying on
	 * its monotonically-advanced position. Set on slot-freeing events (collect /
	 * cancel) so a freed slot always recovers a recommendation.
	 * @param excludeItemId item id to skip when rewinding — set to the just-cancelled
	 * item so a cancel surfaces a different flip rather than re-recommending the same one.
	 */
	private void focusNextAvailableAction(boolean rewindToFirstAvailableBuy, int excludeItemId)
	{
		focusedCollectedItemId = -1;

		// Highest priority: any GE slot with a completed (BOUGHT/SOLD) offer
		// awaiting collection must be surfaced before anything else. Otherwise
		// auto-mode could advance to a new buy on a different slot while the
		// completed slot sits uncollected — and may even recommend a flip for
		// an item the user has already sold, because the slot has not been
		// freed.
		if (!offerStore.completedAwaitingCollection().isEmpty())
		{
			promptCollection();
			return;
		}

		if (hasCollectedItemsToSell())
		{
			focusNextCollectedItemSell();
		}
		else if (!staleOffers.isEmpty())
		{
			// Guide user through stale offers one at a time before new recommendations
			focusNextStaleOffer();
		}
		else if (hasAvailableGESlots())
		{
			// currentIndex only ever moves forward, so a freed slot can leave it
			// parked past every still-valid item (including the one just freed).
			// Rewind to the first surfaceable buy in that case so the slot is never
			// left empty until the next full queue refresh.
			if (rewindToFirstAvailableBuy)
			{
				int idx = firstAvailableBuyIndex(queue.view(),
					plugin.getActiveFlipItemIds(), excludeItemId, config.priceOffset(), config.minimumProfit());
				queue.setCurrentIndex((idx >= 0) ? idx : queue.size());
			}
			if (queue.cursorWithinBounds())
			{
				focusCurrent();
			}
			else
			{
				promptCollection();
			}
		}
		else
		{
			// Slots full or queue exhausted — prompt collection if there are
			// completed offers, otherwise show "Waiting for flips"
			promptCollection();
		}
	}

	/**
	 * First queue index that can be surfaced as a buy right now: not already on the
	 * GE and at or above the minimum adjusted profit. Scans from the front (lowest
	 * volume first), independent of {@code currentIndex}, so a freed slot can recover
	 * an item the monotonic {@code currentIndex} has already advanced past. Returns
	 * -1 when nothing is currently surfaceable.
	 */
	static int firstAvailableBuyIndex(
		List<FlipRecommendation> queue,
		Set<Integer> activeItemIds,
		int excludeItemId,
		int priceOffset,
		int minProfit)
	{
		for (int i = 0; i < queue.size(); i++)
		{
			FlipRecommendation rec = queue.get(i);
			if (rec.getItemId() != excludeItemId
				&& !activeItemIds.contains(rec.getItemId())
				&& FocusedFlip.calculateAdjustedProfit(rec, priceOffset) >= minProfit)
			{
				return i;
			}
		}
		return -1;
	}

	private boolean isUserBusy()
	{
		return isBusyStep(plugin.getFlipAssistOverlayStep());
	}

	private synchronized void executeOrDefer(Runnable action)
	{
		if (isUserBusy())
		{
			deferredActions.add(action);
			log.debug("Auto-recommend: Deferred action (user busy, {} queued)", deferredActions.size());
		}
		else
		{
			action.run();
		}
	}

	public synchronized void onOverlayStepChanged(FlipAssistStep newStep)
	{
		if (!active || deferredActions.isEmpty())
		{
			return;
		}

		if (!isBusyStep(newStep))
		{
			int count = deferredActions.size();
			deferredActions.clear();
			log.debug("Auto-recommend: Draining {} deferred actions after step change to {}", count, newStep);
			focusNextAvailableAction();
		}
	}

	private static boolean isBusyStep(FlipAssistStep step)
	{
		return step == FlipAssistStep.SEARCH_ITEM
			|| step == FlipAssistStep.SET_QUANTITY
			|| step == FlipAssistStep.SET_PRICE
			|| step == FlipAssistStep.CONFIRM_OFFER
			|| step == FlipAssistStep.SET_SELL_PRICE
			|| step == FlipAssistStep.CONFIRM_SELL;
	}

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
			log.debug("Auto-recommend: Recovering sell price for item {} from queue ({})",
				itemId, rec.getRecommendedSellPrice());
			plugin.setRecommendedSellPrice(itemId, rec.getRecommendedSellPrice());
		}
	}

	private void updateSellPriceFromQueueOrFallback(int itemId)
	{
		FlipRecommendation rec = findRecommendationForItem(itemId);
		if (rec != null && rec.getRecommendedSellPrice() > 0)
		{
			plugin.setRecommendedSellPrice(itemId, rec.getRecommendedSellPrice());
			log.debug("Auto-recommend: Updated re-sell price for item {} from queue ({})",
				itemId, rec.getRecommendedSellPrice());
			return;
		}

		// Keep existing stored price — it was set when the buy was originally placed
		PlayerSession session = plugin.getSession();
		if (session != null && session.getRecommendedPrice(itemId) != null)
		{
			log.debug("Auto-recommend: Keeping existing sell price for item {} ({})",
				itemId, session.getRecommendedPrice(itemId));
		}
		else
		{
			log.warn("Auto-recommend: No sell price available for re-sell of item {}", itemId);
		}
	}

	// =====================
	// Queue Refresh
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
		List<FlipRecommendation> filtered = filterAndSortRecommendations(newRecommendations);
		rebuildQueue(filtered, currentRec);

		queue.setLastQueueRefreshMillis(System.currentTimeMillis());
		plugin.getSession().clearStaleNotifications();

		log.debug("Auto-recommend: Queue refreshed with {} items", queue.size());
		// Reflect the item actually focused after the rebuild — currentRec may have
		// been dropped from the queue if Flip Finder no longer recommends it.
		updateOverlayAfterRefresh(getCurrentRecommendation());
	}

	private List<FlipRecommendation> filterAndSortRecommendations(List<FlipRecommendation> newRecommendations)
	{
		Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();
		int priceOffset = config.priceOffset();
		int minProfit = config.minimumProfit();
		List<FlipRecommendation> filtered = new ArrayList<>();
		for (FlipRecommendation rec : newRecommendations)
		{
			queue.putItemName(rec.getItemId(), rec.getItemName());
			if (!activeItemIds.contains(rec.getItemId())
				&& FocusedFlip.calculateAdjustedProfit(rec, priceOffset) >= minProfit)
			{
				filtered.add(rec);
			}
		}
		filtered.sort(Comparator.comparingDouble(FlipRecommendation::getVolumePerHour));
		return filtered;
	}

	private void rebuildQueue(List<FlipRecommendation> filtered, FlipRecommendation currentRec)
	{
		queue.replace(buildRefreshedQueue(filtered, currentRec, plugin.getActiveFlipItemIds()));
	}

	/**
	 * Decide the ordered queue after a Flip Finder refresh. {@code filtered} is the
	 * refreshed, actionable pool (already excludes GE-active and below-min-profit items).
	 * The currently focused item is kept at index 0 only when it is still in that pool,
	 * so churn is avoided when nothing changed but stale items are dropped when Flip
	 * Finder no longer recommends them.
	 */
	static List<FlipRecommendation> buildRefreshedQueue(
		List<FlipRecommendation> filtered,
		FlipRecommendation currentRec,
		Set<Integer> activeItemIds)
	{
		// If currentRec is now on the GE, don't preserve it across the refresh —
		// otherwise it would survive the filter and resurface at index 0.
		boolean currentRecActive = currentRec != null
			&& activeItemIds.contains(currentRec.getItemId());
		// If Flip Finder dropped currentRec from the actionable pool, don't keep it
		// queued — fall through to the refreshed order so the next pull is current.
		boolean currentRecStillRecommended = currentRec != null
			&& filtered.stream().anyMatch(r -> r.getItemId() == currentRec.getItemId());
		if (currentRec == null || currentRecActive || !currentRecStillRecommended)
		{
			return new ArrayList<>(filtered);
		}
		List<FlipRecommendation> queue = new ArrayList<>();
		queue.add(currentRec);
		for (FlipRecommendation rec : filtered)
		{
			if (rec.getItemId() != currentRec.getItemId())
			{
				queue.add(rec);
			}
		}
		return queue;
	}

	private void updateOverlayAfterRefresh(FlipRecommendation currentRec)
	{
		executeOrDefer(() ->
		{
			if (!hasAvailableGESlots() && !hasCollectedItemsToSell())
			{
				// Don't overwrite stale offer prompts with "Monitoring active offers"
				if (!staleOffers.isEmpty())
				{
					focusNextStaleOffer();
				}
				else
				{
					promptCollection();
				}
			}
			else if (hasCollectedItemsToSell() || (!queue.isEmpty() && hasAvailableGESlots()))
			{
				// Re-evaluate priorities: sells first, then buys from refreshed queue
				focusNextAvailableAction();
			}
			else
			{
				updateStatus(String.format("Auto: %d/%d - %s",
					queue.getCurrentIndex() + 1, queue.size(),
					currentRec != null ? currentRec.getItemName() : "Refreshed"));
			}
		});
	}

	// =====================
	// Inactivity Detection
	// =====================

	/**
	 * Check for buy offers that haven't filled within the inactivity timeout.
	 * Updates status to prompt user action. Does NOT auto-cancel.
	 */
	public synchronized void checkInactiveOffers(
		List<FlipRecommendation> currentRecommendations)
	{
		if (!active)
		{
			return;
		}

		long now = System.currentTimeMillis();
		PlayerSession session = plugin.getSession();

		// Find the first stale buy offer that hasn't been notified yet
		OfferRecord staleOffer = findFirstStaleOffer(session, now);
		if (staleOffer == null)
		{
			return;
		}

		int itemId = staleOffer.getItemId();
		session.addStaleNotified(itemId);

		// Only prompt if the offer is uncompetitive (red border).
		// Green-bordered offers are still within the market spread.
		FlipSmartPlugin.OfferCompetitiveness comp = plugin.calculateCompetitiveness(staleOffer);
		if (comp != FlipSmartPlugin.OfferCompetitiveness.UNCOMPETITIVE)
		{
			log.debug("Auto-recommend: Stale offer for {} but still competitive — skipping", staleOffer.getItemName());
			return;
		}

		long age = now - staleOffer.getEffectiveLastActivityAtMillis();
		log.debug("Auto-recommend: Stale & uncompetitive offer for {} (age: {}m)",
			staleOffer.getItemName(), age / 60000);

		addToStaleQueue(staleOffer);
	}

	/**
	 * Find the first tracked buy offer that is stale and hasn't been notified yet.
	 */
	private OfferRecord findFirstStaleOffer(
		PlayerSession session,
		long now)
	{
		for (OfferRecord offer : offerStore.liveOffers())
		{
			if (!offer.isBuy() || offer.getState() == OfferState.FILLED)
			{
				continue;
			}

			long age = now - offer.getEffectiveLastActivityAtMillis();
			if (age >= INACTIVITY_TIMEOUT_MS && !session.isStaleNotified(offer.getItemId()))
			{
				return offer;
			}
		}
		return null;
	}

	// =====================
	// Adjustment Timers
	// =====================

	/**
	 * Schedule an adjustment timer for a buy offer.
	 * When the timer expires, checkAdjustmentTimers() will prompt the user
	 * to adjust the price if the recommendation has changed.
	 */
	private void scheduleAdjustmentTimer(int itemId, int itemPrice)
	{
		long delay = AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
		long deadline = System.currentTimeMillis() + delay;
		adjustments.putBuyDeadline(itemId, deadline);
		log.debug("Auto-recommend: Adjustment timer scheduled for item {} in {}m", itemId, delay / 60000);
	}

	/**
	 * Reset the adjustment timer for an item (e.g., after a partial fill).
	 */
	public synchronized void resetAdjustmentTimer(int itemId, int itemPrice)
	{
		if (!active || !adjustments.hasBuyDeadline(itemId))
		{
			return;
		}

		long delay = AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
		long deadline = System.currentTimeMillis() + delay;
		adjustments.putBuyDeadline(itemId, deadline);
		log.debug("Auto-recommend: Adjustment timer reset for item {} ({}m)", itemId, delay / 60000);

		// Clear stale-notified flag so the 15-minute inactivity check can fire again
		// if the offer goes quiet after this fill
		PlayerSession session = plugin.getSession();
		if (session != null)
		{
			session.removeStaleNotified(itemId);
		}
	}

	/**
	 * Clear the adjustment timer for an item.
	 */
	private void clearAdjustmentTimer(int itemId)
	{
		if (adjustments.removeBuyDeadline(itemId))
		{
			log.debug("Auto-recommend: Adjustment timer cleared for item {}", itemId);
		}
	}

	/**
	 * Guard for the legacy adjustment path: disabled only when Active timeframe is
	 * selected and the Active-offer advisor is enabled (the advisor owns that flow).
	 */
	public static boolean shouldRunLegacyAdjustment(String timeframeApiValue, boolean activeAdvisorEnabled)
	{
		boolean isActive = "active".equals(timeframeApiValue);
		return !(isActive && activeAdvisorEnabled);
	}

	/**
	 * Check all adjustment timers and prompt the user to adjust unfilled buy offers
	 * when the recommended price has changed.
	 *
	 * Called periodically from the auto-recommend refresh timer (2-minute interval).
	 */
	public synchronized void checkAdjustmentTimers(
		List<FlipRecommendation> currentRecommendations)
	{
		if (!shouldRunLegacyAdjustment(config.flipTimeframe().getApiValue(), config.enableActiveOfferAdvisor()))
		{
			return;
		}
		if (!active || !adjustments.hasBuyDeadlines())
		{
			log.debug("Auto-recommend: checkAdjustmentTimers skipped (active={}, deadlines={})",
				active, adjustments.buyDeadlineCount());
			return;
		}

		long now = System.currentTimeMillis();
		log.debug("Auto-recommend: Checking {} adjustment timers (recommendations={})",
			adjustments.buyDeadlineCount(),
			currentRecommendations != null ? currentRecommendations.size() : "null");

		Iterator<Map.Entry<Integer, Long>> iter = adjustments.buyDeadlineIterator();

		while (iter.hasNext())
		{
			Map.Entry<Integer, Long> entry = iter.next();
			long deadline = entry.getValue();
			String itemName = queue.getItemName(entry.getKey(), "id=" + entry.getKey());
			if (now >= deadline)
			{
				log.debug("Auto-recommend: Timer expired for {} (overdue by {}s)",
					itemName, (now - deadline) / 1000);
				processExpiredAdjustmentTimer(entry, currentRecommendations, iter, now);
			}
			else
			{
				log.debug("Auto-recommend: Timer for {} expires in {}s", itemName, (deadline - now) / 1000);
			}
		}
	}

	/**
	 * Process a single expired adjustment timer entry.
	 * Calls the backend /flips/adjustment API for the authoritative decision,
	 * then acts on the response (adjust, cancel, or reschedule).
	 */
	private void processExpiredAdjustmentTimer(
		Map.Entry<Integer, Long> entry,
		List<FlipRecommendation> currentRecommendations,
		Iterator<Map.Entry<Integer, Long>> iter,
		long now)
	{
		int itemId = entry.getKey();

		OfferRecord offer = findTrackedBuyOffer(itemId);
		if (offer == null || offer.getState() == OfferState.FILLED)
		{
			iter.remove();
			return;
		}

		// Set a temporary long deadline to prevent duplicate calls while API responds
		entry.setValue(now + 5 * 60 * 1000L);

		int minutesSince = (int) ((now - offer.getEffectiveLastActivityAtMillis()) / 60000);
		String timeframe = config.flipTimeframe().getApiValue();
		String itemName = offer.getItemName();

		// Get buy price (cost basis) for breakeven calculation
		Integer costBasis = adjustments.getBuyPriceOrDefault(itemId, offer.getPrice());

		log.debug("Auto-recommend: Checking buy adjustment for {} (price={}, {}min elapsed)",
			itemName, offer.getPrice(), minutesSince);

		plugin.getApiClient().getFlipAdjustmentAsync(FlipAdjustmentRequest.builder()
			.itemId(itemId)
			.isBuyOffer(true)
			.offerPrice(offer.getPrice())
			.averageBuyPrice(costBasis)
			.minutesSinceOffer(minutesSince)
			.adjustmentCount(0)
			.quantityFilled(offer.getFilledQuantity())
			.totalQuantity(offer.getTotalQuantity())
			.timeframe(timeframe)
			.build()
		).thenAccept(response ->
		{
			synchronized (this)
			{
				handleBuyAdjustmentResponse(itemId, offer, response);
			}
		}).exceptionally(ex ->
		{
			log.warn("Auto-recommend: Buy adjustment API error for {}: {}", itemName, ex.getMessage());
			synchronized (this)
			{
				// Reschedule with fallback delay
				adjustments.putBuyDeadline(itemId, System.currentTimeMillis() + AdjustmentTimerUtils.FALLBACK_CHECK_DELAY_MS);
			}
			return null;
		});
	}

	/**
	 * Handle the response from the buy adjustment API.
	 */
	private void handleBuyAdjustmentResponse(int itemId, OfferRecord offer,
		FlipAdjustmentResponse response)
	{
		if (response == null)
		{
			return;
		}

		long nextDelay = AdjustmentTimerUtils.nextCheckDelayMs(response.getNextCheckMinutes());

		if (!response.isActionRequired())
		{
			// Hold — reschedule using API timing
			adjustments.putBuyDeadline(itemId, System.currentTimeMillis() + nextDelay);
			log.debug("Auto-recommend: Hold for {} - rescheduling in {}m",
				offer.getItemName(), nextDelay / 60000);
			return;
		}

		// Only prompt if the offer is actually uncompetitive (red border).
		// Green-bordered offers are still within the market spread and likely to fill.
		FlipSmartPlugin.OfferCompetitiveness comp = plugin.calculateCompetitiveness(offer);
		if (comp != FlipSmartPlugin.OfferCompetitiveness.UNCOMPETITIVE)
		{
			log.debug("Auto-recommend: API suggests action for {} but offer is still competitive — rescheduling",
				offer.getItemName());
			adjustments.putBuyDeadline(itemId, System.currentTimeMillis() + nextDelay);
			return;
		}

		if (response.isReadjustBuy() && response.getRecommendedPrice() != null)
		{
			log.debug("Auto-recommend: API recommends adjust {} buy {} → {} gp",
				offer.getItemName(), offer.getPrice(), response.getRecommendedPrice());
			addToStaleQueue(offer);
			adjustments.removeBuyDeadline(itemId);
		}
		else if (response.isCancelAndSell())
		{
			log.debug("Auto-recommend: API says cancel {} — margin gone", offer.getItemName());
			addToStaleQueue(offer);
			adjustments.removeBuyDeadline(itemId);
		}
	}

	/**
	 * Reschedule adjustment timers after login for any active unfilled buy offers
	 * that don't already have a timer.
	 *
	 * Reads offer age from the live offer store (createdAtMillis is preserved
	 * across sessions when offers are restored).
	 *
	 * @return true if any offers are already past their adjustment threshold
	 */
	private boolean rescheduleAdjustmentTimersAfterLogin()
	{
		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return false;
		}

		long now = System.currentTimeMillis();
		boolean anyAlreadyStale = false;

		for (OfferRecord offer : offerStore.liveOffers())
		{
			if (!offer.isBuy() || offer.getState() == OfferState.FILLED
				|| adjustments.hasBuyDeadline(offer.getItemId()))
			{
				continue;
			}

			long delayMs = AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
			long offerAgeMs = now - offer.getEffectiveLastActivityAtMillis();
			long remainingMs = delayMs - offerAgeMs;

			if (remainingMs <= 0)
			{
				adjustments.putBuyDeadline(offer.getItemId(), now - 1);
				anyAlreadyStale = true;
				log.debug("Auto-recommend: {} already stale (age={}m, threshold={}m) — will check immediately",
					offer.getItemName(), offerAgeMs / 60000, delayMs / 60000);
			}
			else
			{
				adjustments.putBuyDeadline(offer.getItemId(), now + remainingMs);
				log.debug("Auto-recommend: Rescheduled adjustment timer for {} in {}m (age={}m, threshold={}m)",
					offer.getItemName(), remainingMs / 60000, offerAgeMs / 60000, delayMs / 60000);
			}
		}

		return anyAlreadyStale;
	}

	/**
	 * Ensure all tracked offers have adjustment timers scheduled.
	 * Called on each 2-minute refresh cycle to catch offers that were
	 * missed (e.g., placed before a plugin rebuild or not present at login).
	 */
	public synchronized void ensureAllOffersHaveTimers()
	{
		if (!active)
		{
			return;
		}

		long now = System.currentTimeMillis();
		for (OfferRecord offer : offerStore.liveOffers())
		{
			if (offer.getState() == OfferState.FILLED)
			{
				continue;
			}

			if (offer.isBuy() && !adjustments.hasBuyDeadline(offer.getItemId()))
			{
				scheduleMissingBuyTimer(offer, now);
			}
			else if (!offer.isBuy() && !adjustments.hasSellState(offer.getItemId()))
			{
				scheduleMissingSellTimer(offer, now);
			}
		}
	}

	private void scheduleMissingBuyTimer(OfferRecord offer, long now)
	{
		long offerAgeMs = now - offer.getEffectiveLastActivityAtMillis();
		long deadline = offerAgeMs >= AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS
			? now - 1 : now + (AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS - offerAgeMs);
		adjustments.putBuyDeadline(offer.getItemId(), deadline);
		log.debug("Auto-recommend: Scheduled missing buy timer for {} (age={}m)",
			offer.getItemName(), offerAgeMs / 60000);
	}

	private void scheduleMissingSellTimer(OfferRecord offer, long now)
	{
		Integer buyPrice = adjustments.getBuyPriceOrDefault(offer.getItemId(), offer.getPrice());
		long offerAgeMs = now - offer.getEffectiveLastActivityAtMillis();
		long deadline = offerAgeMs >= AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS
			? now - 1 : now + (AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS - offerAgeMs);
		SellAdjustmentState state = new SellAdjustmentState(
			offer.getItemId(), offer.getItemName(), buyPrice, deadline);
		adjustments.putSellState(offer.getItemId(), state);
		log.debug("Auto-recommend: Scheduled missing sell timer for {} (age={}m)",
			offer.getItemName(), offerAgeMs / 60000);
	}

	// =====================
	// Stale Offer Scan (timer-independent)
	// =====================

	/**
	 * Show the next stale offer in the queue for user action.
	 * Displays one at a time with a counter (e.g., "1/3: Cancel Berserker necklace?").
	 * When the queue is exhausted, falls through to normal focusNextAvailableAction.
	 */
	private void focusNextStaleOffer()
	{
		// Remove offers that are no longer relevant:
		// - cancelled/collected (no longer in GE)
		// - became competitive (green border — price is back in the spread)
		PlayerSession session = plugin.getSession();
		if (session != null)
		{
			List<OfferRecord> currentOffers = offerStore.liveOffers();
			staleOffers.pruneIrrelevant(o ->
			{
				OfferRecord current = currentOffers.stream()
					.filter(t -> t.getItemId() == o.getItemId() && t.getState() != OfferState.FILLED)
					.findFirst().orElse(null);
				if (current == null)
				{
					return true; // No longer in GE
				}
				// Re-check competitiveness — wiki prices may have refreshed
				FlipSmartPlugin.OfferCompetitiveness comp = plugin.calculateCompetitiveness(current);
				return comp != FlipSmartPlugin.OfferCompetitiveness.UNCOMPETITIVE;
			});
		}

		if (staleOffers.isEmpty())
		{
			// All stale offers handled — drop any lingering slot highlight, resume normal flow
			if (onClearAllHighlights != null)
			{
				onClearAllHighlights.run();
			}
			focusNextAvailableAction();
			return;
		}

		OfferRecord next = staleOffers.head();
		Integer resellPrice = staleOffers.getResellPrice(next.getItemId());
		String overlayMsg;
		if (!next.isBuy() && resellPrice != null)
		{
			Integer net = staleOffers.getResellNet(next.getItemId());
			String netSuffix = net == null ? ""
				: String.format(" (%s%s)", net >= 0 ? "+" : "-", GpUtils.formatGP(Math.abs(net)));
			overlayMsg = String.format("Re-sell %s at:\n%s gp%s", next.getItemName(),
				String.format("%,d", resellPrice), netSuffix);
		}
		else
		{
			overlayMsg = String.format("Consider cancelling:\n%s", next.getItemName());
		}

		updateStatus("Auto: " + overlayMsg);
		invokeFocusCallback(null);
		invokeOverlayMessageCallback(overlayMsg, next.getItemId());

		java.util.function.IntConsumer staleCallback = onStaleOfferPrompted;
		if (staleCallback != null)
		{
			staleCallback.accept(next.getItemId());
		}
	}

	/**
	 * Add a tracked offer to the stale queue if not already present.
	 * If the queue was empty, immediately shows the first prompt.
	 */
	void addToStaleQueue(OfferRecord offer)
	{
		StaleOfferQueue.AddResult result = staleOffers.addIfAbsent(offer);
		if (result == StaleOfferQueue.AddResult.ALREADY_PRESENT)
		{
			// Don't add duplicates
			return;
		}

		log.debug("Auto-recommend: Added {} to stale queue (queue size: {})",
			offer.getItemName(), staleOffers.size());

		if (result == StaleOfferQueue.AddResult.ADDED_WAS_EMPTY)
		{
			executeOrDefer(this::focusNextStaleOffer);
		}
	}

	/**
	 * Surface an Active-mode advisor sell re-list recommendation through the stale-resell
	 * prompt so the overlay shows "Re-sell &lt;item&gt; at: &lt;price&gt;" and the re-list auto-fill
	 * uses the advised price. Idempotent — addToStaleQueue dedupes by item, and the price
	 * map is refreshed on each poll.
	 */
	public synchronized void surfaceAdvisorResell(OfferRecord offer, int newPrice, Integer netProfitEstimate)
	{
		if (offer == null)
		{
			return;
		}
		staleOffers.putResellPrice(offer.getItemId(), newPrice);
		if (netProfitEstimate != null)
		{
			staleOffers.putResellNet(offer.getItemId(), netProfitEstimate);
		}
		else
		{
			staleOffers.removeResellNet(offer.getItemId());
		}
		PlayerSession sess = plugin.getSession();
		if (sess != null)
		{
			sess.setRecommendedPrice(offer.getItemId(), newPrice);
		}
		addToStaleQueue(offer);
	}

	/**
	 * Retract a previously-surfaced advisor sell prompt (the advisor changed its mind, e.g.
	 * the market recovered and it now returns WAIT). Refreshes focus to whatever is next.
	 */
	public synchronized void removeAdvisorResell(int itemId)
	{
		boolean wasHead = staleOffers.headIsItem(itemId);
		staleOffers.removeOffer(itemId);
		if (wasHead)
		{
			// Re-prompt the new head (re-highlights it) or clears highlights if the queue drained.
			executeOrDefer(this::focusNextStaleOffer);
		}
	}

	// =====================
	// Sell Adjustment Timers
	// =====================

	/**
	 * Schedule a sell adjustment timer for an item.
	 * Uses the stored buy price as cost basis for breakeven calculations.
	 */
	private void scheduleSellAdjustmentTimer(int itemId)
	{
		// Find the tracked sell offer to get current price and item name
		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return;
		}

		OfferRecord sellOffer = findTrackedSellOffer(itemId);
		if (sellOffer == null)
		{
			return;
		}

		// Get the buy price (cost basis) — stored when the buy was placed
		Integer buyPrice = adjustments.getBuyPrice(itemId);
		if (buyPrice == null)
		{
			// Fallback: try recommendation queue
			FlipRecommendation rec = findRecommendationForItem(itemId);
			if (rec != null)
			{
				buyPrice = rec.getRecommendedBuyPrice();
			}
		}
		if (buyPrice == null)
		{
			// Last resort: use the sell price itself (breakeven will be approximate)
			buyPrice = sellOffer.getPrice();
		}

		long delay = AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
		long deadline = System.currentTimeMillis() + delay;

		SellAdjustmentState state = new SellAdjustmentState(
			itemId, sellOffer.getItemName(), buyPrice, deadline);
		adjustments.putSellState(itemId, state);

		log.debug("Auto-recommend: Sell adjustment timer scheduled for {} in {}m (buyPrice={})",
			sellOffer.getItemName(), delay / 60000, buyPrice);
	}

	/**
	 * Clear the sell adjustment timer for an item.
	 */
	private void clearSellAdjustmentTimer(int itemId)
	{
		if (adjustments.removeSellState(itemId))
		{
			log.debug("Auto-recommend: Sell adjustment timer cleared for item {}", itemId);
		}
	}

	/**
	 * Reset the sell adjustment timer for an item (e.g., after a partial fill).
	 */
	public synchronized void resetSellAdjustmentTimer(int itemId)
	{
		if (!active)
		{
			return;
		}

		SellAdjustmentState state = adjustments.getSellState(itemId);
		if (state == null)
		{
			return;
		}

		state.deadline = System.currentTimeMillis() + AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
		log.debug("Auto-recommend: Sell adjustment timer reset for item {} ({}m)", itemId,
			AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS / 60000);

		// Clear stale-notified flag so the inactivity check can fire again
		PlayerSession session = plugin.getSession();
		if (session != null)
		{
			session.removeStaleNotified(itemId);
		}
	}

	/**
	 * Check all sell adjustment timers and call the API for expired ones.
	 * Called periodically from the auto-recommend refresh timer (2-minute interval).
	 */
	public synchronized void checkSellAdjustmentTimers()
	{
		if (!shouldRunLegacyAdjustment(config.flipTimeframe().getApiValue(), config.enableActiveOfferAdvisor()))
		{
			return;
		}
		if (!active || !adjustments.hasSellStates())
		{
			return;
		}

		long now = System.currentTimeMillis();
		Iterator<Map.Entry<Integer, SellAdjustmentState>> iter = adjustments.sellStateIterator();

		while (iter.hasNext())
		{
			Map.Entry<Integer, SellAdjustmentState> entry = iter.next();
			SellAdjustmentState state = entry.getValue();
			processOneSellTimer(state, now, iter);
		}
	}

	private void processOneSellTimer(SellAdjustmentState state,
		long now, Iterator<Map.Entry<Integer, SellAdjustmentState>> iter)
	{
		if (now < state.deadline)
		{
			return;
		}

		OfferRecord offer = findTrackedSellOffer(state.itemId);
		if (offer == null || offer.getState() == OfferState.FILLED)
		{
			iter.remove();
			return;
		}

		// Timer expired — call the adjustment API
		int minutesSince = (int) ((now - offer.getEffectiveLastActivityAtMillis()) / 60000);
		callSellAdjustmentApi(state, offer, minutesSince);

		// Set a temporary long deadline to prevent duplicate calls (API callback will reschedule)
		state.deadline = now + 5 * 60 * 1000L;
	}

	/**
	 * Call the /flips/adjustment API for a sell offer and handle the response.
	 */
	private void callSellAdjustmentApi(SellAdjustmentState state, OfferRecord offer, int minutesSince)
	{
		String timeframe = config.flipTimeframe().getApiValue();

		log.debug("Auto-recommend: Checking sell adjustment for {} (price={}, buyPrice={}, {}min elapsed, adj#{})",
			state.itemName, offer.getPrice(), state.averageBuyPrice, minutesSince, state.adjustmentCount);

		PlayerSession session = plugin.getSession();
		String rsn = session != null ? session.getRsnSafe().orElse(null) : null;

		plugin.getApiClient().getFlipAdjustmentAsync(FlipAdjustmentRequest.builder()
			.itemId(state.itemId)
			.isBuyOffer(false)
			.offerPrice(offer.getPrice())
			.averageBuyPrice(state.averageBuyPrice)
			.minutesSinceOffer(minutesSince)
			.adjustmentCount(state.adjustmentCount)
			.quantityFilled(offer.getFilledQuantity())
			.totalQuantity(offer.getTotalQuantity())
			.timeframe(timeframe)
			.rsn(rsn)
			.build()
		).thenAccept(response ->
		{
			synchronized (this)
			{
				handleSellAdjustmentResponse(state, offer, response);
			}
		}).exceptionally(ex ->
		{
			log.warn("Auto-recommend: Sell adjustment API error for {}: {}", state.itemName, ex.getMessage());
			// Reschedule for retry
			state.deadline = System.currentTimeMillis() + AdjustmentTimerUtils.FALLBACK_CHECK_DELAY_MS;
			return null;
		});
	}

	/**
	 * Handle the response from the sell adjustment API.
	 */
	private void handleSellAdjustmentResponse(SellAdjustmentState state, OfferRecord offer, FlipAdjustmentResponse response)
	{
		if (response == null)
		{
			return;
		}

		if (!response.isActionRequired())
		{
			// Hold — reschedule using API-provided interval
			long delay = AdjustmentTimerUtils.nextCheckDelayMs(response.getNextCheckMinutes());
			state.deadline = System.currentTimeMillis() + delay;
			log.debug("Auto-recommend: Sell hold for {} - rescheduling ({}m)", state.itemName, delay / 60000);
			return;
		}

		if (response.isReadjustSell() && response.getRecommendedPrice() != null)
		{
			int newPrice = response.getRecommendedPrice();
			state.adjustmentCount++;

			log.debug("Auto-recommend: Sell adjustment for {} — {} → {} gp (adj#{})",
				state.itemName, offer.getPrice(), newPrice, state.adjustmentCount);

			// The API already determined this sell is overpriced — always queue the prompt.
			// Unlike buy adjustments (where local wiki competitiveness is a useful gate),
			// sell adjustments use backend market data which is authoritative.
			staleOffers.putResellPrice(offer.getItemId(), newPrice);
			staleOffers.removeResellNet(offer.getItemId());  // legacy adjustment has no advisor net estimate
			addToStaleQueue(offer);

			// Persist to session so relist auto-fill uses the adjusted price
			PlayerSession sess = plugin.getSession();
			if (sess != null)
			{
				sess.setRecommendedPrice(offer.getItemId(), newPrice);
			}

			// Reschedule in case user doesn't act or offer is still competitive
			state.deadline = System.currentTimeMillis()
				+ AdjustmentTimerUtils.nextCheckDelayMs(response.getNextCheckMinutes());
		}
		else
		{
			// Unexpected action — log and reschedule
			log.debug("Auto-recommend: Sell adjustment response for {}: action={}, message={}",
				state.itemName, response.getAction(), response.getMessage());
			state.deadline = System.currentTimeMillis()
				+ AdjustmentTimerUtils.nextCheckDelayMs(response.getNextCheckMinutes());
		}
	}

	/**
	 * Reschedule sell adjustment timers after login for any active sell offers
	 * that don't already have a timer.
	 */
	private void rescheduleSellAdjustmentTimersAfterLogin()
	{
		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			return;
		}

		long now = System.currentTimeMillis();

		for (OfferRecord offer : offerStore.liveOffers())
		{
			if (offer.isBuy() || offer.getState() == OfferState.FILLED
				|| adjustments.hasSellState(offer.getItemId()))
			{
				continue;
			}

			// Get buy price from stored prices or fall back to sell price
			Integer buyPrice = adjustments.getBuyPrice(offer.getItemId());
			if (buyPrice == null)
			{
				buyPrice = offer.getPrice();
			}

			long delayMs = AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
			long offerAgeMs = now - offer.getEffectiveLastActivityAtMillis();
			long remainingMs = delayMs - offerAgeMs;

			long deadline;
			if (remainingMs <= 0)
			{
				deadline = now - 1;
				log.debug("Auto-recommend: Sell {} already stale (age={}m, threshold={}m) — will check immediately",
					offer.getItemName(), offerAgeMs / 60000, delayMs / 60000);
			}
			else
			{
				deadline = now + remainingMs;
				log.debug("Auto-recommend: Rescheduled sell adjustment timer for {} in {}m (age={}m, threshold={}m)",
					offer.getItemName(), remainingMs / 60000, offerAgeMs / 60000, delayMs / 60000);
			}

			SellAdjustmentState state = new SellAdjustmentState(
				offer.getItemId(), offer.getItemName(), buyPrice, deadline);
			adjustments.putSellState(offer.getItemId(), state);
		}
	}

	/**
	 * Find a tracked sell offer for the given item ID.
	 */
	private OfferRecord findTrackedSellOffer(int itemId)
	{
		for (OfferRecord offer : offerStore.liveOffers())
		{
			if (offer.getItemId() == itemId && !offer.isBuy())
			{
				return offer;
			}
		}
		return null;
	}

	/**
	 * Find a tracked buy offer for the given item ID.
	 */
	private OfferRecord findTrackedBuyOffer(int itemId)
	{
		for (OfferRecord offer : offerStore.liveOffers())
		{
			if (offer.getItemId() == itemId && offer.isBuy())
			{
				return offer;
			}
		}
		return null;
	}

	// =====================
	// Persistence
	// =====================

	/**
	 * Create a serializable snapshot of the current state.
	 */
	public synchronized PersistedState getStateForPersistence()
	{
		PersistedState state = new PersistedState();
		state.active = active;
		state.queue = queue.snapshot();
		state.currentIndex = queue.getCurrentIndex();
		state.savedAtMillis = System.currentTimeMillis();

		if (!adjustments.buyPricesEmpty())
		{
			state.buyPrices = adjustments.buyPricesSnapshot();
		}

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
			log.debug("Auto-recommend: Persisted state is stale ({}m old), not restoring", age / 60000);
			return false;
		}

		if (state.queue == null || state.queue.isEmpty())
		{
			return false;
		}

		queue.clear();
		queue.view().addAll(state.queue);
		for (FlipRecommendation rec : state.queue)
		{
			queue.putItemName(rec.getItemId(), rec.getItemName());
		}
		int restoredIndex = Math.min(state.currentIndex, queue.size() - 1);
		restoredIndex = Math.max(0, restoredIndex);
		queue.setCurrentIndex(restoredIndex);

		plugin.getSession().clearStaleNotifications();
		active = true;
		queue.setLastQueueRefreshMillis(state.savedAtMillis);

		// Restore buy prices — needed as cost basis for sell adjustment calculations.
		// Timers are NOT persisted — they're rebuilt from backend transaction timestamps
		// on login via rescheduleAdjustmentTimersAfterLogin().
		if (state.buyPrices != null)
		{
			adjustments.putAllBuyPrices(state.buyPrices);
			log.debug("Auto-recommend: Restored {} buy prices from persisted state", state.buyPrices.size());
		}

		log.debug("Auto-recommend: Restored state with {} items in queue, index {}",
			queue.size(), queue.getCurrentIndex());

		focusCurrent();
		return true;
	}

	// =====================
	// Internal Navigation
	// =====================

	/**
	 * Skip the current recommendation or stale offer prompt.
	 * Called when the user clicks the Skip button during auto-recommend.
	 */
	public synchronized void skip()
	{
		if (!active)
		{
			return;
		}

		if (!staleOffers.isEmpty())
		{
			OfferRecord skipped = staleOffers.removeHead();
			log.debug("Auto-recommend: User skipped stale offer prompt for {}", skipped.getItemName());
			focusNextAvailableAction();
			return;
		}

		// If we're currently showing a collected-item sell prompt, skip it
		int collectedId = focusedCollectedItemId;
		if (collectedId >= 0)
		{
			PlayerSession session = plugin.getSession();
			log.debug("Auto-recommend: User skipped collected item sell prompt for item {}", collectedId);
			session.removeCollectedItem(collectedId);
			focusedCollectedItemId = -1;
			focusNextAvailableAction();
			return;
		}

		log.debug("Auto-recommend: User skipped current recommendation");
		advanceToNext();
	}

	private void advanceToNext()
	{
		int priceOffset = config.priceOffset();
		int minProfit = config.minimumProfit();

		queue.skipToNextSurfaceable(
			plugin.getActiveFlipItemIds(),
			next -> FocusedFlip.calculateAdjustedProfit(next, priceOffset),
			minProfit);

		if (queue.cursorBeyondEnd())
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

				Runnable exhaustedCallback = onQueueExhausted;
				if (exhaustedCallback != null)
				{
					javax.swing.SwingUtilities.invokeLater(exhaustedCallback);
				}
			}
			return;
		}

		if (!hasAvailableGESlots())
		{
			log.debug("Auto-recommend: All GE slots full - waiting for collection");
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

		// Re-validate against active GE state. The queue can carry an already-listed
		// item into focus via paths that bypass advanceToNext()'s skip-loop:
		// rebuildQueue() preserving currentRec across a refresh, restoreState() on
		// relogin, or focusCurrent() callers when slots free up. Catch them all here
		// so we never recommend an item the player already has on the GE.
		if (plugin.getActiveFlipItemIds().contains(rec.getItemId()))
		{
			log.debug("Auto-recommend: Current rec {} is already on GE - advancing", rec.getItemName());
			advanceToNext();
			return;
		}

		// Never show a buy recommendation when all GE slots are occupied
		if (!hasAvailableGESlots())
		{
			promptCollection();
			return;
		}

		focusBuyOverlay(rec.getItemId(), rec.getItemName(),
			rec.getRecommendedBuyPrice(), rec.getRecommendedQuantity(), rec.getRecommendedSellPrice(),
			String.format("Auto: %d/%d - %s", queue.getCurrentIndex() + 1, queue.size(), rec.getItemName()));
		invokeQueueAdvancedCallback();
	}

	/**
	 * Focus the sell side for a specific item with known name and quantity.
	 * Used when we have direct info from the just-collected offer.
	 */
	private void focusSellForItem(int itemId, String itemName, int quantity)
	{
		Integer sellPrice = resolveBestSellPrice(itemId);

		if (sellPrice == null || sellPrice <= 0)
		{
			log.warn("Auto-recommend: No recommended sell price for {} ({})", itemName, itemId);
			focusNextCollectedItemSell();
			return;
		}

		// Always prefer inventory count (source of truth) over passed-in quantity
		int sellQuantity = resolveSellQuantity(itemId);
		if (sellQuantity <= 0)
		{
			sellQuantity = quantity;
		}

		int priceOffset = config.priceOffset();
		FocusedFlip focus = FocusedFlip.forSell(
			itemId,
			itemName,
			sellPrice,
			sellQuantity,
			priceOffset
		);

		invokeFocusCallback(focus);
		invokeQueueAdvancedCallback();

		updateStatus(String.format(MSG_SELL_FORMAT,
			itemName, GpUtils.formatGPWithSuffix(sellPrice)));
	}

	/**
	 * Focus the sell side for the next collected item that needs selling.
	 * Uses session state for sell price (independent of recommendation queue).
	 * Uses cached itemNames map for item name (survives queue refreshes).
	 */
	private void focusNextCollectedItemSell()
	{
		int sellableItemId = findNextSellableCollectedItem();
		if (sellableItemId >= 0)
		{
			Integer sellPrice = resolveBestSellPrice(sellableItemId);

			if (sellPrice != null && sellPrice > 0)
			{
				String itemName = queue.getItemName(sellableItemId, plugin.getItemName(sellableItemId));
				int sellQuantity = resolveSellQuantity(sellableItemId);

				int priceOffset = config.priceOffset();
				FocusedFlip focus = FocusedFlip.forSell(
					sellableItemId,
					itemName,
					sellPrice,
					sellQuantity,
					priceOffset
				);

				focusedCollectedItemId = sellableItemId;
				invokeFocusCallback(focus);
				invokeQueueAdvancedCallback();

				updateStatus(String.format(MSG_SELL_FORMAT,
					itemName, GpUtils.formatGPWithSuffix(sellPrice)));
				return;
			}

			// Collected item has no sell price — clean it up so it doesn't block the queue
			log.warn("Auto-recommend: Removing collected item {} with no sell price (price={})",
				sellableItemId, sellPrice);
			plugin.getSession().removeCollectedItem(sellableItemId);
		}

		focusedCollectedItemId = -1;

		// No sellable items can be properly displayed - check buy queue
		if (hasAvailableGESlots() && queue.cursorWithinBounds())
		{
			focusCurrent();
		}
		else
		{
			// Clear any stale focus so the hint box can show
			invokeFocusCallback(null);
			promptCollection();
		}
	}

	/**
	 * Find the next collected item that needs selling.
	 * Returns the item ID, or -1 if none found.
	 * Cleans up stale entries where the item is no longer in inventory or GE.
	 */
	private int findNextSellableCollectedItem()
	{
		PlayerSession session = plugin.getSession();
		List<Integer> staleItems = new ArrayList<>();

		for (int itemId : session.getCollectedItemIds())
		{
			if (offerStore.hasActiveSellOfferForItem(itemId))
			{
				continue;
			}

			int result = evaluateCollectedItem(itemId, staleItems);
			if (result >= 0)
			{
				return result;
			}
		}

		for (int staleId : staleItems)
		{
			log.debug("Auto-recommend: Cleaning up stale collected item {} (not in inventory or GE)", staleId);
			session.removeCollectedItem(staleId);
		}

		return -1;
	}

	/**
	 * Returns itemId if sellable now, else -1. An entry is sellable only when the
	 * item is actually present in inventory. Active or uncollected buys fall through
	 * to promptCollection. Anything else is stale — collectedQuantity alone does not
	 * keep an entry alive (that fallback was the root cause of stuck sell prompts).
	 */
	private int evaluateCollectedItem(int itemId, List<Integer> staleItems)
	{
		if (isItemInInventory(itemId))
		{
			return hasSellPrice(itemId) ? itemId : -1;
		}
		if (offerStore.hasLiveBuyOfferForItem(itemId))
		{
			return -1;
		}
		staleItems.add(itemId);
		return -1;
	}

	private boolean isItemInInventory(int itemId)
	{
		try
		{
			return plugin.getInventoryCountForItem(itemId) > 0;
		}
		catch (Exception | AssertionError e)
		{
			return false; // Not on client thread — caller handles missing inventory gracefully
		}
	}

	private boolean hasSellPrice(int itemId)
	{
		Integer price = resolveBestSellPrice(itemId);
		return price != null && price > 0;
	}

	/**
	 * Resolve the sell quantity for an item, with inventory fallback.
	 * Tries session collected quantity first, then actual inventory count.
	 * Auto-corrects the session if inventory has items but session doesn't.
	 */
	private int resolveSellQuantity(int itemId)
	{
		// Inventory is the source of truth for sell quantity
		int inventoryCount = plugin.getInventoryCountForItem(itemId);
		if (inventoryCount > 0)
		{
			return inventoryCount;
		}

		// Fallback: session tracked quantity (items may not be in inventory yet)
		PlayerSession session = plugin.getSession();
		int qty = session.getCollectedQuantity(itemId);
		if (qty > 0)
		{
			return qty;
		}

		// Last resort: check recommendation quantity
		FlipRecommendation rec = findRecommendationForItem(itemId);
		return rec != null ? rec.getRecommendedQuantity() : 0;
	}

	/**
	 * Find a recommendation matching the given item ID from the queue.
	 */
	private FlipRecommendation findRecommendationForItem(int itemId)
	{
		return queue.findRecommendationForItem(itemId);
	}

	/**
	 * Resolve the best sell price for an item. Session is the single source of truth:
	 * it carries the most recent decision (initial smart-sell, or a manual/auto sell
	 * adjustment) and is what the Flip Assist prompt reads. The panel's displayed
	 * price is only a fallback for items the session hasn't seen yet.
	 */
	private Integer resolveBestSellPrice(int itemId)
	{
		Integer sessionPrice = plugin.getSession().getRecommendedPrice(itemId);
		if (sessionPrice != null && sessionPrice > 0)
		{
			return sessionPrice;
		}
		IntFunction<Integer> provider = displayedSellPriceProvider;
		if (provider != null)
		{
			Integer smartPrice = provider.apply(itemId);
			if (smartPrice != null && smartPrice > 0)
			{
				return smartPrice;
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
		// Show stale offer prompts before falling through to "Monitoring"
		if (!staleOffers.isEmpty())
		{
			focusNextStaleOffer();
			return;
		}

		// Clear the buy overlay so it doesn't show a buy instruction when slots are full
		invokeFocusCallback(null);

		List<OfferRecord> completed = offerStore.completedAwaitingCollection();
		if (!completed.isEmpty() && plugin.hasCollectableGEOffers())
		{
			OfferRecord first = completed.get(0);
			if (first.isBuy())
			{
				updateStatus("Auto: Collect " + first.getItemName() + " from GE");
				invokeOverlayMessageCallback("Collect " + first.getItemName(), first.getItemId());
			}
			else
			{
				updateStatus("Auto: Collect profit from GE");
				invokeOverlayMessageCallback("Collect profit from GE");
			}
		}
		else if (adjustments.hasBuyDeadlines() || adjustments.hasSellStates())
		{
			// Offers are being monitored for staleness — don't say "Waiting for flips"
			updateStatus("Auto: Monitoring active offers");
			invokeOverlayMessageCallback("Monitoring active offers");
		}
		else
		{
			if (!plugin.isPremium() && !hasAvailableGESlots())
			{
				updateStatus("Auto: Waiting for flips");
				invokeOverlayMessageCallback(MSG_WAITING_FOR_FLIPS + "\nUpgrade to Premium for more slots");
			}
			else
			{
				updateStatus("Auto: Waiting for flips");
				invokeOverlayMessageCallback(MSG_WAITING_FOR_FLIPS);
			}
		}
	}

	// =====================
	// Focus Helpers
	// =====================

	/**
	 * Create a buy FocusedFlip, invoke the focus callback, and update status text.
	 * Centralizes the repeated pattern of showing a buy overlay with a status message.
	 */
	private void focusBuyOverlay(int itemId, String itemName, int buyPrice, int quantity, int sellPrice, String statusMsg)
	{
		int priceOffset = config.priceOffset();
		FocusedFlip focus = FocusedFlip.forBuy(itemId, itemName, buyPrice, quantity, sellPrice, priceOffset);
		invokeFocusCallback(focus);
		updateStatus(statusMsg);
	}

	// =====================
	// Callbacks
	// =====================

	private void invokeFocusCallback(FocusedFlip focus)
	{
		Integer locked = queue.getLockedItemId();
		if (locked != null && (focus == null || focus.getItemId() != locked))
		{
			log.debug("Auto-recommend: focus change blocked by offer-screen lock on item {}", locked);
			return;
		}

		Consumer<FocusedFlip> callback = onFocusChanged;
		if (callback != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> callback.accept(focus));
		}
	}

	/** Lock auto-mode focus to the given item until the offer setup screen closes. Idempotent. */
	public void acquireOfferLock(int itemId)
	{
		if (itemId <= 0)
		{
			return;
		}
		Integer prior = queue.getLockedItemId();
		if (prior != null && prior == itemId)
		{
			return;
		}
		queue.setLockedItemId(itemId);
		log.debug("Auto-recommend: offer-screen lock acquired for item {}", itemId);
	}

	/** Clear the offer-screen lock. No-op if not held. */
	public void releaseOfferLock()
	{
		Integer prior = queue.getLockedItemId();
		if (prior == null)
		{
			return;
		}
		queue.setLockedItemId(null);
		log.debug("Auto-recommend: offer-screen lock released (was item {})", prior);
	}

	public Integer getLockedItemId()
	{
		return queue.getLockedItemId();
	}

	/** Re-run the next-action router after the lock releases — focus updates that ran while locked were dropped. */
	public synchronized void refreshFocusAfterUnlock()
	{
		if (!active)
		{
			return;
		}
		focusNextAvailableAction();
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
		lastOverlayMessage = message;
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
		return queue.getCurrentRecommendation();
	}

	public synchronized int getCurrentIndex()
	{
		return queue.getCurrentIndex();
	}

	public synchronized int getQueueSize()
	{
		return queue.size();
	}

	private boolean hasAvailableGESlots()
	{
		return plugin.getFilledGESlotCount() < plugin.getFlipSlotLimit();
	}
}
