package com.flipsmart;
import com.flipsmart.api.dto.FlipAdjustmentRequest;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.api.dto.FlipAdjustmentResponse;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.recommend.ActionDecision;
import com.flipsmart.recommend.ActionResolver;
import com.flipsmart.recommend.AdjustmentService;
import com.flipsmart.recommend.AdjustmentService.SellAdjustmentState;
import com.flipsmart.recommend.CollectOrigin;
import com.flipsmart.recommend.CollectedItem;
import com.flipsmart.recommend.RecommendationQueue;
import com.flipsmart.recommend.ActionStep;
import com.flipsmart.recommend.ResolverInput;
import com.flipsmart.recommend.SkipCooldownTracker;
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

	static boolean localBuyStaleDetectionEnabled(FlipSmartConfig.FlipTimeframe timeframe)
	{
		// 12h buys hold until the backend's 8h exit timer; the short local inactivity
		// threshold would queue a "consider cancelling" prompt far too early.
		return timeframe != FlipSmartConfig.FlipTimeframe.TWELVE_HOURS;
	}

	private static final long TWELVE_H_SELL_CHECK_DELAY_MS = 30 * 60 * 1000L;

	static long sellInitialCheckDelayMs(FlipSmartConfig.FlipTimeframe timeframe)
	{
		// 12h overnight sells re-check on the backend's 30-min cadence; a fresh 5-min timer
		// re-armed on every relist would re-prompt far too often for an overnight flip.
		if (timeframe == FlipSmartConfig.FlipTimeframe.TWELVE_HOURS)
		{
			return TWELVE_H_SELL_CHECK_DELAY_MS;
		}
		return AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS;
	}

	static long loginCheckDeadlineMs(long offerAgeMs, long now)
	{
		// An offer already past the initial check delay is re-checked immediately on login
		// (now-1); otherwise it waits out the remaining delay. This surfaces a 12h buy's 8h
		// exit right after login when the timer elapsed while logged off.
		return offerAgeMs >= AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS
			? now - 1
			: now + (AdjustmentTimerUtils.INITIAL_CHECK_DELAY_MS - offerAgeMs);
	}

	static boolean competitivenessGateApplies(FlipSmartConfig.FlipTimeframe timeframe)
	{
		// For 12h the backend is authoritative (rung readjusts / 8h exit); the local
		// green/red-border competitiveness check must not suppress those prompts.
		return timeframe != FlipSmartConfig.FlipTimeframe.TWELVE_HOURS;
	}
	/** Maximum age of persisted state before it's considered stale (30 minutes) */
	static final long MAX_PERSISTED_AGE_MS = 30 * 60 * 1000L;
	private static final String MSG_WAITING_FOR_FLIPS = "Waiting for flips";
	private static final String MSG_SELL_FORMAT = "Auto: Sell %s @ %s";
	private static final String MSG_BUY_FORMAT = "Auto: Buy %s @ %s";

	// Coins icon for the sell-side "Collect profit" prompt, so every collect prompt
	// carries an icon (buy collects show the item; sell collects show coins).
	private static final int COINS_ITEM_ID = 995;

	/**
	 * How long after collecting a buy the resolver keeps holding the free slot for the
	 * item's sell while its sell price resolves (covers a transient wiki-price timeout).
	 * Sized to comfortably cover realistic resolution latency — an in-field incident took
	 * ~47s (a wiki-price timeout on a freshly collected item) and the OSRS wiki /latest is
	 * ~60s CDN-cached, so a too-short window would let the wrong-buy reappear. Past this
	 * window auto resumes normal buys so a permanently-stuck price can't wedge trading —
	 * the held item stays sellable and re-surfaces as a LIST the moment its price lands.
	 */
	private static final long PENDING_SELL_PRICE_GRACE_MS = 90_000L;


	private final FlipSmartConfig config;
	private final FlipSmartPlugin plugin;
	private final OfferStore offerStore;

	// Buy queue + cursor + item names + offer-screen lock + last-refresh timestamp.
	private final RecommendationQueue queue = new RecommendationQueue();

	// Stale-offer queue + resell prices/net + prompted set.
	private final StaleOfferQueue staleOffers = new StaleOfferQueue();

	// Buy/sell adjustment timers + buy-price cost basis.
	private final AdjustmentService adjustments = new AdjustmentService();

	private final ActionResolver actionResolver = new ActionResolver();

	// 5-minute per-item skip window: a skipped buy/action is held out of auto-surfacing
	// so it does not immediately re-appear. Cleared on auto-mode toggle, survives refreshes.
	private final SkipCooldownTracker skipCooldown = new SkipCooldownTracker();

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

	// Clears all transient slot highlights and lights the live slot for the given
	// item. Used by both the stale re-sell prompt and the collect prompt so the
	// bright box always tracks the currently-prompted item.
	private volatile java.util.function.IntConsumer onHighlightItemSlot;

	// Clears all GE slot adjustment highlights when the stale-offer queue drains,
	// so a slot highlight never lingers without a matching prompt.
	private volatile Runnable onClearAllHighlights;

	// Sticky highlight callbacks (keyed by GE slot): keep a skipped-but-cooling action's
	// orange box lit across focus changes, and drop it when the cooldown ends or the offer
	// is acted on.
	private volatile java.util.function.IntConsumer onStickyHighlight;
	private volatile java.util.function.IntConsumer onClearStickyHighlight;

	// Full highlight reset (transient + sticky) used when auto-mode stops.
	private volatile Runnable onResetAllHighlights;

	// Items whose orange box is kept sticky because their action was skipped and is still
	// cooling. Maps itemId -> the GE slot recorded at skip time, so the sticky box can be
	// cleared by slot even after the offer has left the GE (the item lookup would then fail).
	private final Map<Integer, Integer> stickyHighlightSlots = new java.util.concurrent.ConcurrentHashMap<>();

	// Items whose surfaced resell price is a margin-decay EXIT (#918 AC2) rather than a
	// buy reprice, so the prompt reads "Cancel & re-sell" instead of "Adjust buy".
	private final java.util.Set<Integer> advisorExitResellItems = java.util.concurrent.ConcurrentHashMap.newKeySet();

	// Items whose stale-queue prompt originates from the Active-mode advisor (SURFACE_PRICE
	// reprices and margin-decay exits). These are backend-authoritative and must bypass the local
	// wiki competitiveness prune that governs legacy stale-offer prompts — otherwise an advisor
	// reprice/exit on an offer the local check still reads as "competitive" (green) is dropped
	// before it can surface.
	private final java.util.Set<Integer> advisorSurfacedItems = java.util.concurrent.ConcurrentHashMap.newKeySet();

	// Provider for the panel's displayed (smart) sell price — preferred over session's stored price
	private volatile IntFunction<Integer> displayedSellPriceProvider;

	// Last overlay message sent — readable by the overlay as a fallback when the
	// async callback result gets lost due to race conditions
	private volatile String lastOverlayMessage;

	// Currently-focused collected item sell prompt — used by skip() to remove stuck items
	private volatile int focusedCollectedItemId = -1;

	// Last decision applied by resolveAndApply / the game-tick re-resolve. Drives change
	// detection so the per-tick re-resolve only repaints (and logs) when the action changes.
	private ActionDecision lastAppliedDecision;

	// Whether a FocusedFlip (buy/sell setup overlay) is currently shown. The game-tick
	// re-resolve heals BLANK overlays only — it must never override an action already shown,
	// so it stays out of the way of a direct focus (e.g. collect -> sell) that the resolver's
	// slower view would otherwise replace with an empty-slot buy.
	private volatile boolean overlayFocusShown;

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

	public void setOnHighlightItemSlot(java.util.function.IntConsumer callback)
	{
		this.onHighlightItemSlot = callback;
	}

	public void setOnClearAllHighlights(Runnable callback)
	{
		this.onClearAllHighlights = callback;
	}

	public void setOnStickyHighlight(java.util.function.IntConsumer callback)
	{
		this.onStickyHighlight = callback;
	}

	public void setOnClearStickyHighlight(java.util.function.IntConsumer callback)
	{
		this.onClearStickyHighlight = callback;
	}

	public void setOnResetAllHighlights(Runnable callback)
	{
		this.onResetAllHighlights = callback;
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

		queue.clear();
		session.clearStaleNotifications();
		queue.setCurrentIndex(0);

		// Apply the same profit/volume/active filter Flip Finder's list uses, so the
		// Assist queue is a subset of what Flip Finder displays. Already sorted
		// slowest-filling first.
		queue.addAll(filterAndSortRecommendations(recommendations));

		if (queue.isEmpty())
		{
			updateStatus("Auto: All recommendations already in GE");
			return;
		}

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
		// AC5/AC6: toggling auto-mode off clears every skip cooldown and its sticky box.
		skipCooldown.clearAll();
		stickyHighlightSlots.clear();
		advisorExitResellItems.clear();
		advisorSurfacedItems.clear();
		Runnable resetHighlights = onResetAllHighlights;
		if (resetHighlights != null)
		{
			resetHighlights.run();
		}
		PlayerSession session = plugin.getSession();
		if (session != null)
		{
			session.clearStaleNotifications();
		}
		queue.setCurrentIndex(0);
		focusedCollectedItemId = -1;
		lastAppliedDecision = null;
		overlayFocusShown = false;

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
		resolveActedItem(itemId);
		if (!active)
		{
			return;
		}

		FlipRecommendation current = getCurrentRecommendation();
		if (current == null || current.getItemId() != itemId)
		{
			// Non-focused buy: store sell price from queue.
			FlipRecommendation rec = findRecommendationForItem(itemId);
			if (rec != null && rec.getRecommendedSellPrice() > 0)
			{
				plugin.setRecommendedSellPrice(itemId, rec.getRecommendedSellPrice());
				adjustments.putBuyPrice(itemId, rec.getRecommendedBuyPrice());
				scheduleAdjustmentTimer(itemId, rec.getRecommendedBuyPrice());
				captureOriginalMargin(itemId, rec);
				log.debug("Auto-recommend: Non-focused buy for item {} - stored sell price {} from queue",
					itemId, rec.getRecommendedSellPrice());
			}
		}
		else
		{
			plugin.setRecommendedSellPrice(itemId, current.getRecommendedSellPrice());
			adjustments.putBuyPrice(itemId, current.getRecommendedBuyPrice());
			scheduleAdjustmentTimer(itemId, current.getRecommendedBuyPrice());
			captureOriginalMargin(itemId, current);
			log.debug("Auto-recommend: Buy order placed for {} - re-resolving", current.getItemName());
		}

		// Route through the resolver so a still-open slot surfaces the next buy deterministically
		// (full-queue scan), instead of the cursor-only advanceToNext that could miss a buyable
		// item earlier in the queue and fall to "monitoring" until the user pressed Skip.
		focusNextAvailableAction(true, itemId);
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

		// Don't re-show sell overlay if this item already has an active sell order — unless the
		// advisor has a pending reprice, in which case surface the new price on the setup screen
		// immediately instead of blanking until the old offer clears.
		Integer resellPrice = staleOffers.getResellPrice(itemId);
		boolean repricePending = resellPrice != null && resellPrice > 0;
		if (offerStore.hasActiveSellOfferForItem(itemId) && !repricePending)
		{
			// When the player has the modify/setup screen open for this active sell (offer lock
			// held for it), paint the already-known recommended price instantly instead of
			// returning blank — the price lives in session state, so there's no need to wait for
			// a later event/tick. Off-screen we still bail, to avoid re-surfacing a listed sell.
			boolean setupScreenOpen = java.util.Objects.equals(queue.getLockedItemId(), itemId);
			Integer knownPrice = resolveBestSellPrice(itemId);
			if (setupScreenOpen && knownPrice != null && knownPrice > 0)
			{
				int qty = resolveRepriceQuantity(itemId);
				FocusedFlip focus = FocusedFlip.forSell(itemId, itemName, knownPrice, qty, config.priceOffset());
				invokeFocusCallback(focus);
				updateStatus(String.format(MSG_SELL_FORMAT, itemName, GpUtils.formatGPWithSuffix(knownPrice)));
				return SellFocusResult.FOCUSED;
			}
			log.debug("Auto-recommend: Sell already active for {} - ignoring override", itemName);
			return SellFocusResult.ALREADY_SELLING;
		}

		if (repricePending)
		{
			int repriceQty = resolveRepriceQuantity(itemId);
			// resellPrice is the advisor's backend price, already jittered (#918 AC6) —
			// do NOT re-apply the plugin priceOffset or it would double-adjust.
			FocusedFlip focus = FocusedFlip.forSell(itemId, itemName, resellPrice, repriceQty);
			invokeFocusCallback(focus);
			updateStatus(String.format(MSG_SELL_FORMAT, itemName, GpUtils.formatGPWithSuffix(resellPrice)));
			return SellFocusResult.FOCUSED;
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

		// Re-listing a skipped/uncompetitive sell resolves its maintenance action (AC8 path).
		resolveActedItem(itemId);

		PlayerSession session = plugin.getSession();
		if (session != null)
		{
			session.removeCollectedItem(itemId);
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
		resolveActedItem(itemId);
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

		session.addCollectedItem(itemId, filledQuantity, CollectOrigin.PARTIAL_CANCEL, System.currentTimeMillis());
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
		resolveActedItem(itemId);
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

			// Modify/cancel of a sell returns the items to inventory (re-added to collected by
			// handleCollectedSellOffer). The player is mid-flip on that item, so re-list it —
			// directly, mirroring handleBuyCollected — instead of advancing to a new buy for the
			// freed slot (where the resolver's S2 empty-slot buy would outrank the S3 re-list).
			PlayerSession session = plugin.getSession();
			Integer sellPrice = session != null ? session.getRecommendedPrice(itemId) : null;
			if (session != null && session.getCollectedItemIds().contains(itemId) && sellPrice != null && sellPrice > 0)
			{
				log.debug("Auto-recommend: Sell modified/returned for {} - re-listing", itemName);
				focusSellForItem(itemId, itemName, quantity);
				return;
			}

			log.debug("Auto-recommend: Sell collected for {} - advancing", itemName);
			// Fully sold (nothing returned) — rewind so a new buy surfaces.
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
		// Collecting must not downgrade a partial-cancel (S1 list) to a completed-buy (S3),
		// or the resolver lets an empty-slot buy (S2) outrank listing the held items.
		CollectOrigin priorOrigin = session.getCollectOrigin(itemId);
		CollectOrigin origin = priorOrigin == CollectOrigin.PARTIAL_CANCEL
			? CollectOrigin.PARTIAL_CANCEL
			: CollectOrigin.COMPLETED_BUY;
		session.addCollectedItem(itemId, quantity, origin, System.currentTimeMillis());
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
		if (!plugin.isClientThread())
		{
			plugin.runOnClientThread(() -> focusNextAvailableAction(rewindToFirstAvailableBuy, excludeItemId));
			return;
		}
		resolveAndApply(excludeItemId);
	}

	ActionDecision resolveAndApply(int excludeItemId)
	{
		// Retire any sticky boxes whose cooldown lapsed or whose offer is gone before we
		// decide what to surface (cheap no-op when nothing is sticky).
		reconcileStickyHighlights();
		ResolverInput input = buildResolverInput(excludeItemId);
		ActionDecision decision = actionResolver.resolve(input);
		log.debug("Auto-recommend: resolver decision {}/{} item={} slot={}",
			decision.getKind(), decision.getStep(), decision.getItemId(), decision.getSlot());
		focusedCollectedItemId = decision.getStep() == ActionStep.LIST ? decision.getItemId() : -1;
		lastAppliedDecision = decision;
		applyDecision(decision);
		// When the only thing we could have done was a buy we suppressed for a pending sell,
		// tell the player we're holding for that item's price rather than showing a vague wait.
		// Guarded by staleOffers.isEmpty() so we never clobber a re-sell prompt that
		// applyDecision(IDLE)->promptCollection surfaces for a cooling-down stale offer (which
		// the resolver excludes from its input but promptCollection still shows).
		if (decision.getStep() == ActionStep.NONE && input.isBlockBuyForPendingSell()
			&& staleOffers.isEmpty())
		{
			surfacePendingSellStatus(input.getPendingSellItemId());
		}
		return decision;
	}

	/** Overlay message shown while auto holds a slot for a collected item awaiting its sell price. */
	private void surfacePendingSellStatus(int itemId)
	{
		if (itemId <= 0)
		{
			return;
		}
		String name = resolveItemName(itemId);
		updateStatus("Auto: Preparing to sell " + name);
		invokeOverlayMessageCallback("Preparing to sell " + name, itemId);
	}

	/**
	 * Per-game-tick re-resolve. Auto-mode selection is otherwise only re-run on discrete GE
	 * offer events (~8-15s apart) plus a 2-minute refresh, so a transient blank/IDLE produced
	 * when game state hadn't settled at event time would linger until the next event. Running
	 * the (cheap, deterministic) resolver each tick heals those gaps within ~1s. Deduped against
	 * the last applied decision so it repaints/logs only when the action actually changes, and
	 * skipped while the offer-setup screen lock is held so it never fights the setup autofill.
	 */
	public synchronized void onGameTickReresolve()
	{
		if (!active || !plugin.isClientThread() || queue.getLockedItemId() != null)
		{
			return;
		}
		// Heal blanks only: never override an action already shown on the overlay. A direct
		// focus (e.g. collect -> sell) sets a FocusedFlip the resolver's slower view would
		// replace with an empty-slot buy (S2 outranks S3); the tick must not fight it.
		if (overlayFocusShown)
		{
			return;
		}
		ResolverInput input = buildResolverInput(-1, false);
		ActionDecision decision = actionResolver.resolve(input);
		if (decision.equals(lastAppliedDecision))
		{
			return;
		}
		log.debug("Auto-recommend: tick re-resolve {}/{} item={} slot={}",
			decision.getKind(), decision.getStep(), decision.getItemId(), decision.getSlot());
		focusedCollectedItemId = decision.getStep() == ActionStep.LIST ? decision.getItemId() : -1;
		lastAppliedDecision = decision;
		applyDecision(decision);
		// Mirror resolveAndApply: when a buy was suppressed for a pending sell, hold with a
		// clear message instead of the generic wait (keeps both resolve paths consistent).
		// Same staleOffers guard so a cooling-down re-sell prompt is never clobbered.
		if (decision.getStep() == ActionStep.NONE && input.isBlockBuyForPendingSell()
			&& staleOffers.isEmpty())
		{
			surfacePendingSellStatus(input.getPendingSellItemId());
		}
	}

	private void applyDecision(ActionDecision decision)
	{
		switch (decision.getStep())
		{
			case PLACE_BUY:
				focusBuyForItem(decision.getItemId());
				break;
			case LIST:
				applyListDecision(decision.getItemId());
				break;
			case CANCEL:
			case REPRICE:
				focusStaleOfferForItem(decision.getItemId());
				break;
			case COLLECT:
				promptCollectionForItem(decision.getItemId());
				break;
			case NONE:
			default:
				promptCollection();
				break;
		}
	}

	private void focusBuyForItem(int itemId)
	{
		List<FlipRecommendation> view = queue.view();
		for (int i = 0; i < view.size(); i++)
		{
			if (view.get(i).getItemId() == itemId)
			{
				queue.setCurrentIndex(i);
				break;
			}
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

	private void applyListDecision(int itemId)
	{
		PlayerSession session = plugin.getSession();
		if (session == null)
		{
			promptCollection();
			return;
		}
		String name = resolveItemName(itemId);
		int qty = session.getCollectedQuantity(itemId);
		focusSellForItem(itemId, name, qty);
	}

	private String resolveItemName(int itemId)
	{
		return queue.getItemName(itemId, plugin.getItemName(itemId));
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
		return firstAvailableBuyIndex(queue, activeItemIds, excludeItemId, priceOffset, minProfit, id -> false);
	}

	/**
	 * As above, but also skips items inside their post-skip cooldown window so a skipped
	 * buy does not immediately re-appear (AC1/AC2) — including when the queue was just
	 * refreshed from the backend (AC4).
	 */
	static int firstAvailableBuyIndex(
		List<FlipRecommendation> queue,
		Set<Integer> activeItemIds,
		int excludeItemId,
		int priceOffset,
		int minProfit,
		java.util.function.IntPredicate isCoolingDown)
	{
		for (int i = 0; i < queue.size(); i++)
		{
			FlipRecommendation rec = queue.get(i);
			if (rec.getItemId() != excludeItemId
				&& !activeItemIds.contains(rec.getItemId())
				&& !isCoolingDown.test(rec.getItemId())
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
		for (FlipRecommendation rec : newRecommendations)
		{
			queue.putItemName(rec.getItemId(), rec.getItemName());
		}
		return filterSurfaceable(newRecommendations, plugin.getActiveFlipItemIds(),
			config.priceOffset(), config.minimumProfit(), config.minimumVolume());
	}

	/**
	 * The pool a player may be shown in Flip Assist: recommendations that are not
	 * already on the GE and that clear the same minimum-profit and minimum-volume
	 * filters Flip Finder's list applies ({@link FocusedFlip#passesRecommendationFilters}),
	 * sorted slowest-filling first. Shared by both start() and refreshQueue() so the
	 * Assist queue never surfaces an item Flip Finder would hide.
	 */
	static List<FlipRecommendation> filterSurfaceable(
		List<FlipRecommendation> recommendations,
		Set<Integer> activeItemIds,
		int priceOffset,
		int minProfit,
		int minVolume)
	{
		List<FlipRecommendation> filtered = new ArrayList<>();
		for (FlipRecommendation rec : recommendations)
		{
			if (!activeItemIds.contains(rec.getItemId())
				&& FocusedFlip.passesRecommendationFilters(rec, priceOffset, minProfit, minVolume))
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
		if (!localBuyStaleDetectionEnabled(config.flipTimeframe()))
		{
			return null;
		}
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

		// Only prompt if the offer is actually uncompetitive (red border) — except for 12h,
		// where the backend's exit/readjust decision is authoritative and must surface.
		if (competitivenessGateApplies(config.flipTimeframe()))
		{
			FlipSmartPlugin.OfferCompetitiveness comp = plugin.calculateCompetitiveness(offer);
			if (comp != FlipSmartPlugin.OfferCompetitiveness.UNCOMPETITIVE)
			{
				log.debug("Auto-recommend: API suggests action for {} but offer is still competitive — rescheduling",
					offer.getItemName());
				adjustments.putBuyDeadline(itemId, System.currentTimeMillis() + nextDelay);
				return;
			}
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
		long deadline = loginCheckDeadlineMs(offerAgeMs, now);
		adjustments.putBuyDeadline(offer.getItemId(), deadline);
		log.debug("Auto-recommend: Scheduled missing buy timer for {} (age={}m)",
			offer.getItemName(), offerAgeMs / 60000);
	}

	private void scheduleMissingSellTimer(OfferRecord offer, long now)
	{
		Integer buyPrice = adjustments.getBuyPriceOrDefault(offer.getItemId(), offer.getPrice());
		long offerAgeMs = now - offer.getEffectiveLastActivityAtMillis();
		long deadline = loginCheckDeadlineMs(offerAgeMs, now);
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
			staleOffers.pruneIrrelevant(o -> staleOfferNoLongerRelevant(o, currentOffers));
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
		if (next == null)
		{
			// The queue drained between the isEmpty() check above and here; nothing to prompt.
			focusNextAvailableAction();
			return;
		}
		renderStaleOfferPrompt(next);
	}

	private void renderStaleOfferPrompt(OfferRecord offer)
	{
		Integer resellPrice = staleOffers.getResellPrice(offer.getItemId());
		String overlayMsg;
		if (resellPrice != null)
		{
			Integer net = staleOffers.getResellNet(offer.getItemId());
			String netSuffix = net == null ? ""
				: String.format(" (%s%s)", net >= 0 ? "+" : "-", GpUtils.formatGP(Math.abs(net)));
			// AC2 exit → cancel & re-sell; a priced buy prompt is the competitive buy
			// reprice (move_price_up); a priced sell prompt is a re-sell/exit at the advised price.
			String verb;
			if (advisorExitResellItems.contains(offer.getItemId()))
			{
				verb = "Cancel & re-sell";
			}
			else if (offer.isBuy())
			{
				verb = "Adjust buy";
			}
			else
			{
				verb = "Re-sell";
			}
			overlayMsg = String.format("%s %s at:\n%s gp%s", verb, offer.getItemName(),
				String.format("%,d", resellPrice), netSuffix);
		}
		else
		{
			overlayMsg = String.format("Consider cancelling:\n%s", offer.getItemName());
		}

		updateStatus("Auto: " + overlayMsg);
		invokeFocusCallback(null);
		invokeOverlayMessageCallback(overlayMsg, offer.getItemId());
		highlightItemSlot(offer.getItemId());
	}

	/**
	 * Clear transient slot highlights and light the live GE slot for {@code itemId}
	 * so the bright box matches the currently-prompted item (the wired callback,
	 * {@code highlightSlotForItem}, clears then sets). Sticky skip-reminder boxes on
	 * other slots are preserved (and rendered dimmed) by the overlay.
	 */
	private void highlightItemSlot(int itemId)
	{
		java.util.function.IntConsumer cb = onHighlightItemSlot;
		if (cb != null)
		{
			cb.accept(itemId);
		}
	}

	/**
	 * Drop all transient slot highlights when a no-focus prompt has no slot of its
	 * own to point at (monitoring / waiting), so a bright box from a prior focus
	 * doesn't linger under it. Sticky skip-reminder boxes are preserved.
	 */
	private void clearTransientHighlights()
	{
		Runnable cb = onClearAllHighlights;
		if (cb != null)
		{
			cb.run();
		}
	}

	/**
	 * Whether a queued stale/advisor offer should be pruned from the stale queue. Prunes when the
	 * offer has left the GE (sold / collected / cancelled). Otherwise an advisor-surfaced prompt
	 * (a SURFACE_PRICE reprice or margin-decay exit) is backend-authoritative and is kept
	 * regardless of the local wiki competitiveness check — only legacy local-detection prompts are
	 * subject to the green/red gate, which drops an offer that has drifted back to competitive.
	 * Without the advisor bypass, a reprice/exit on an offer the local check still reads as
	 * "competitive" (green) is pruned before it can surface.
	 */
	private boolean staleOfferNoLongerRelevant(OfferRecord queued, List<OfferRecord> currentOffers)
	{
		OfferRecord current = currentOffers.stream()
			.filter(t -> t.getItemId() == queued.getItemId() && t.getState() != OfferState.FILLED)
			.findFirst().orElse(null);
		if (current == null)
		{
			return true; // No longer in GE
		}
		// Advisor prompts are backend-authoritative — never dropped for local competitiveness.
		if (advisorSurfacedItems.contains(queued.getItemId()))
		{
			return false;
		}
		// 12h ladder prompts are backend-authoritative too — keep them regardless of competitiveness.
		if (!competitivenessGateApplies(config.flipTimeframe()))
		{
			return false;
		}
		// Re-check competitiveness — wiki prices may have refreshed.
		FlipSmartPlugin.OfferCompetitiveness comp = plugin.calculateCompetitiveness(current);
		return comp != FlipSmartPlugin.OfferCompetitiveness.UNCOMPETITIVE;
	}

	private void focusStaleOfferForItem(int itemId)
	{
		PlayerSession session = plugin.getSession();
		if (session != null)
		{
			List<OfferRecord> currentOffers = offerStore.liveOffers();
			staleOffers.pruneIrrelevant(o -> staleOfferNoLongerRelevant(o, currentOffers));
		}

		if (staleOffers.isEmpty())
		{
			if (onClearAllHighlights != null)
			{
				onClearAllHighlights.run();
			}
			focusNextAvailableAction();
			return;
		}

		OfferRecord target = staleOffers.findByItemId(itemId);
		if (target == null)
		{
			focusNextStaleOffer();
			return;
		}

		renderStaleOfferPrompt(target);
	}

	/**
	 * The player placed/cancelled/collected an offer for this item, so any skip cooldown and
	 * sticky box for it are now moot — drop them immediately rather than waiting for expiry.
	 */
	private void resolveActedItem(int itemId)
	{
		skipCooldown.clear(itemId);
		dropStickyHighlight(itemId);
		advisorExitResellItems.remove(itemId);
		advisorSurfacedItems.remove(itemId);
	}

	/** GE slot currently holding a live offer for the item, or null if none. */
	private Integer slotForLiveItem(int itemId)
	{
		for (OfferRecord o : offerStore.liveOffers())
		{
			if (o.getItemId() == itemId && o.getSlot() != null)
			{
				return o.getSlot();
			}
		}
		return null;
	}

	/** Track a skipped action's item and light its sticky orange box (AC7). */
	private void keepStickyHighlight(int itemId)
	{
		Integer slot = slotForLiveItem(itemId);
		if (slot == null)
		{
			return;
		}
		stickyHighlightSlots.put(itemId, slot);
		java.util.function.IntConsumer cb = onStickyHighlight;
		if (cb != null)
		{
			cb.accept(slot);
		}
	}

	/** Stop tracking a sticky item and drop its orange box by the slot recorded at skip time. */
	private void dropStickyHighlight(int itemId)
	{
		Integer slot = stickyHighlightSlots.remove(itemId);
		if (slot != null)
		{
			java.util.function.IntConsumer cb = onClearStickyHighlight;
			if (cb != null)
			{
				cb.accept(slot);
			}
		}
	}

	/**
	 * Drop sticky boxes whose reason has passed: the cooldown expired, or the offer is no
	 * longer a live, unfilled GE offer (acted on / collected / cancelled). An expired
	 * still-uncompetitive offer is re-surfaced by the normal adjustment-timer path once its
	 * cooldown lifts, which restores a live (transient) highlight.
	 */
	private void reconcileStickyHighlights()
	{
		if (stickyHighlightSlots.isEmpty())
		{
			return;
		}
		List<OfferRecord> live = offerStore.liveOffers();
		for (Integer itemId : new java.util.ArrayList<>(stickyHighlightSlots.keySet()))
		{
			boolean cooling = skipCooldown.isCoolingDown(itemId);
			boolean stillLiveOffer = live.stream()
				.anyMatch(o -> o.getItemId() == itemId && o.getState() != OfferState.FILLED);
			if (!cooling || !stillLiveOffer)
			{
				dropStickyHighlight(itemId);
			}
		}
	}

	/**
	 * Surface a bare advisor HANDOFF: the advisor decided to cancel with no reprice — e.g. a
	 * buy with no fills in its window (CANCEL_AND_RELIST_OTHER). Marks the item as advisor-surfaced
	 * so the "consider cancelling" prompt bypasses the local competitiveness prune (the advisor is
	 * authoritative — a no-fill cancel has nothing to do with the local green/red price check), then
	 * queues it.
	 */
	public synchronized void surfaceAdvisorCancel(OfferRecord offer)
	{
		if (offer == null)
		{
			return;
		}
		advisorSurfacedItems.add(offer.getItemId());
		addToStaleQueue(offer);
	}

	/**
	 * Add a tracked offer to the stale queue if not already present.
	 * If the queue was empty, immediately shows the first prompt.
	 */
	void addToStaleQueue(OfferRecord offer)
	{
		if (skipCooldown.isCoolingDown(offer.getItemId()))
		{
			// Snoozed by a recent skip — don't re-prompt until the cooldown lifts.
			return;
		}

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
		advisorExitResellItems.remove(offer.getItemId());
		advisorSurfacedItems.add(offer.getItemId());
		addToStaleQueue(offer);
	}

	/**
	 * Surface a margin-decay exit (#918 AC2): cancel the remaining buy and re-sell the held
	 * units at the advisor's already-jittered price. Stores the price in the stale-price map
	 * (so the eventual sell lists via the no-offset path, honouring AC6) and marks the item as
	 * an exit so the prompt reads "Cancel & re-sell" rather than "Adjust buy". Deliberately does
	 * NOT set the session recommended price — that would flow through the offset-applying focus.
	 */
	public synchronized void surfaceAdvisorExitResell(OfferRecord offer, int resellPrice, Integer netProfitEstimate)
	{
		if (offer == null)
		{
			return;
		}
		staleOffers.putResellPrice(offer.getItemId(), resellPrice);
		if (netProfitEstimate != null)
		{
			staleOffers.putResellNet(offer.getItemId(), netProfitEstimate);
		}
		else
		{
			staleOffers.removeResellNet(offer.getItemId());
		}
		advisorExitResellItems.add(offer.getItemId());
		advisorSurfacedItems.add(offer.getItemId());
		addToStaleQueue(offer);
	}

	/**
	 * Retract a previously-surfaced advisor sell prompt (the advisor changed its mind, e.g.
	 * the market recovered and it now returns WAIT). Refreshes focus to whatever is next.
	 */
	public synchronized void removeAdvisorResell(int itemId)
	{
		advisorExitResellItems.remove(itemId);
		advisorSurfacedItems.remove(itemId);
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

		long delay = sellInitialCheckDelayMs(config.flipTimeframe());
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
		queue.addAll(state.queue);
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
			// Hold the action out of auto-surfacing for the cooldown, but keep its orange
			// box lit (AC7) — the trade still needs maintenance — and its session sell
			// price intact so a manual GE pull-up still shows the re-adjusted price (AC8).
			skipCooldown.skip(skipped.getItemId());
			keepStickyHighlight(skipped.getItemId());
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
			skipCooldown.skip(collectedId);
			session.removeCollectedItem(collectedId);
			focusedCollectedItemId = -1;
			focusNextAvailableAction();
			return;
		}

		log.debug("Auto-recommend: User skipped current recommendation");
		FlipRecommendation skippedBuy = queue.getCurrentRecommendation();
		if (skippedBuy != null)
		{
			skipCooldown.skip(skippedBuy.getItemId());
		}
		advanceToNext();
	}

	private void advanceToNext()
	{
		int priceOffset = config.priceOffset();
		int minProfit = config.minimumProfit();

		queue.skipToNextSurfaceable(
			plugin.getActiveFlipItemIds(),
			next -> FocusedFlip.calculateAdjustedProfit(next, priceOffset),
			minProfit,
			skipCooldown::isCoolingDown);

		if (queue.cursorBeyondEnd())
		{
			if (hasCollectedItemsToSell())
			{
				focusNextCollectedItemSell();
			}
			else
			{
				// Queue exhausted (all listed, or all remaining items are inside their skip
				// cooldown) — pull a fresh, shuffled recommendation list and continue on that.
				// The skip cooldown survives the refresh, so just-skipped items stay suppressed
				// in the new list while new items surface.
				invokeFocusCallback(null);
				updateStatus("Auto: Finding more recommendations");
				invokeOverlayMessageCallback("Finding more recommendations...");

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
				String itemName = resolveItemName(sellableItemId);
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
	int findNextSellableCollectedItem()
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
		Boolean inInventory = inventoryContains(itemId);
		if (inInventory == null)
		{
			return -1; // cannot verify inventory off-thread — do NOT mark stale, do NOT surface this cycle
		}
		if (inInventory)
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

	/** TRUE/FALSE if inventory could be read (client thread); null if undeterminable (off-thread). */
	private Boolean inventoryContains(int itemId)
	{
		try
		{
			return plugin.getInventoryCountForItem(itemId) > 0;
		}
		catch (Exception | AssertionError e)
		{
			return null; // off-thread — cannot determine; caller must NOT treat as absent
		}
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
	 * Quantity for a reprice prompt. During a reprice the items sit in the still-active sell
	 * offer (not inventory), so prefer the offer's unsold remainder over inventory.
	 */
	private int resolveRepriceQuantity(int itemId)
	{
		OfferRecord staleSell = staleOffers.findByItemId(itemId);
		if (staleSell != null)
		{
			int remaining = staleSell.getTotalQuantity() - staleSell.getFilledQuantity();
			if (remaining > 0)
			{
				return remaining;
			}
		}
		return Math.max(1, resolveSellQuantity(itemId));
	}

	/**
	 * Find a recommendation matching the given item ID from the queue.
	 */
	private FlipRecommendation findRecommendationForItem(int itemId)
	{
		return queue.findRecommendationForItem(itemId);
	}

	/** Persist the flip's original per-unit margin at buy placement (#918), so it survives the
	 * recommendation queue cycling. Captured once per placement from the seeding recommendation. */
	private void captureOriginalMargin(int itemId, FlipRecommendation rec)
	{
		if (rec == null || rec.getMargin() <= 0)
		{
			return;
		}
		PlayerSession sess = plugin.getSession();
		if (sess != null)
		{
			sess.setOriginalMargin(itemId, rec.getMargin());
		}
	}

	/**
	 * The flip's original per-unit margin — the fixed baseline the advisor measures decay and the
	 * joint reduction budget against. Sourced from the active offer's captured value (persisted at
	 * placement, survives queue cycling), falling back to the live queue for a freshly-recommended
	 * item not yet placed. Null when unknown (advisor then falls back to its existing behavior).
	 */
	public synchronized Integer getOriginalMargin(int itemId)
	{
		PlayerSession sess = plugin.getSession();
		Integer persisted = sess == null ? null : sess.getOriginalMargin(itemId);
		if (persisted != null)
		{
			return persisted;
		}
		FlipRecommendation rec = findRecommendationForItem(itemId);
		return rec == null ? null : rec.getMargin();
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
	private void promptCollectionForItem(int itemId)
	{
		List<OfferRecord> completed = offerStore.completedAwaitingCollection();
		OfferRecord target = null;
		for (OfferRecord r : completed)
		{
			if (r.getItemId() == itemId)
			{
				target = r;
				break;
			}
		}
		if (target == null || !plugin.hasCollectableGEOffers())
		{
			promptCollection();
			return;
		}
		invokeFocusCallback(null);
		if (target.isBuy())
		{
			updateStatus("Auto: Collect " + target.getItemName() + " from GE");
			invokeOverlayMessageCallback("Collect " + target.getItemName(), target.getItemId());
		}
		else
		{
			updateStatus("Auto: Collect profit from GE");
			invokeOverlayMessageCallback("Collect profit from GE", COINS_ITEM_ID);
		}
		// Light the collect target's own slot so the highlight matches the prompt.
		highlightItemSlot(target.getItemId());
	}

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
				invokeOverlayMessageCallback("Collect profit from GE", COINS_ITEM_ID);
			}
			// Light the collect target's own slot so the highlight matches the prompt.
			highlightItemSlot(first.getItemId());
		}
		else if (adjustments.hasBuyDeadlines() || adjustments.hasSellStates())
		{
			// Monitoring/waiting prompts point at no slot of their own, so drop any
			// bright box left over from a prior focus rather than let it linger.
			clearTransientHighlights();
			// Offers are being monitored for staleness — don't say "Waiting for flips"
			updateStatus("Auto: Monitoring active offers");
			invokeOverlayMessageCallback("Monitoring active offers");
		}
		else
		{
			clearTransientHighlights();
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

		overlayFocusShown = focus != null;
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

	ResolverInput buildResolverInput(int excludeItemId)
	{
		return buildResolverInput(excludeItemId, true);
	}

	ResolverInput buildResolverInput(int excludeItemId, boolean logInput)
	{
		long now = System.currentTimeMillis();
		PlayerSession session = plugin.getSession();

		List<CollectedItem> collected = new ArrayList<>();
		boolean blockBuyForPendingSell = false;
		int pendingSellItemId = -1;
		if (session != null)
		{
			for (Integer itemId : session.getCollectedItemIds())
			{
				if (offerStore.hasActiveSellOfferForItem(itemId))
				{
					continue;
				}
				if (!isItemInInventory(itemId))
				{
					continue;
				}
				Integer resolvedPrice = resolveBestSellPrice(itemId);
				if (resolvedPrice == null || resolvedPrice <= 0)
				{
					// The sell price hasn't resolved yet (e.g. a transient wiki-price timeout).
					// For a short grace window after collection, flag a pending sell so the
					// resolver holds the free slot instead of spending it on a new buy that
					// would strand this just-collected item. Past the window we fall through,
					// so a permanently-unresolved price can never wedge trading.
					if (now - session.getCollectedAtMillis(itemId) < PENDING_SELL_PRICE_GRACE_MS)
					{
						blockBuyForPendingSell = true;
						if (pendingSellItemId < 0)
						{
							pendingSellItemId = itemId;
						}
					}
					continue;
				}
				CollectOrigin origin = session.getCollectOrigin(itemId);
				if (origin == null)
				{
					origin = CollectOrigin.COMPLETED_BUY;
				}
				collected.add(new CollectedItem(itemId, origin, true,
					session.getCollectedAtMillis(itemId)));
			}
		}

		boolean hasSurfaceable = false;
		int surfaceableItemId = -1;
		List<FlipRecommendation> view = queue.view();
		int idx = firstAvailableBuyIndex(view, plugin.getActiveFlipItemIds(),
			excludeItemId, config.priceOffset(), config.minimumProfit(), skipCooldown::isCoolingDown);
		if (idx >= 0 && idx < view.size())
		{
			hasSurfaceable = true;
			surfaceableItemId = view.get(idx).getItemId();
		}

		int filledSlots = plugin.getFilledGESlotCount();
		int slotLimit = plugin.getFlipSlotLimit();
		List<OfferRecord> completed = offerStore.completedAwaitingCollection();
		// Drop any cooling-down stale offers so a skipped reprice/cancel action is not
		// re-surfaced by the resolver until its cooldown lifts (AC6).
		List<OfferRecord> staleSnapshot = staleOffers.snapshot().stream()
			.filter(o -> !skipCooldown.isCoolingDown(o.getItemId()))
			.collect(java.util.stream.Collectors.toList());
		if (logInput)
		{
			log.debug("Auto-recommend: resolver input filled={}/{} surfaceableBuy={} (item={}, idx={}) queue={} active={} collected={} stale={} completed={} pendingSell={}",
				filledSlots, slotLimit, hasSurfaceable, surfaceableItemId, idx,
				view.size(), plugin.getActiveFlipItemIds().size(), collected.size(),
				staleSnapshot.size(), completed.size(), blockBuyForPendingSell ? pendingSellItemId : -1);
		}

		return ResolverInput.builder()
			.slotLimit(slotLimit)
			.filledSlotCount(filledSlots)
			.surfaceableBuy(hasSurfaceable, surfaceableItemId)
			.nowMillis(now)
			.blockBuyForPendingSell(blockBuyForPendingSell, pendingSellItemId)
			.completedAwaitingCollection(completed)
			.staleOffers(staleSnapshot)
			.collectedAwaitingList(collected)
			.build();
	}
}
