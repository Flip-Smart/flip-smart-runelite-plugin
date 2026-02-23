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
	public void handleOfferChanged(int slot, int itemId, String itemName, int quantitySold,
		int totalQuantity, int price, int spent, GrandExchangeOfferState state)
	{
		boolean isBuy = TrackedOffer.isBuyState(state);

		if (state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL)
		{
			handleCancelledOffer(slot, itemId, itemName, quantitySold, totalQuantity, price, spent, isBuy, state);
			return;
		}

		if (state == GrandExchangeOfferState.EMPTY)
		{
			handleEmptyOffer(slot);
			return;
		}

		handleActiveOffer(slot, itemId, itemName, quantitySold, totalQuantity, price, spent, isBuy, state);
	}

	// =====================
	// Cancelled Offers
	// =====================

	private void handleCancelledOffer(int slot, int itemId, String itemName, int quantitySold,
		int totalQuantity, int price, int spent, boolean isBuy, GrandExchangeOfferState state)
	{
		if (quantitySold > 0)
		{
			TrackedOffer previousOffer = session.getTrackedOffer(slot);

			if (previousOffer != null && quantitySold > previousOffer.getPreviousQuantitySold())
			{
				int newQuantity = quantitySold - previousOffer.getPreviousQuantitySold();
				int pricePerItem = spent / quantitySold;

				log.info("Recording final transaction before cancellation: {} {} x{}/{} @ {} gp each",
					isBuy ? "BUY" : "SELL",
					previousOffer.getItemName(),
					newQuantity,
					previousOffer.getTotalQuantity(),
					pricePerItem);

				Integer recommendedSellPrice = isBuy ? session.getRecommendedPrice(itemId) : null;

				apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
					.builder(itemId, previousOffer.getItemName(), isBuy, newQuantity, pricePerItem)
					.geSlot(slot)
					.recommendedSellPrice(recommendedSellPrice)
					.rsn(getRsn().orElse(null))
					.totalQuantity(previousOffer.getTotalQuantity())
					.build());
			}

			log.info("Order cancelled: {} {} - {} items filled out of {}",
				isBuy ? "BUY" : "SELL",
				previousOffer != null ? previousOffer.getItemName() : itemName,
				quantitySold,
				totalQuantity);
		}
		else
		{
			TrackedOffer previousOffer = session.getTrackedOffer(slot);
			log.info("Order cancelled with no fills: {} {}",
				isBuy ? "BUY" : "SELL",
				previousOffer != null ? previousOffer.getItemName() : itemName);

			if (isBuy)
			{
				log.info("Dismissing active flip for {} - buy order cancelled with 0 fills", itemName);
				apiClient.dismissActiveFlipAsync(itemId, getRsn().orElse(null));
				fireActiveFlipsRefresh();
			}
		}

		// For cancelled BUY orders with partial fills, track the items as collected
		if (isBuy && quantitySold > 0)
		{
			TrackedOffer cancelledOffer = session.getTrackedOffer(slot);
			log.info("Cancelled buy order had {} items filled (ordered {}) - syncing actual quantity and tracking until sold",
				quantitySold, cancelledOffer != null ? cancelledOffer.getTotalQuantity() : "?");
			session.addCollectedItem(itemId);

			String rsn = getRsn().orElse(null);
			if (rsn != null && cancelledOffer != null)
			{
				int pricePerItem = spent / quantitySold;
				log.info("Syncing cancelled order quantity to backend: {} x{} (was {})",
					cancelledOffer.getItemName(), quantitySold, cancelledOffer.getTotalQuantity());
				apiClient.syncActiveFlipAsync(
					itemId,
					cancelledOffer.getItemName(),
					quantitySold,
					quantitySold,
					pricePerItem,
					rsn
				);
			}

			fireActiveFlipsRefresh();
		}

		session.removeTrackedOffer(slot);
		session.removeRecommendedPrice(itemId);
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

		if (onPanelRefresh != null && oneShotScheduler != null)
		{
			oneShotScheduler.accept(TRANSACTION_REFRESH_DELAY_MS, () ->
				javax.swing.SwingUtilities.invokeLater(onPanelRefresh));
		}
	}

	private void handleCollectedBuyOffer(TrackedOffer collectedOffer)
	{
		log.info("Buy offer collected from GE: {} x{} - tracking until sold",
			collectedOffer.getItemName(), collectedOffer.getPreviousQuantitySold());
		session.addCollectedItem(collectedOffer.getItemId());

		int inventoryCount = activeFlipTracker.getInventoryCountForItem(collectedOffer.getItemId());
		int trackedFills = collectedOffer.getPreviousQuantitySold();

		if (inventoryCount > trackedFills)
		{
			log.info("Order for {} may have completed offline - tracked {} fills but have {} in inventory. Syncing.",
				collectedOffer.getItemName(), trackedFills, inventoryCount);

			String rsn = getRsn().orElse(null);
			if (rsn != null)
			{
				int actualFills = Math.min(inventoryCount, collectedOffer.getTotalQuantity());
				apiClient.syncActiveFlipAsync(
					collectedOffer.getItemId(),
					collectedOffer.getItemName(),
					actualFills,
					collectedOffer.getTotalQuantity(),
					collectedOffer.getPrice(),
					rsn
				);
			}
		}
	}

	private void handleCollectedSellOffer(TrackedOffer collectedOffer)
	{
		int inventoryCount = activeFlipTracker.getInventoryCountForItem(collectedOffer.getItemId());
		if (inventoryCount > 0)
		{
			log.info("Sell offer collected/modified for {}: {} items returned to inventory - keeping active flip tracking",
				collectedOffer.getItemName(), inventoryCount);
			session.addCollectedItem(collectedOffer.getItemId());
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
			session.addCollectedItem(collectedOffer.getItemId());
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
		if (autoRecommendService != null && autoRecommendService.isActive())
		{
			autoRecommendService.onOfferCollected(
				collectedOffer.getItemId(),
				collectedOffer.isBuy(),
				collectedOffer.getItemName(),
				collectedOffer.getPreviousQuantitySold()
			);
		}
	}

	// =====================
	// Active Offers (fills, new orders)
	// =====================

	private void handleActiveOffer(int slot, int itemId, String itemName, int quantitySold,
		int totalQuantity, int price, int spent, boolean isBuy, GrandExchangeOfferState state)
	{
		TrackedOffer previousOffer = session.getTrackedOffer(slot);

		if (quantitySold > 0)
		{
			handleOfferWithFills(slot, itemId, itemName, quantitySold, totalQuantity, price, spent,
				isBuy, state, previousOffer);
		}
		else
		{
			handleNewOfferNoFills(slot, itemId, itemName, totalQuantity, price, isBuy, previousOffer);
		}
	}

	private void handleOfferWithFills(int slot, int itemId, String itemName, int quantitySold,
		int totalQuantity, int price, int spent, boolean isBuy, GrandExchangeOfferState state,
		TrackedOffer previousOffer)
	{
		int newQuantity = 0;

		if (previousOffer != null)
		{
			newQuantity = quantitySold - previousOffer.getPreviousQuantitySold();
		}
		else
		{
			newQuantity = quantitySold;

			if (isBuy && totalQuantity > 0 && autoRecommendService != null && autoRecommendService.isActive())
			{
				FlipRecommendation currentRec = autoRecommendService.getCurrentRecommendation();
				if (currentRec != null && currentRec.getItemId() == itemId && currentRec.getRecommendedSellPrice() > 0)
				{
					log.info("Immediate-fill buy for {} - pre-storing recommended sell price {}", itemName, currentRec.getRecommendedSellPrice());
					session.setRecommendedPrice(itemId, currentRec.getRecommendedSellPrice());
				}
			}
		}

		if (newQuantity > 0)
		{
			int pricePerItem = spent / quantitySold;

			log.info("Recording transaction: {} {} x{} @ {} gp each (slot {}, {}/{} filled)",
				isBuy ? "BUY" : "SELL",
				itemName,
				newQuantity,
				pricePerItem,
				slot,
				quantitySold,
				totalQuantity);

			Integer recommendedSellPrice = isBuy ? session.getRecommendedPrice(itemId) : null;

			apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
				.builder(itemId, itemName, isBuy, newQuantity, pricePerItem)
				.geSlot(slot)
				.recommendedSellPrice(recommendedSellPrice)
				.rsn(getRsn().orElse(null))
				.totalQuantity(totalQuantity)
				.build());

			if (!isBuy)
			{
				activeFlipTracker.markItemSold(itemId);
			}

			if (onPanelRefresh != null && oneShotScheduler != null)
			{
				oneShotScheduler.accept(TRANSACTION_REFRESH_DELAY_MS, () ->
					javax.swing.SwingUtilities.invokeLater(onPanelRefresh));
			}
		}

		if (state == GrandExchangeOfferState.BOUGHT
			&& isBuy && autoRecommendService != null && autoRecommendService.isActive())
		{
			autoRecommendService.onBuyOrderCompleted(itemId, itemName);
		}
		if (state == GrandExchangeOfferState.SOLD
			&& !isBuy && autoRecommendService != null && autoRecommendService.isActive())
		{
			autoRecommendService.onSellOrderCompleted(itemId);
		}

		session.putTrackedOffer(slot, TrackedOffer.createWithPreservedTimestamps(
			itemId, itemName, totalQuantity, price, quantitySold, previousOffer, state));

		if (previousOffer == null && totalQuantity > 0)
		{
			handleImmediateFillTransitions(itemId, itemName, isBuy);
		}
	}

	private void handleImmediateFillTransitions(int itemId, String itemName, boolean isBuy)
	{
		if (isBuy && autoRecommendService != null && autoRecommendService.isActive())
		{
			log.info("Immediate-fill buy for {} - advancing auto-recommend queue", itemName);
			autoRecommendService.onBuyOrderPlaced(itemId);
		}
		else if (!isBuy)
		{
			log.info("Immediate-fill sell for {} - performing sell bookkeeping", itemName);
			String rsn = getRsn().orElse(null);
			if (rsn != null)
			{
				apiClient.markActiveFlipSellingAsync(itemId, rsn);
			}
			session.removeRecommendedPrice(itemId);
			if (autoRecommendService != null && autoRecommendService.isActive())
			{
				autoRecommendService.onSellOrderPlaced(itemId);
			}
		}
	}

	private void handleNewOfferNoFills(int slot, int itemId, String itemName,
		int totalQuantity, int price, boolean isBuy, TrackedOffer previousOffer)
	{
		session.putTrackedOffer(slot, new TrackedOffer(itemId, itemName, isBuy, totalQuantity, price, 0));

		clearFlipAssistFocusIfMatches(itemId, isBuy);

		if (isBuy && totalQuantity > 0 && previousOffer == null)
		{
			log.debug("Recording new buy order: {} x{} @ {} gp each (slot {}, 0/{} filled)",
				itemName, 0, price, slot, totalQuantity);

			if (autoRecommendService != null && autoRecommendService.isActive())
			{
				autoRecommendService.onBuyOrderPlaced(itemId);
			}

			Integer recommendedSellPrice = session.getRecommendedPrice(itemId);

			apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
				.builder(itemId, itemName, true, 0, price)
				.geSlot(slot)
				.recommendedSellPrice(recommendedSellPrice)
				.rsn(getRsn().orElse(null))
				.totalQuantity(totalQuantity)
				.build());
		}

		if (!isBuy && totalQuantity > 0 && previousOffer == null)
		{
			log.info("Sell order placed for {} x{} - marking active flip as selling", itemName, totalQuantity);
			String rsn = getRsn().orElse(null);
			if (rsn != null)
			{
				apiClient.markActiveFlipSellingAsync(itemId, rsn);
			}

			session.removeRecommendedPrice(itemId);

			if (autoRecommendService != null && autoRecommendService.isActive())
			{
				autoRecommendService.onSellOrderPlaced(itemId);
			}
		}

		if (previousOffer == null && onPendingOrdersUpdate != null && onActiveFlipsRefresh != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> {
				if (onPendingOrdersUpdate != null) onPendingOrdersUpdate.accept(null);
				if (onActiveFlipsRefresh != null) onActiveFlipsRefresh.run();
			});
		}
	}

	// =====================
	// Flip Assist Focus
	// =====================

	/**
	 * Auto-focus on an active flip when the player sets up a sell offer for that item.
	 */
	public void autoFocusOnActiveFlip(int itemId)
	{
		if (autoRecommendService != null && autoRecommendService.isActive())
		{
			return;
		}

		String rsn = getRsn().orElse(null);

		apiClient.getActiveFlipsAsync(rsn).thenAccept(response ->
		{
			if (response == null || response.getActiveFlips() == null)
			{
				return;
			}

			ActiveFlip matchingFlip = findActiveFlipForItem(response.getActiveFlips(), itemId);
			if (matchingFlip != null)
			{
				setFocusForSell(matchingFlip);
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

	private void setFocusForSell(ActiveFlip flip)
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

		FocusedFlip focus = FocusedFlip.forSell(
			flip.getItemId(),
			flip.getItemName(),
			sellPrice,
			flip.getTotalQuantity()
		);

		if (onFocusChanged != null)
		{
			onFocusChanged.accept(focus);
		}
		log.info("Auto-focused on active flip for sell: {} @ {} gp", flip.getItemName(), sellPrice);
	}

	/**
	 * Clear the Flip Assist focus if the submitted order matches the focused item.
	 */
	public void clearFlipAssistFocusIfMatches(int itemId, boolean isBuy)
	{
		if (autoRecommendService != null && autoRecommendService.isActive())
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
}
