package com.flipsmart;
import com.flipsmart.api.dto.TransactionRequest;
import com.flipsmart.domain.offer.OfferAction;
import com.flipsmart.api.dto.ActiveFlipsResponse;
import com.flipsmart.api.dto.OfferAdviceResponse;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferTransition;
import com.flipsmart.domain.offer.PendingOrder;
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

		if (ctx.quantitySold > 0)
		{
			recordFinalCancellationFill(ctx, previousOffer);
		}
		else
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

		// A cancel that leaves items in the GE collection box must stay collectable so
		// the items route through the normal collect -> (re)sell flow instead of being
		// orphaned. The store keeps a partial-cancel record live (CANCELLED_PARTIAL) and
		// frees the slot on a zero-fill cancel (CANCELLED_EMPTY), mirroring the prior
		// keep/remove split:
		//  - partial BUY cancel: the bought items still need selling
		//  - SELL cancel with unsold items: the returned items need re-listing
		boolean partialBuyCancel = ctx.isBuy && ctx.quantitySold > 0;
		boolean sellCancelWithRemaining = !ctx.isBuy && (totalQuantity - ctx.quantitySold) > 0;
		if ((partialBuyCancel || sellCancelWithRemaining) && previousOffer.getFilledQuantity() == 0)
		{
			// Edge: a "cancel with remaining" that the store recorded as zero-filled still
			// needs to stay collectable; promote it to a partial-cancel via a fill replay so
			// bySlot keeps returning it for the later collect.
			OfferRecord promoted = previousOffer
				.withFill(ctx.quantitySold, ctx.spent, com.flipsmart.domain.offer.OfferState.CANCELLED_PARTIAL,
					System.currentTimeMillis());
			offerStore.importRecords(replaceInStore(promoted));
		}
		else
		{
			// Apply the cancellation: partial keeps the slot (CANCELLED_PARTIAL), zero-fill
			// frees it (CANCELLED_EMPTY) so hasActiveSellOfferForItem returns false and
			// findNextSellableCollectedItem can find the re-added items.
			offerStore.apply(toSignal(ctx), System.currentTimeMillis());
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
			session.addCollectedItem(ctx.itemId, remaining);
			log.debug("Sell cancelled for {} - re-added {} unsold items to collected",
				ctx.itemName, remaining);
		}
	}

	private void recordFinalCancellationFill(OfferContext ctx, OfferRecord previousOffer)
	{
		if (ctx.quantitySold > previousOffer.getFilledQuantity())
		{
			int newQuantity = ctx.quantitySold - previousOffer.getFilledQuantity();
			long previousSpent = previousOffer.getSpent();
			long incrementalSpent = ctx.spent - previousSpent;
			int pricePerItem = (newQuantity > 0) ? (int)(incrementalSpent / newQuantity) : 0;

			log.debug("Recording final transaction before cancellation: {} {} x{}/{} @ {} gp each",
				ctx.isBuy ? "BUY" : "SELL",
				ctx.itemName,
				newQuantity,
				previousOffer.getTotalQuantity(),
				pricePerItem);

			Integer recommendedSellPrice = ctx.isBuy ? session.getRecommendedPrice(ctx.itemId) : null;

			apiClient.recordTransactionAsync(TransactionRequest
				.builder(ctx.itemId, ctx.itemName, ctx.isBuy, newQuantity, pricePerItem)
				.geSlot(ctx.slot)
				.recommendedSellPrice(recommendedSellPrice)
				.rsn(getRsn().orElse(null))
				.totalQuantity(previousOffer.getTotalQuantity())
				.build());
		}

		log.debug("Order cancelled: {} {} - {} items filled out of {}",
			ctx.isBuy ? "BUY" : "SELL",
			ctx.itemName,
			ctx.quantitySold,
			ctx.totalQuantity);
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
		long priorSpent = previousOffer != null ? previousOffer.getSpent() : 0L;
		OfferTransition transition = offerStore.apply(toSignal(ctx), System.currentTimeMillis());

		if (ctx.quantitySold > 0)
		{
			handleOfferWithFills(ctx, previousOffer, transition, priorSpent);
		}
		else
		{
			handleNewOfferNoFills(ctx, previousOffer);
		}
	}

	private void handleOfferWithFills(OfferContext ctx, OfferRecord previousOffer,
		OfferTransition transition, long priorSpent)
	{
		int newQuantity = newlyFilledFrom(transition);

		// First sight of this offer already showing fills (immediate/offline fill): capture
		// the recommended sell price before recording, mirroring the old first-fill path.
		if (previousOffer == null && newQuantity > 0)
		{
			preStoreImmediateFillSellPrice(ctx);
		}

		if (newQuantity > 0)
		{
			recordFillTransaction(ctx, newQuantity, priorSpent, previousOffer == null);
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

	private void recordFillTransaction(OfferContext ctx, int newQuantity, long priorSpent, boolean noBaseline)
	{
		long incrementalSpent = ctx.spent - priorSpent;
		int pricePerItem = (newQuantity > 0) ? (int)(incrementalSpent / newQuantity) : 0;

		// Diagnostic: when there was no baseline tracked offer, incrementalSpent == ctx.spent.
		// If ctx.spent reflects an earlier order's allocation (slot reuse, plugin reload,
		// world hop, login-burst miss), the recorded pricePerItem will equal the *placed*
		// price of that earlier order rather than the actual fill. Surface the conditions so
		// we can correlate to a specific code path.
		if (noBaseline && newQuantity > 0)
		{
			log.warn("[FlipSmart][no-baseline] Recording fill with no prior baseline state — "
					+ "slot={} item={} ({}) newQty={} ctxSpent={} placedPrice={} qtySold={} totalQty={} state={} pricePerItem={}",
				ctx.slot, ctx.itemId, ctx.itemName, newQuantity, ctx.spent, ctx.price,
				ctx.quantitySold, ctx.totalQuantity, ctx.state, pricePerItem);
		}

		log.debug("Recording transaction: {} {} x{} @ {} gp each (slot {}, {}/{} filled)",
			ctx.isBuy ? "BUY" : "SELL",
			ctx.itemName,
			newQuantity,
			pricePerItem,
			ctx.slot,
			ctx.quantitySold,
			ctx.totalQuantity);

		Integer recommendedSellPrice = ctx.isBuy ? session.getRecommendedPrice(ctx.itemId) : null;

		apiClient.recordTransactionAsync(TransactionRequest
			.builder(ctx.itemId, ctx.itemName, ctx.isBuy, newQuantity, pricePerItem)
			.geSlot(ctx.slot)
			.recommendedSellPrice(recommendedSellPrice)
			.rsn(getRsn().orElse(null))
			.totalQuantity(ctx.totalQuantity)
			.build());

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

		Integer recommendedSellPrice = session.getRecommendedPrice(ctx.itemId);

		apiClient.recordTransactionAsync(TransactionRequest
			.builder(ctx.itemId, ctx.itemName, true, 0, ctx.price)
			.geSlot(ctx.slot)
			.recommendedSellPrice(recommendedSellPrice)
			.rsn(getRsn().orElse(null))
			.totalQuantity(ctx.totalQuantity)
			.build());
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
