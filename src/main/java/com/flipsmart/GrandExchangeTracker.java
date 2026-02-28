package com.flipsmart;

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

	private final PlayerSession session;
	private final FlipSmartApiClient apiClient;
	private final ActiveFlipTracker activeFlipTracker;
	private final ItemManager itemManager;

	// Set after construction (created in plugin startUp)
	private AutoRecommendService autoRecommendService;

	// Callbacks wired by the plugin
	private Supplier<Optional<String>> rsnSupplier;
	private Runnable onPanelRefresh;
	private Runnable onActiveFlipsRefresh;
	private Consumer<List<PendingOrder>> onPendingOrdersUpdate;
	private Consumer<FocusedFlip> onFocusChanged;
	private BiConsumer<Integer, Boolean> onFocusClear;
	private IntFunction<Integer> displayedSellPriceProvider;
	private BiConsumer<Integer, Runnable> oneShotScheduler;

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
		ItemManager itemManager)
	{
		this.session = session;
		this.apiClient = apiClient;
		this.activeFlipTracker = activeFlipTracker;
		this.itemManager = itemManager;
	}

	// =====================
	// Setter wiring
	// =====================

	public void setAutoRecommendService(AutoRecommendService service)
	{
		this.autoRecommendService = service;
	}

	public void setRsnSupplier(Supplier<Optional<String>> rsnSupplier)
	{
		this.rsnSupplier = rsnSupplier;
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
		// Duplicate cancellation guard (RuneLite bug #12037)
		if (session.getTrackedOffer(ctx.slot) == null)
		{
			log.debug("Ignoring duplicate cancellation event for slot {}", ctx.slot);
			return;
		}

		if (ctx.quantitySold > 0)
		{
			recordFinalCancellationFill(ctx);
		}
		else
		{
			handleZeroFillCancellation(ctx);
		}

		if (ctx.isBuy && ctx.quantitySold > 0)
		{
			syncCancelledPartialBuy(ctx);
		}

		// For sell cancels: re-add unsold items to collected so they can be re-listed
		if (!ctx.isBuy)
		{
			TrackedOffer sellOffer = session.getTrackedOffer(ctx.slot);
			int orderTotal = sellOffer != null ? sellOffer.getTotalQuantity() : ctx.totalQuantity;
			int remaining = orderTotal - ctx.quantitySold;
			if (remaining > 0)
			{
				session.addCollectedItem(ctx.itemId, remaining);
				log.info("Sell cancelled for {} - re-added {} unsold items to collected",
					sellOffer != null ? sellOffer.getItemName() : ctx.itemName, remaining);
			}
		}

		// Capture total quantity before removing tracked offer
		TrackedOffer cancelledOffer = session.getTrackedOffer(ctx.slot);
		int totalQuantity = cancelledOffer != null ? cancelledOffer.getTotalQuantity() : ctx.totalQuantity;

		// Remove tracked offer BEFORE notifying auto-recommend so hasActiveSellSlotForItem
		// returns false and findNextSellableCollectedItem can find the re-added items
		session.removeTrackedOffer(ctx.slot);

		// Only remove recommended price for buy cancels — sell cancels preserve it for re-listing
		if (ctx.isBuy)
		{
			session.removeRecommendedPrice(ctx.itemId);
		}

		// Notify auto-recommend AFTER tracked offer removal
		if (isAutoRecommendActive())
		{
			autoRecommendService.onOfferCancelled(ctx.itemId, ctx.isBuy, ctx.quantitySold, totalQuantity);
		}
	}

	private void recordFinalCancellationFill(OfferContext ctx)
	{
		TrackedOffer previousOffer = session.getTrackedOffer(ctx.slot);

		if (previousOffer != null && ctx.quantitySold > previousOffer.getPreviousQuantitySold())
		{
			int newQuantity = ctx.quantitySold - previousOffer.getPreviousQuantitySold();
			int pricePerItem = ctx.spent / ctx.quantitySold;

			log.info("Recording final transaction before cancellation: {} {} x{}/{} @ {} gp each",
				ctx.isBuy ? "BUY" : "SELL",
				previousOffer.getItemName(),
				newQuantity,
				previousOffer.getTotalQuantity(),
				pricePerItem);

			Integer recommendedSellPrice = ctx.isBuy ? session.getRecommendedPrice(ctx.itemId) : null;

			apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
				.builder(ctx.itemId, previousOffer.getItemName(), ctx.isBuy, newQuantity, pricePerItem)
				.geSlot(ctx.slot)
				.recommendedSellPrice(recommendedSellPrice)
				.rsn(getRsn().orElse(null))
				.totalQuantity(previousOffer.getTotalQuantity())
				.build());
		}

		log.info("Order cancelled: {} {} - {} items filled out of {}",
			ctx.isBuy ? "BUY" : "SELL",
			previousOffer != null ? previousOffer.getItemName() : ctx.itemName,
			ctx.quantitySold,
			ctx.totalQuantity);
	}

	private void handleZeroFillCancellation(OfferContext ctx)
	{
		TrackedOffer previousOffer = session.getTrackedOffer(ctx.slot);
		log.info("Order cancelled with no fills: {} {}",
			ctx.isBuy ? "BUY" : "SELL",
			previousOffer != null ? previousOffer.getItemName() : ctx.itemName);

		if (ctx.isBuy)
		{
			log.info("Dismissing active flip for {} - buy order cancelled with 0 fills", ctx.itemName);
			apiClient.dismissActiveFlipAsync(ctx.itemId, getRsn().orElse(null));
			fireActiveFlipsRefresh();
		}
	}

	private void syncCancelledPartialBuy(OfferContext ctx)
	{
		TrackedOffer cancelledOffer = session.getTrackedOffer(ctx.slot);
		log.info("Cancelled buy order had {} items filled (ordered {}) - syncing actual quantity and tracking until sold",
			ctx.quantitySold, cancelledOffer != null ? cancelledOffer.getTotalQuantity() : "?");
		session.addCollectedItem(ctx.itemId, ctx.quantitySold);

		String rsn = getRsn().orElse(null);
		if (rsn != null && cancelledOffer != null)
		{
			int pricePerItem = ctx.spent / ctx.quantitySold;
			log.info("Syncing cancelled order quantity to backend: {} x{} (was {})",
				cancelledOffer.getItemName(), ctx.quantitySold, cancelledOffer.getTotalQuantity());
			apiClient.syncActiveFlipAsync(
				ctx.itemId,
				cancelledOffer.getItemName(),
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
		TrackedOffer collectedOffer = session.removeTrackedOffer(slot);

		if (collectedOffer == null)
		{
			return;
		}

		if (collectedOffer.isBuy() && collectedOffer.getPreviousQuantitySold() > 0)
		{
			handleCollectedBuyOffer(collectedOffer);
		}
		else if (!collectedOffer.isBuy())
		{
			handleCollectedSellOffer(collectedOffer);
		}
		else if (collectedOffer.isBuy() && collectedOffer.getPreviousQuantitySold() == 0)
		{
			handleEmptyBuyWithZeroFills(collectedOffer);
		}

		ensureFallbackSellPrice(collectedOffer);
		notifyAutoRecommendCollection(collectedOffer);
		schedulePanelRefresh();
	}

	private void handleCollectedBuyOffer(TrackedOffer collectedOffer)
	{
		int inventoryCount = activeFlipTracker.getInventoryCountForItem(collectedOffer.getItemId());
		int trackedFills = collectedOffer.getPreviousQuantitySold();
		int collectedQty = trackedFills;

		if (inventoryCount > trackedFills)
		{
			collectedQty = Math.min(inventoryCount, collectedOffer.getTotalQuantity());
			log.info("Order for {} may have completed offline - tracked {} fills but have {} in inventory. Using {} as collected quantity.",
				collectedOffer.getItemName(), trackedFills, inventoryCount, collectedQty);

			String rsn = getRsn().orElse(null);
			if (rsn != null)
			{
				apiClient.syncActiveFlipAsync(
					collectedOffer.getItemId(),
					collectedOffer.getItemName(),
					collectedQty,
					collectedOffer.getTotalQuantity(),
					collectedOffer.getPrice(),
					rsn
				);
			}
		}

		log.info("Buy offer collected from GE: {} x{} - tracking until sold",
			collectedOffer.getItemName(), collectedQty);
		session.addCollectedItem(collectedOffer.getItemId(), collectedQty);
	}

	private void handleCollectedSellOffer(TrackedOffer collectedOffer)
	{
		int inventoryCount = activeFlipTracker.getInventoryCountForItem(collectedOffer.getItemId());
		if (inventoryCount > 0)
		{
			log.info("Sell offer collected/modified for {}: {} items returned to inventory - keeping active flip tracking",
				collectedOffer.getItemName(), inventoryCount);
			session.addCollectedItem(collectedOffer.getItemId(), inventoryCount);
		}
		else
		{
			log.info("Sell offer for {} went empty with no items in inventory - dismissing active flip",
				collectedOffer.getItemName());
			activeFlipTracker.dismissFlip(collectedOffer.getItemId());
		}
	}

	private void handleEmptyBuyWithZeroFills(TrackedOffer collectedOffer)
	{
		int inventoryCount = activeFlipTracker.getInventoryCountForItem(collectedOffer.getItemId());
		if (inventoryCount > 0)
		{
			log.info("Buy order for {} went empty but found {} items in inventory - may have filled offline",
				collectedOffer.getItemName(), inventoryCount);
			session.addCollectedItem(collectedOffer.getItemId(), inventoryCount);
		}
	}

	private void ensureFallbackSellPrice(TrackedOffer collectedOffer)
	{
		if (collectedOffer.isBuy() && collectedOffer.getPreviousQuantitySold() > 0
			&& session.getRecommendedPrice(collectedOffer.getItemId()) == null)
		{
			int buyPrice = collectedOffer.getPrice();
			int fallbackSellPrice = (int) Math.ceil((buyPrice + 1) / 0.98);
			session.setRecommendedPrice(collectedOffer.getItemId(), fallbackSellPrice);
			log.info("No recommended sell price for {} - using fallback {} gp (bought at {} gp)",
				collectedOffer.getItemName(), fallbackSellPrice, buyPrice);
		}
	}

	private void notifyAutoRecommendCollection(TrackedOffer collectedOffer)
	{
		if (isAutoRecommendActive())
		{
			// Use the session's collected quantity (which accounts for offline fills)
			// rather than the tracked offer's stale previousQuantitySold
			int quantity = collectedOffer.getPreviousQuantitySold();
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
		TrackedOffer previousOffer = session.getTrackedOffer(ctx.slot);

		if (ctx.quantitySold > 0)
		{
			handleOfferWithFills(ctx, previousOffer);
		}
		else
		{
			handleNewOfferNoFills(ctx, previousOffer);
		}
	}

	private void handleOfferWithFills(OfferContext ctx, TrackedOffer previousOffer)
	{
		int newQuantity = calculateNewFillQuantity(ctx, previousOffer);

		if (newQuantity > 0)
		{
			recordFillTransaction(ctx, newQuantity);
		}

		// Reset adjustment timer on partial buy fills (not yet fully bought)
		if (ctx.isBuy && newQuantity > 0 && ctx.state != GrandExchangeOfferState.BOUGHT && isAutoRecommendActive())
		{
			autoRecommendService.resetAdjustmentTimer(ctx.itemId, ctx.price);
		}

		notifyAutoRecommendOnCompletion(ctx);

		session.putTrackedOffer(ctx.slot, TrackedOffer.createWithPreservedTimestamps(
			ctx.itemId, ctx.itemName, ctx.totalQuantity, ctx.price, ctx.quantitySold, previousOffer, ctx.state));

		if (previousOffer == null && ctx.totalQuantity > 0)
		{
			handleImmediateFillTransitions(ctx.itemId, ctx.itemName, ctx.isBuy);
		}
	}

	private int calculateNewFillQuantity(OfferContext ctx, TrackedOffer previousOffer)
	{
		if (previousOffer != null)
		{
			return ctx.quantitySold - previousOffer.getPreviousQuantitySold();
		}

		// First time seeing this offer with fills — immediate fill on placement
		preStoreImmediateFillSellPrice(ctx);
		return ctx.quantitySold;
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
			log.info("Immediate-fill buy for {} - pre-storing recommended sell price {}", ctx.itemName, currentRec.getRecommendedSellPrice());
			session.setRecommendedPrice(ctx.itemId, currentRec.getRecommendedSellPrice());
		}
	}

	private void recordFillTransaction(OfferContext ctx, int newQuantity)
	{
		int pricePerItem = ctx.spent / ctx.quantitySold;

		log.info("Recording transaction: {} {} x{} @ {} gp each (slot {}, {}/{} filled)",
			ctx.isBuy ? "BUY" : "SELL",
			ctx.itemName,
			newQuantity,
			pricePerItem,
			ctx.slot,
			ctx.quantitySold,
			ctx.totalQuantity);

		Integer recommendedSellPrice = ctx.isBuy ? session.getRecommendedPrice(ctx.itemId) : null;

		apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
			.builder(ctx.itemId, ctx.itemName, ctx.isBuy, newQuantity, pricePerItem)
			.geSlot(ctx.slot)
			.recommendedSellPrice(recommendedSellPrice)
			.rsn(getRsn().orElse(null))
			.totalQuantity(ctx.totalQuantity)
			.build());

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
			log.info("Immediate-fill buy for {} - advancing auto-recommend queue", itemName);
			autoRecommendService.onBuyOrderPlaced(itemId);
		}
		else if (!isBuy)
		{
			handleImmediateFillSell(itemId, itemName);
		}
	}

	private void handleImmediateFillSell(int itemId, String itemName)
	{
		log.info("Immediate-fill sell for {} - performing sell bookkeeping", itemName);
		String rsn = getRsn().orElse(null);
		if (rsn != null)
		{
			apiClient.markActiveFlipSellingAsync(itemId, rsn);
		}
		session.removeRecommendedPrice(itemId);
		if (isAutoRecommendActive())
		{
			autoRecommendService.onSellOrderPlaced(itemId);
		}
	}

	private void handleNewOfferNoFills(OfferContext ctx, TrackedOffer previousOffer)
	{
		session.putTrackedOffer(ctx.slot, new TrackedOffer(ctx.itemId, ctx.itemName, ctx.isBuy, ctx.totalQuantity, ctx.price, 0));

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

		Integer recommendedSellPrice = session.getRecommendedPrice(ctx.itemId);

		apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
			.builder(ctx.itemId, ctx.itemName, true, 0, ctx.price)
			.geSlot(ctx.slot)
			.recommendedSellPrice(recommendedSellPrice)
			.rsn(getRsn().orElse(null))
			.totalQuantity(ctx.totalQuantity)
			.build());
	}

	private void recordNewSellOrder(OfferContext ctx)
	{
		log.info("Sell order placed for {} x{} - marking active flip as selling", ctx.itemName, ctx.totalQuantity);
		String rsn = getRsn().orElse(null);
		if (rsn != null)
		{
			apiClient.markActiveFlipSellingAsync(ctx.itemId, rsn);
		}

		session.removeRecommendedPrice(ctx.itemId);

		if (isAutoRecommendActive())
		{
			autoRecommendService.onSellOrderPlaced(ctx.itemId);
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
		if (isAutoRecommendActive())
		{
			// overrideFocusForSell handles sell price recovery, quantity resolution
			// (including inventory fallback and auto-correction of session state)
			String itemName = ItemUtils.getItemName(itemManager, itemId);
			if (autoRecommendService.overrideFocusForSell(itemId, itemName))
			{
				return;
			}
			// Fall through to API path for items not in the auto-recommend queue
		}

		String rsn = getRsn().orElse(null);

		// Capture inventory count on client thread before async API call
		// (client.getItemContainer only works on the client thread)
		int inventoryCount = activeFlipTracker.getInventoryCountForItem(itemId);

		apiClient.getActiveFlipsAsync(rsn).thenAccept(response ->
		{
			if (response == null || response.getActiveFlips() == null)
			{
				return;
			}

			ActiveFlip matchingFlip = findActiveFlipForItem(response.getActiveFlips(), itemId);
			if (matchingFlip != null)
			{
				setFocusForSell(matchingFlip, inventoryCount);

				// Sync inventory-corrected quantity to API if inventory has more
				if (inventoryCount > matchingFlip.getTotalQuantity() && inventoryCount > 0 && rsn != null)
				{
					int orderQty = matchingFlip.getOrderQuantity() > 0
						? matchingFlip.getOrderQuantity() : inventoryCount;
					log.info("Syncing inventory-corrected quantity for {} to API: {} items",
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
			else
			{
				log.debug("No active flip found for item {} when setting up sell offer", itemId);
			}
		});
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

		FocusedFlip focus = FocusedFlip.forSell(
			flip.getItemId(),
			flip.getItemName(),
			sellPrice,
			sellQuantity
		);

		if (onFocusChanged != null)
		{
			onFocusChanged.accept(focus);
		}
		log.info("Auto-focused on active flip for sell: {} @ {} gp (qty: api={}, inv={}, using={})",
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
	// Utility
	// =====================

	private boolean isAutoRecommendActive()
	{
		return autoRecommendService != null && autoRecommendService.isActive();
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
