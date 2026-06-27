package com.flipsmart;
import com.flipsmart.domain.offer.OfferAction;
import com.flipsmart.api.dto.ActiveFlipsResponse;
import com.flipsmart.api.dto.OfferAdviceResponse;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferTransition;
import com.flipsmart.domain.offer.PendingOrder;
import com.flipsmart.recommend.CollectOrigin;
import com.flipsmart.trading.OfferEventMapper;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.util.ItemUtils;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Handles GE offer state changes: cancelled, collected/empty, and active fills.
 * Also manages flip assist auto-focus when setting up sell offers.
 */
@Slf4j
@Singleton
public class GrandExchangeTracker
{
	private static final int TRANSACTION_REFRESH_DELAY_MS = 500;
	/** Minimum interval between autoFocusOnActiveFlip calls for the same item */
	private static final long AUTO_FOCUS_DEBOUNCE_MS = 2000;

	private final PlayerSession session;
	private final FlipSmartApiClient apiClient;
	private final ActiveFlipTracker activeFlipTracker;
	private final ItemManager itemManager;
	private final TradeActivityLog tradeActivityLog;

	// State owner for offer lifecycle decisions (delta/transition kind). Injected by the
	// plugin; self-provisioned when absent so the tracker is always backed by the new core.
	private OfferStore offerStore = new OfferStore();

	// Set after construction (created in plugin startUp)
	private AutoRecommendService autoRecommendService;
	private ManualAdjustmentTracker manualAdjustmentTracker;
	private ActiveOfferAdvisorService activeOfferAdvisorService;
	private java.util.function.BooleanSupplier adjustmentPromptsEnabled;
	private FlipSmartConfig config;

	// Callbacks wired by the plugin
	private Supplier<Optional<String>> rsnSupplier;
	private Runnable onPanelRefresh;
	private Runnable onActiveFlipsRefresh;
	private Consumer<List<PendingOrder>> onPendingOrdersUpdate;
	private Consumer<FocusedFlip> onFocusChanged;
	private BiConsumer<Integer, Boolean> onFocusClear;
	private IntFunction<Integer> displayedSellPriceProvider;
	private BiConsumer<Integer, Runnable> oneShotScheduler;

	// Debounce: prevent duplicate autoFocusOnActiveFlip calls for the same item
	private volatile int lastAutoFocusItemId;
	private volatile long lastAutoFocusTimeMs;

	// Deferred sell-focus retry: when the sell screen opens before the collect's
	// state has settled, re-attempt focus over the next few game ticks.
	private static final int SELL_FOCUS_RETRY_TICKS = 6;
	private volatile int pendingSellFocusItemId = -1;
	private int pendingSellFocusTicksLeft;

	/**
	 * Bundles GE offer data passed from the plugin event handler.
	 * Constructed by the plugin and passed to {@link #handleOfferChanged(OfferContext)}.
	 */
	@lombok.Builder
	static class OfferContext
	{
		final int slot;
		final int itemId;
		final String itemName;
		final int quantitySold;
		final int totalQuantity;
		final int price;
		final int spent;
		final boolean isBuy;
		final GrandExchangeOfferState state;
	}

	@Inject
	public GrandExchangeTracker(
		PlayerSession session,
		FlipSmartApiClient apiClient,
		ActiveFlipTracker activeFlipTracker,
		ItemManager itemManager,
		TradeActivityLog tradeActivityLog)
	{
		this.session = session;
		this.apiClient = apiClient;
		this.activeFlipTracker = activeFlipTracker;
		this.itemManager = itemManager;
		this.tradeActivityLog = tradeActivityLog;
	}

	// =====================
	// Setter wiring
	// =====================

	public void setOfferStore(OfferStore offerStore)
	{
		if (offerStore != null)
		{
			this.offerStore = offerStore;
		}
	}

	public void setAutoRecommendService(AutoRecommendService service)
	{
		this.autoRecommendService = service;
	}

	public void setManualAdjustmentTracker(ManualAdjustmentTracker tracker)
	{
		this.manualAdjustmentTracker = tracker;
	}

	public void setActiveOfferAdvisorService(ActiveOfferAdvisorService service)
	{
		this.activeOfferAdvisorService = service;
	}

	public void setAdjustmentPromptsEnabled(java.util.function.BooleanSupplier supplier)
	{
		this.adjustmentPromptsEnabled = supplier;
	}

	public void setRsnSupplier(Supplier<Optional<String>> rsnSupplier)
	{
		this.rsnSupplier = rsnSupplier;
	}

	public void setConfig(FlipSmartConfig config)
	{
		this.config = config;
	}

	public void setOnPanelRefresh(Runnable callback)
	{
		this.onPanelRefresh = callback;
	}

	public void setOnActiveFlipsRefresh(Runnable callback)
	{
		this.onActiveFlipsRefresh = callback;
	}

	public void setOnPendingOrdersUpdate(Consumer<List<PendingOrder>> callback)
	{
		this.onPendingOrdersUpdate = callback;
	}

	public void setOnFocusChanged(Consumer<FocusedFlip> callback)
	{
		this.onFocusChanged = callback;
	}

	public void setOnFocusClear(BiConsumer<Integer, Boolean> callback)
	{
		this.onFocusClear = callback;
	}

	public void setDisplayedSellPriceProvider(IntFunction<Integer> provider)
	{
		this.displayedSellPriceProvider = provider;
	}

	public void setOneShotScheduler(BiConsumer<Integer, Runnable> scheduler)
	{
		this.oneShotScheduler = scheduler;
	}

	// =====================
	// GE Offer Changed Handler
	// =====================

	/**
	 * Handle a GE offer change event. Called by the plugin AFTER login burst detection.
	 */
	public void handleOfferChanged(OfferContext ctx)
	{
		if (ctx.state == GrandExchangeOfferState.CANCELLED_BUY || ctx.state == GrandExchangeOfferState.CANCELLED_SELL)
		{
			handleCancelledOffer(ctx);
			return;
		}

		if (ctx.state == GrandExchangeOfferState.EMPTY)
		{
			handleEmptyOffer(ctx.slot);
			return;
		}

		handleActiveOffer(ctx);
	}

	// =====================
	// Cancelled Offers
	// =====================

	private void handleCancelledOffer(OfferContext ctx)
	{
		// Guard against the RuneLite client re-firing a CANCELLED offer event twice for the
		// same slot. A removing cancel (zero-fill / nothing remaining) frees the slot, so the
		// duplicate sees a null record. A partial cancel keeps its slot live as
		// CANCELLED_PARTIAL, so the null check alone misses the re-fire — also short-circuit
		// when the slot already holds a partial-cancel record, making partial cancels
		// idempotent. A re-listed offer holds a fresh NEW/PARTIAL_FILL record, not
		// CANCELLED_PARTIAL, so a legitimate later cancel still proceeds.
		OfferRecord previousOffer = offerStore.bySlot(ctx.slot);
		if (previousOffer == null
			|| previousOffer.getState() == com.flipsmart.domain.offer.OfferState.CANCELLED_PARTIAL)
		{
			log.debug("Ignoring duplicate cancellation event for slot {}", ctx.slot);
			return;
		}

		// Clear manual adjustment timer on cancellation
		if (manualAdjustmentTracker != null)
		{
			manualAdjustmentTracker.clearTimer(ctx.slot);
		}

		if (ctx.quantitySold == 0)
		{
			handleZeroFillCancellation(ctx);
		}

		if (ctx.isBuy && ctx.quantitySold > 0)
		{
			syncCancelledPartialBuy(ctx, previousOffer);
		}

		if (!ctx.isBuy)
		{
			reAddUnsoldItemsOnSellCancel(ctx, previousOffer);
		}

		int totalQuantity = previousOffer.getTotalQuantity() > 0
			? previousOffer.getTotalQuantity() : ctx.totalQuantity;

		// Route the cancellation through apply() so it emits a CANCELLED OfferEvent that the
		// transaction listener records — its residual fill is the final cancellation
		// transaction (no longer recorded inline here). apply() targets CANCELLED_PARTIAL when
		// quantitySold>0 (slot kept) and CANCELLED_EMPTY when zero-fill (slot freed).
		offerStore.apply(toSignal(ctx), System.currentTimeMillis());

		// A cancel that leaves items in the GE collection box must stay collectable so the
		// items route through the normal collect -> (re)sell flow instead of being orphaned.
		// apply() frees the slot (CANCELLED_EMPTY) on a zero-fill cancel, but a partial BUY
		// cancel or a SELL cancel with unsold remaining must keep the slot live
		// (CANCELLED_PARTIAL) so bySlot keeps returning it for the later collect. Promote
		// without re-recording (no new fill, no event) when apply() freed it but we need it kept.
		boolean partialBuyCancel = ctx.isBuy && ctx.quantitySold > 0;
		boolean sellCancelWithRemaining = !ctx.isBuy && (totalQuantity - ctx.quantitySold) > 0;
		OfferRecord afterApply = offerStore.bySlot(ctx.slot);
		boolean slotFreedByApply = afterApply == null
			|| afterApply.getOfferId() != previousOffer.getOfferId();
		if ((partialBuyCancel || sellCancelWithRemaining) && slotFreedByApply)
		{
			OfferRecord promoted = previousOffer
				.withFill(ctx.quantitySold, ctx.spent, com.flipsmart.domain.offer.OfferState.CANCELLED_PARTIAL,
					System.currentTimeMillis());
			offerStore.importRecords(replaceInStore(promoted));
		}

		// Only remove recommended price for zero-fill buy cancels — partial fills need the
		// sell price preserved so the user can be prompted to sell the collected items
		if (ctx.isBuy && ctx.quantitySold == 0)
		{
			session.removeRecommendedPrice(ctx.itemId);
		}

		// Notify auto-recommend AFTER the store cancellation is applied
		if (isAutoRecommendActive())
		{
			autoRecommendService.onOfferCancelled(ctx.itemId, ctx.itemName, ctx.isBuy, ctx.quantitySold, totalQuantity);
		}
	}

	private java.util.List<OfferRecord> replaceInStore(OfferRecord replacement)
	{
		java.util.List<OfferRecord> records = new java.util.ArrayList<>(offerStore.allRecords());
		records.removeIf(r -> r.getOfferId() == replacement.getOfferId());
		records.add(replacement);
		return records;
	}

	private void reAddUnsoldItemsOnSellCancel(OfferContext ctx, OfferRecord sellOffer)
	{
		int orderTotal = sellOffer.getTotalQuantity() > 0 ? sellOffer.getTotalQuantity() : ctx.totalQuantity;
		int remaining = orderTotal - ctx.quantitySold;
		if (remaining > 0)
		{
			session.addCollectedItem(ctx.itemId, remaining, CollectOrigin.COMPLETED_BUY, System.currentTimeMillis());
			log.debug("Sell cancelled for {} - re-added {} unsold items to collected",
				ctx.itemName, remaining);
		}
	}

	private void handleZeroFillCancellation(OfferContext ctx)
	{
		log.debug("Order cancelled with no fills: {} {}",
			ctx.isBuy ? "BUY" : "SELL",
			ctx.itemName);

		if (ctx.isBuy)
		{
			log.debug("Dismissing active flip for {} - buy order cancelled with 0 fills", ctx.itemName);
			apiClient.dismissActiveFlipAsync(ctx.itemId, getRsn().orElse(null));
			fireActiveFlipsRefresh();
		}
	}

	private void syncCancelledPartialBuy(OfferContext ctx, OfferRecord cancelledOffer)
	{
		log.debug("Cancelled buy order had {} items filled (ordered {}) - syncing actual quantity and tracking until sold",
			ctx.quantitySold, cancelledOffer.getTotalQuantity());
		session.addCollectedItem(ctx.itemId, ctx.quantitySold);

		String rsn = getRsn().orElse(null);
		if (rsn != null)
		{
			int pricePerItem = (ctx.quantitySold > 0) ? (int)((long) ctx.spent / ctx.quantitySold) : 0;
			log.debug("Syncing cancelled order quantity to backend: {} x{} (was {})",
				ctx.itemName, ctx.quantitySold, cancelledOffer.getTotalQuantity());
			apiClient.syncActiveFlipAsync(
				ctx.itemId,
				ctx.itemName,
				ctx.quantitySold,
				ctx.quantitySold,
				pricePerItem,
				rsn
			);
		}

		fireActiveFlipsRefresh();
	}

	// =====================
	// Empty Offers (collected/cleared/modified)
	// =====================

	private void handleEmptyOffer(int slot)
	{
		// Capture the record before the collect transition frees the slot, then mirror the
		// collect into the store (terminal COLLECTED) so it stops occupying the slot.
		OfferRecord collectedOffer = offerStore.bySlot(slot);

		if (collectedOffer == null)
		{
			return;
		}

		offerStore.apply(emptySignal(slot, collectedOffer), System.currentTimeMillis());

		if (collectedOffer.isBuy() && collectedOffer.getFilledQuantity() > 0)
		{
			handleCollectedBuyOffer(collectedOffer);
		}
		else if (!collectedOffer.isBuy())
		{
			handleCollectedSellOffer(collectedOffer);
		}
		else if (collectedOffer.isBuy() && collectedOffer.getFilledQuantity() == 0)
		{
			handleEmptyBuyWithZeroFills(collectedOffer);
		}

		notifyAutoRecommendCollection(collectedOffer);
		schedulePanelRefresh();
	}

	private OfferSignal emptySignal(int slot, OfferRecord record)
	{
		return OfferEventMapper.toSignal(
			slot, GrandExchangeOfferState.EMPTY, record.getItemId(), record.getItemName(),
			record.getTotalQuantity(), record.getPrice(), record.getFilledQuantity(), record.getSpent());
	}

	private void handleCollectedBuyOffer(OfferRecord collectedOffer)
	{
		int inventoryCount = activeFlipTracker.getInventoryCountForItem(collectedOffer.getItemId());
		int trackedFills = collectedOffer.getFilledQuantity();
		int collectedQty = trackedFills;

		if (inventoryCount > trackedFills)
		{
			collectedQty = Math.min(inventoryCount, collectedOffer.getTotalQuantity());
			log.debug("Order for {} may have completed offline - tracked {} fills but have {} in inventory. Using {} as collected quantity.",
				collectedOffer.getItemName(), trackedFills, inventoryCount, collectedQty);

			String rsn = getRsn().orElse(null);
			if (rsn != null)
			{
				apiClient.syncActiveFlipAsync(
					collectedOffer.getItemId(),
					collectedOffer.getItemName(),
					collectedQty,
					collectedOffer.getTotalQuantity(),
					collectedOffer.getSpent() > 0 && collectedOffer.getFilledQuantity() > 0
						? (int)(collectedOffer.getSpent() / collectedOffer.getFilledQuantity())
						: collectedOffer.getPrice(),
					rsn
				);
			}
		}

		log.debug("Buy offer collected from GE: {} x{} - tracking until sold",
			collectedOffer.getItemName(), collectedQty);
		session.addCollectedItem(collectedOffer.getItemId(), collectedQty);
	}

	private void handleCollectedSellOffer(OfferRecord collectedOffer)
	{
		int inventoryCount = activeFlipTracker.getInventoryCountForItem(collectedOffer.getItemId());
		if (inventoryCount > 0)
		{
			log.debug("Sell offer collected/modified for {}: {} items returned to inventory - keeping active flip tracking",
				collectedOffer.getItemName(), inventoryCount);
			session.addCollectedItem(collectedOffer.getItemId(), inventoryCount);
		}
		else
		{
			log.debug("Sell offer for {} went empty with no items in inventory - dismissing active flip",
				collectedOffer.getItemName());
			activeFlipTracker.dismissFlip(collectedOffer.getItemId());
		}
	}

	private void handleEmptyBuyWithZeroFills(OfferRecord collectedOffer)
	{
		int inventoryCount = activeFlipTracker.getInventoryCountForItem(collectedOffer.getItemId());
		if (inventoryCount > 0)
		{
			log.debug("Buy order for {} went empty but found {} items in inventory - may have filled offline",
				collectedOffer.getItemName(), inventoryCount);
			session.addCollectedItem(collectedOffer.getItemId(), inventoryCount);
		}
	}

	private void notifyAutoRecommendCollection(OfferRecord collectedOffer)
	{
		if (isAutoRecommendActive())
		{
			// Use the session's collected quantity (which accounts for offline fills)
			// rather than the record's stale filled quantity
			int quantity = collectedOffer.getFilledQuantity();
			if (collectedOffer.isBuy())
			{
				int sessionQty = session.getCollectedQuantity(collectedOffer.getItemId());
				if (sessionQty > 0)
				{
					quantity = sessionQty;
				}
			}

			autoRecommendService.onOfferCollected(
				collectedOffer.getItemId(),
				collectedOffer.isBuy(),
				collectedOffer.getItemName(),
				quantity
			);
		}
	}

	// =====================
	// Active Offers (fills, new orders)
	// =====================

	private void handleActiveOffer(OfferContext ctx)
	{
		// OfferStore is the authority for the fill-delta / transition decision. The baseline
		// is the slot's current record (null on first sight); apply the signal, then drive the
		// side-effects below from the returned transition and the pre-apply baseline spend.
		OfferRecord previousOffer = offerStore.bySlot(ctx.slot);

		// For an immediate-fill buy (first sight already showing fills), the session must
		// hold the recommended sell price before offerStore.apply fires the TransactionLogger
		// listener — the logger reads the price from session at record time. Any case where
		// this is not an immediate-fill buy is a no-op inside preStoreImmediateFillSellPrice
		// because its own guards check isBuy, totalQuantity, and isAutoRecommendActive.
		if (previousOffer == null && ctx.quantitySold > 0)
		{
			preStoreImmediateFillSellPrice(ctx);
		}

		OfferTransition transition = offerStore.apply(toSignal(ctx), System.currentTimeMillis());

		if (ctx.quantitySold > 0)
		{
			handleOfferWithFills(ctx, previousOffer, transition);
		}
		else
		{
			handleNewOfferNoFills(ctx, previousOffer);
		}
	}

	private void handleOfferWithFills(OfferContext ctx, OfferRecord previousOffer,
		OfferTransition transition)
	{
		int newQuantity = newlyFilledFrom(transition);

		if (newQuantity > 0)
		{
			applyFillSideEffects(ctx, newQuantity);
		}

		// Reset adjustment timer on partial fills (not yet fully completed)
		if (newQuantity > 0 && ctx.state != GrandExchangeOfferState.BOUGHT
			&& ctx.state != GrandExchangeOfferState.SOLD)
		{
			if (isAutoRecommendActive())
			{
				if (ctx.isBuy)
				{
					autoRecommendService.resetAdjustmentTimer(ctx.itemId, ctx.price);
				}
				else
				{
					autoRecommendService.resetSellAdjustmentTimer(ctx.itemId);
				}
			}
			else if (manualAdjustmentTracker != null)
			{
				manualAdjustmentTracker.resetTimer(ctx.slot);
			}
		}

		// Clear manual adjustment timer on completion
		if ((ctx.state == GrandExchangeOfferState.BOUGHT || ctx.state == GrandExchangeOfferState.SOLD)
			&& manualAdjustmentTracker != null)
		{
			manualAdjustmentTracker.clearTimer(ctx.slot);
		}

		// The store record was already advanced by apply() above (state, fill, spend,
		// completion + activity timestamps), so getCompletedOffers() sees the BOUGHT/SOLD
		// state when promptCollection() runs.
		notifyAutoRecommendOnCompletion(ctx);

		if (previousOffer == null && ctx.totalQuantity > 0)
		{
			handleImmediateFillTransitions(ctx.itemId, ctx.itemName, ctx.isBuy);
		}
	}

	private int newlyFilledFrom(OfferTransition transition)
	{
		switch (transition.kind)
		{
			case PLACED:
			case FILLED_DELTA:
			case COMPLETED:
				return transition.newlyFilledQuantity;
			default:
				return 0;
		}
	}

	private void preStoreImmediateFillSellPrice(OfferContext ctx)
	{
		if (!ctx.isBuy || ctx.totalQuantity <= 0 || !isAutoRecommendActive())
		{
			return;
		}

		FlipRecommendation currentRec = autoRecommendService.getCurrentRecommendation();
		if (currentRec != null && currentRec.getItemId() == ctx.itemId && currentRec.getRecommendedSellPrice() > 0)
		{
			log.debug("Immediate-fill buy for {} - pre-storing recommended sell price {}", ctx.itemName, currentRec.getRecommendedSellPrice());
			session.setRecommendedPrice(ctx.itemId, currentRec.getRecommendedSellPrice());
		}
	}

	private void applyFillSideEffects(OfferContext ctx, int newQuantity)
	{
		// Recording is owned by TransactionLogger, which subscribes to the OfferStore and
		// records the fill from the same apply() that produced this transition. The
		// non-recording bookkeeping below rides the same single flow so it still fires
		// exactly once per fill.
		log.debug("Fill side-effects: {} {} x{} (slot {}, {}/{} filled)",
			ctx.isBuy ? "BUY" : "SELL",
			ctx.itemName,
			newQuantity,
			ctx.slot,
			ctx.quantitySold,
			ctx.totalQuantity);

		tradeActivityLog.addEntry(ctx.itemId, ctx.itemName, ctx.isBuy, newQuantity);

		if (!ctx.isBuy)
		{
			activeFlipTracker.markItemSold(ctx.itemId);
		}

		schedulePanelRefresh();
	}

	private void notifyAutoRecommendOnCompletion(OfferContext ctx)
	{
		if (!isAutoRecommendActive())
		{
			return;
		}

		if (ctx.state == GrandExchangeOfferState.BOUGHT && ctx.isBuy)
		{
			autoRecommendService.onBuyOrderCompleted(ctx.itemId, ctx.itemName);
		}
		if (ctx.state == GrandExchangeOfferState.SOLD && !ctx.isBuy)
		{
			autoRecommendService.onSellOrderCompleted(ctx.itemId);
		}
	}

	private void handleImmediateFillTransitions(int itemId, String itemName, boolean isBuy)
	{
		if (isBuy && isAutoRecommendActive())
		{
			log.debug("Immediate-fill buy for {} - advancing auto-recommend queue", itemName);
			autoRecommendService.onBuyOrderPlaced(itemId);
		}
		else if (!isBuy)
		{
			handleImmediateFillSell(itemId, itemName);
		}
	}

	private void handleImmediateFillSell(int itemId, String itemName)
	{
		log.debug("Immediate-fill sell for {} - performing sell bookkeeping", itemName);
		String rsn = getRsn().orElse(null);
		if (rsn != null)
		{
			apiClient.markActiveFlipSellingAsync(itemId, rsn);
		}
		// Don't remove recommended price — preserve for cancel recovery
		if (isAutoRecommendActive())
		{
			autoRecommendService.onSellOrderPlaced(itemId);
		}
	}

	private void handleNewOfferNoFills(OfferContext ctx, OfferRecord previousOffer)
	{
		// The store record was minted (or left unchanged on a re-sent same-price event) by
		// apply() above, preserving the placement timestamp across re-fires; no separate
		// session write is needed.
		clearFlipAssistFocusIfMatches(ctx.itemId, ctx.isBuy);

		if (previousOffer == null && ctx.totalQuantity > 0)
		{
			if (ctx.isBuy)
			{
				recordNewBuyOrder(ctx);
			}
			else
			{
				recordNewSellOrder(ctx);
			}
			firePendingOrdersAndActiveFlipsRefresh();
		}
	}

	private void recordNewBuyOrder(OfferContext ctx)
	{
		log.debug("Recording new buy order: {} x{} @ {} gp each (slot {}, 0/{} filled)",
			ctx.itemName, 0, ctx.price, ctx.slot, ctx.totalQuantity);

		if (isAutoRecommendActive())
		{
			autoRecommendService.onBuyOrderPlaced(ctx.itemId);
		}
		else if (manualAdjustmentTracker != null && isAdjustmentPromptsEnabled())
		{
			manualAdjustmentTracker.scheduleBuyAdjustment(
				ctx.itemId, ctx.itemName, ctx.slot, ctx.price);
		}

		// The placement transaction is recorded by TransactionLogger off the PLACED event.
	}

	private void recordNewSellOrder(OfferContext ctx)
	{
		log.debug("Sell order placed for {} x{} - marking active flip as selling", ctx.itemName, ctx.totalQuantity);
		String rsn = getRsn().orElse(null);
		if (rsn != null)
		{
			apiClient.markActiveFlipSellingAsync(ctx.itemId, rsn);
		}

		// Don't remove recommended price here — if the sell is cancelled, we need it
		// to re-show the sell overlay with the correct price. Price is cleaned up when
		// the buy is cancelled or the flip is dismissed.

		if (isAutoRecommendActive())
		{
			autoRecommendService.onSellOrderPlaced(ctx.itemId);
		}

		// Schedule sell-side adjustment timer for manual mode
		if (!isAutoRecommendActive() && manualAdjustmentTracker != null && isAdjustmentPromptsEnabled())
		{
			// Use the buy price as cost basis — fall back to sell price if unavailable
			Integer buyPrice = session.getRecommendedPrice(ctx.itemId);
			int averageBuyPrice = (buyPrice != null && buyPrice > 0) ? buyPrice : ctx.price;
			manualAdjustmentTracker.scheduleSellAdjustment(
				ctx.itemId, ctx.itemName, ctx.slot, ctx.price, averageBuyPrice);
		}

		OfferAdviceResponse priorAdvice = (activeOfferAdvisorService != null)
			? activeOfferAdvisorService.getDisposition(ctx.itemId)
			: null;
		if (priorAdvice != null
			&& priorAdvice.getActionEnum() == OfferAction.EXIT_AT_BREAKEVEN
			&& priorAdvice.getNewPrice() != null)
		{
			OfferRecord relisted = offerStore.bySlot(ctx.slot);
			if (relisted != null
				&& OfferRecord.shouldAdvanceToBreakevenRelist(true, ctx.price, priorAdvice.getNewPrice()))
			{
				offerStore.importRecords(
					replaceInStore(relisted.withOfferStage(OfferRecord.STAGE_BREAKEVEN_RELIST)));
			}
		}
	}

	// =====================
	// Flip Assist Focus
	// =====================

	/**
	 * Auto-focus on an active flip when the player sets up a sell offer for that item.
	 * During auto-recommend, overrides focus if the item is a collected item with a sell price.
	 */
	/**
	 * Re-run the sell focus after the recommended price changed underneath an
	 * already-open sell screen (e.g. a fresh 12h sell-price recalc). Clears the
	 * per-item debounce so the refreshed price is not swallowed as a duplicate.
	 */
	public void refreshSellFocus(int itemId)
	{
		lastAutoFocusItemId = -1;
		autoFocusOnActiveFlip(itemId);
	}

	public void autoFocusOnActiveFlip(int itemId)
	{
		// Debounce: GE_OFFERS_SETUP_BUILD fires multiple times per interaction
		long now = System.currentTimeMillis();
		if (itemId == lastAutoFocusItemId && (now - lastAutoFocusTimeMs) < AUTO_FOCUS_DEBOUNCE_MS)
		{
			return;
		}
		lastAutoFocusItemId = itemId;
		lastAutoFocusTimeMs = now;

		if (isAutoRecommendActive())
		{
			// overrideFocusForSell handles sell price recovery, quantity resolution
			// (including inventory fallback and auto-correction of session state)
			String itemName = ItemUtils.getItemName(itemManager, itemId);
			AutoRecommendService.SellFocusResult result =
				autoRecommendService.overrideFocusForSell(itemId, itemName);
			log.debug("Auto-focus sell {} -> {}", itemName, result);
			if (result != AutoRecommendService.SellFocusResult.UNAVAILABLE)
			{
				// FOCUSED or ALREADY_SELLING — settled, nothing more to do.
				clearPendingSellFocus();
				return;
			}
			// State from the just-finished collect (cleared offer slot, updated
			// inventory) has not caught up to the freshly-opened sell screen yet.
			// Re-attempt over the next few game ticks instead of falling to the slow
			// async path or giving up — that lag is what made the re-sell focus
			// arrive late or not show at all.
			pendingSellFocusItemId = itemId;
			pendingSellFocusTicksLeft = SELL_FOCUS_RETRY_TICKS;
			return;
		}

		tryAsyncSellFocus(itemId);
	}

	/**
	 * Backend fallback: resolve the item's active flip via the API and focus the
	 * sell from that. Used for items the local auto-recommend state cannot resolve
	 * (e.g. not in the queue), and after the local tick-retry budget is exhausted.
	 * Runs on the client thread (reads the inventory container).
	 */
	private void tryAsyncSellFocus(int itemId)
	{
		String rsn = getRsn().orElse(null);

		// Capture inventory count on client thread before async API call
		// (client.getItemContainer only works on the client thread)
		int inventoryCount = activeFlipTracker.getInventoryCountForItem(itemId);

		apiClient.getActiveFlipsAsync(rsn).thenAccept(response ->
			handleActiveFlipResponse(response, itemId, inventoryCount, rsn));
	}

	/**
	 * Re-attempt a deferred sell focus once per game tick until the underlying
	 * collect/inventory/session state settles or the retry budget runs out. Must
	 * run on the client thread (overrideFocusForSell reads inventory). Invoked
	 * from the plugin's GameTick handler.
	 */
	public void retryPendingSellFocusTick()
	{
		if (pendingSellFocusItemId < 0)
		{
			return;
		}
		if (!isAutoRecommendActive())
		{
			clearPendingSellFocus();
			return;
		}
		int itemId = pendingSellFocusItemId;
		String itemName = ItemUtils.getItemName(itemManager, itemId);
		AutoRecommendService.SellFocusResult result =
			autoRecommendService.overrideFocusForSell(itemId, itemName);
		if (result != AutoRecommendService.SellFocusResult.UNAVAILABLE)
		{
			log.debug("Auto-focus sell retry settled {} -> {}", itemName, result);
			clearPendingSellFocus();
			return;
		}
		pendingSellFocusTicksLeft--;
		if (pendingSellFocusTicksLeft <= 0)
		{
			// Local state never settled — fall back to the backend active-flip
			// lookup (the original behaviour for items not resolvable locally).
			log.debug("Auto-focus sell retry budget exhausted for {} - falling back to API lookup", itemName);
			clearPendingSellFocus();
			tryAsyncSellFocus(itemId);
		}
	}

	private void clearPendingSellFocus()
	{
		pendingSellFocusItemId = -1;
		pendingSellFocusTicksLeft = 0;
	}

	private void handleActiveFlipResponse(
		ActiveFlipsResponse response, int itemId, int inventoryCount, String rsn)
	{
		if (response == null || response.getActiveFlips() == null)
		{
			return;
		}

		ActiveFlip matchingFlip = findActiveFlipForItem(response.getActiveFlips(), itemId);
		if (matchingFlip == null)
		{
			log.debug("No active flip found for item {} when setting up sell offer", itemId);
			return;
		}

		// Guard against race condition: sell may have been placed between
		// API request and response — don't override the cleared focus
		if (offerStore.hasActiveSellOfferForItem(itemId))
		{
			log.debug("Sell already placed for {} - not overriding focus", matchingFlip.getItemName());
			return;
		}

		setFocusForSell(matchingFlip, inventoryCount);

		// Sync inventory-corrected quantity to API if inventory has more
		if (inventoryCount > matchingFlip.getTotalQuantity() && inventoryCount > 0 && rsn != null)
		{
			int orderQty = matchingFlip.getOrderQuantity() > 0
				? matchingFlip.getOrderQuantity() : inventoryCount;
			log.debug("Syncing inventory-corrected quantity for {} to API: {} items",
				matchingFlip.getItemName(), inventoryCount);
			apiClient.syncActiveFlipAsync(
				matchingFlip.getItemId(),
				matchingFlip.getItemName(),
				inventoryCount,
				orderQty,
				matchingFlip.getAverageBuyPrice(),
				rsn
			);
		}
	}

	private ActiveFlip findActiveFlipForItem(List<ActiveFlip> flips, int itemId)
	{
		for (ActiveFlip flip : flips)
		{
			if (flip.getItemId() == itemId)
			{
				return flip;
			}
		}
		return null;
	}

	private void setFocusForSell(ActiveFlip flip, int inventoryFallbackCount)
	{
		int sellPrice;

		Integer panelPrice = displayedSellPriceProvider != null
			? displayedSellPriceProvider.apply(flip.getItemId()) : null;

		if (panelPrice != null && panelPrice > 0)
		{
			sellPrice = panelPrice;
			log.debug("Using panel's displayed sell price for {}: {} gp", flip.getItemName(), sellPrice);
		}
		else if (flip.getRecommendedSellPrice() != null && flip.getRecommendedSellPrice() > 0)
		{
			sellPrice = flip.getRecommendedSellPrice();
			log.debug("Using backend recommended sell price for {}: {} gp", flip.getItemName(), sellPrice);
		}
		else
		{
			sellPrice = (int) Math.ceil((flip.getAverageBuyPrice() + 1) / 0.98);
			log.debug("Using calculated min profitable price for {}: {} gp", flip.getItemName(), sellPrice);
		}

		// Use the higher of API quantity vs actual inventory count
		// (inventory is the source of truth — player may have more than API tracked)
		int apiQuantity = flip.getTotalQuantity();
		int sellQuantity = Math.max(apiQuantity, inventoryFallbackCount);

		int priceOffset = config != null ? config.priceOffset() : 0;
		FocusedFlip focus = FocusedFlip.forSell(
			flip.getItemId(),
			flip.getItemName(),
			sellPrice,
			sellQuantity,
			priceOffset
		);

		if (onFocusChanged != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> onFocusChanged.accept(focus));
		}
		log.debug("Auto-focused on active flip for sell: {} @ {} gp (qty: api={}, inv={}, using={})",
			flip.getItemName(), sellPrice, apiQuantity, inventoryFallbackCount, sellQuantity);
	}

	/**
	 * Clear the Flip Assist focus if the submitted order matches the focused item.
	 */
	public void clearFlipAssistFocusIfMatches(int itemId, boolean isBuy)
	{
		if (isAutoRecommendActive())
		{
			return;
		}

		if (onFocusClear == null)
		{
			return;
		}

		onFocusClear.accept(itemId, isBuy);
	}

	// =====================
	// OfferStore routing
	// =====================

	private OfferSignal toSignal(OfferContext ctx)
	{
		return OfferEventMapper.toSignal(
			ctx.slot, ctx.state, ctx.itemId, ctx.itemName,
			ctx.totalQuantity, ctx.price, ctx.quantitySold, ctx.spent);
	}

	private boolean isAutoRecommendActive()
	{
		return autoRecommendService != null && autoRecommendService.isActive();
	}

	private boolean isAdjustmentPromptsEnabled()
	{
		return adjustmentPromptsEnabled != null && adjustmentPromptsEnabled.getAsBoolean();
	}

	private Optional<String> getRsn()
	{
		if (rsnSupplier != null)
		{
			return rsnSupplier.get();
		}
		return session.getRsnSafe();
	}

	private void fireActiveFlipsRefresh()
	{
		if (onActiveFlipsRefresh != null)
		{
			javax.swing.SwingUtilities.invokeLater(onActiveFlipsRefresh);
		}
	}

	private void schedulePanelRefresh()
	{
		if (onPanelRefresh != null && oneShotScheduler != null)
		{
			oneShotScheduler.accept(TRANSACTION_REFRESH_DELAY_MS, () ->
				javax.swing.SwingUtilities.invokeLater(onPanelRefresh));
		}
	}

	private void firePendingOrdersAndActiveFlipsRefresh()
	{
		if (onPendingOrdersUpdate != null && onActiveFlipsRefresh != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> {
				onPendingOrdersUpdate.accept(null);
				onActiveFlipsRefresh.run();
			});
		}
	}
}
