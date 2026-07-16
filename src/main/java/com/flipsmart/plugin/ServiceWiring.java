package com.flipsmart.plugin;

import com.flipsmart.ActionAlertNotifier;
import com.flipsmart.ActiveFlipTracker;
import com.flipsmart.ActiveOfferAdvisorService;
import com.flipsmart.AutoRecommendService;
import com.flipsmart.FlipAssistOverlay;
import com.flipsmart.FlipSmartConfig;
import com.flipsmart.FlipSmartPlugin;
import com.flipsmart.GEHistoryService;
import com.flipsmart.GrandExchangeOverlay;
import com.flipsmart.GrandExchangeSlotOverlay;
import com.flipsmart.InventoryHighlightOverlay;
import com.flipsmart.ManualAdjustmentTracker;
import com.flipsmart.OfflineSyncService;
import com.flipsmart.PlayerSession;
import com.flipsmart.GrandExchangeTracker;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.trading.RoundTripLedger;
import com.flipsmart.trading.TransactionLogger;
import net.runelite.client.Notifier;
import net.runelite.client.ui.ClientUI;

import javax.swing.SwingUtilities;

/**
 * Performs the post-construction setter-callback wiring for the FlipSmart
 * collaborators during startUp().
 *
 * The exact set and order of setter calls and lambda wiring is preserved from
 * when this logic lived directly on {@link FlipSmartPlugin}. The lambdas route
 * plugin-private behavior back through the plugin instance, which exposes the
 * required hooks as package-visible/public methods.
 */
public class ServiceWiring
{
	/**
	 * Wire callbacks for offline sync and active flip tracker services.
	 * (formerly FlipSmartPlugin.wireServiceCallbacks)
	 */
	public void wireServiceCallbacks(FlipSmartPlugin plugin, OfflineSyncService offlineSyncService,
		ActiveFlipTracker activeFlipTracker, PanelRefreshCoalescer refreshCoalescer)
	{
		offlineSyncService.setOnSyncComplete(plugin::schedulePostSyncTasks);
		activeFlipTracker.setOnPanelRefreshNeeded(() -> refreshCoalescer.request(true));
		activeFlipTracker.setOnActiveFlipsRefreshNeeded(() -> refreshCoalescer.request(false));
	}

	/**
	 * Initialize auto-recommend service and wire its callbacks.
	 * (formerly FlipSmartPlugin.initializeAutoRecommendService)
	 *
	 * @return the constructed AutoRecommendService
	 */
	public AutoRecommendService initializeAutoRecommendService(FlipSmartPlugin plugin, FlipSmartConfig config,
		FlipAssistOverlay flipAssistOverlay, GrandExchangeSlotOverlay geSlotOverlay, OfferStore offerStore,
		Notifier notifier, ClientUI clientUI)
	{
		AutoRecommendService autoRecommendService = new AutoRecommendService(config, plugin, offerStore);
		ActionAlertNotifier actionAlerts = new ActionAlertNotifier(notifier, config, plugin::getItemName,
			clientUI::isFocused);
		autoRecommendService.setOnActionAlert(actionAlerts::onDecision);
		autoRecommendService.setOnFocusChanged(plugin::handleAutoRecommendFocusChanged);
		autoRecommendService.setOnOverlayMessageChanged(flipAssistOverlay::setAutoStatusMessage);
		autoRecommendService.setDisplayedSellPriceProvider(itemId -> plugin.getFlipFinderPanel() != null ? plugin.getFlipFinderPanel().getDisplayedSellPrice(itemId) : null);
		autoRecommendService.setOnHighlightItemSlot(plugin::highlightSlotForItem);
		autoRecommendService.setOnClearAllHighlights(geSlotOverlay::clearAllAdjustmentHighlights);
		autoRecommendService.setOnStickyHighlight(geSlotOverlay::setStickyAdjustmentHighlight);
		autoRecommendService.setOnClearStickyHighlight(geSlotOverlay::clearStickyAdjustmentHighlight);
		autoRecommendService.setOnResetAllHighlights(geSlotOverlay::resetAllHighlights);
		flipAssistOverlay.setOnStepChanged(autoRecommendService::onOverlayStepChanged);
		return autoRecommendService;
	}

	/**
	 * Construct the active-offer advisor service and wire its callbacks. The
	 * 30-second poll timer is started separately via the scheduler.
	 * (formerly FlipSmartPlugin.initializeActiveOfferAdvisor, minus the timer)
	 *
	 * @return the constructed ActiveOfferAdvisorService
	 */
	public ActiveOfferAdvisorService initializeActiveOfferAdvisor(FlipSmartPlugin plugin)
	{
		ActiveOfferAdvisorService activeOfferAdvisorService = new ActiveOfferAdvisorService();
		activeOfferAdvisorService.setCallbacks(
			resp -> SwingUtilities.invokeLater(() -> plugin.applyActiveOfferSurface(resp)),
			resp -> SwingUtilities.invokeLater(() -> plugin.handleActiveOfferHandoff(resp)),
			itemId -> SwingUtilities.invokeLater(() -> plugin.clearActiveOfferSurface(itemId)));
		return activeOfferAdvisorService;
	}

	/**
	 * Initialize manual adjustment tracker for staleness detection on manual flips.
	 * (formerly FlipSmartPlugin.initializeManualAdjustmentTracker)
	 *
	 * @return the constructed ManualAdjustmentTracker
	 */
	public ManualAdjustmentTracker initializeManualAdjustmentTracker(FlipSmartPlugin plugin, FlipSmartConfig config,
		FlipAssistOverlay flipAssistOverlay, GrandExchangeSlotOverlay geSlotOverlay,
		InventoryHighlightOverlay inventoryHighlightOverlay, PlayerSession session,
		GrandExchangeTracker grandExchangeTracker, ActiveOfferAdvisorService activeOfferAdvisorService,
		OfferStore offerStore)
	{
		ManualAdjustmentTracker manualAdjustmentTracker = new ManualAdjustmentTracker(plugin.getApiClient(), config, offerStore);

		manualAdjustmentTracker.setOnAdjustmentPrompt(flipAssistOverlay::setAutoStatusMessage);

		manualAdjustmentTracker.setOnFocusFlip((focus, statusMsg) ->
		{
			flipAssistOverlay.setFocusedFlip(focus);
			flipAssistOverlay.setAutoStatusMessage(statusMsg, focus.getItemId());
			plugin.updateInventoryHighlightForFocus(focus);
		});

		manualAdjustmentTracker.setOnHighlightSlot((slot, recommendedPrice) ->
			geSlotOverlay.setAdjustmentHighlight(slot, recommendedPrice));
		manualAdjustmentTracker.setOnClearHighlight(geSlotOverlay::clearAdjustmentHighlight);

		manualAdjustmentTracker.setOnHighlightInventoryItem(inventoryHighlightOverlay::addHighlight);
		manualAdjustmentTracker.setOnClearInventoryItem(inventoryHighlightOverlay::removeHighlight);
		manualAdjustmentTracker.setOnSellPriceAdjusted((itemId, price) ->
		{
			if (session != null)
			{
				session.setRecommendedPrice(itemId, price);
			}
		});

		manualAdjustmentTracker.setCashStackSupplier(() ->
			session.getCurrentCashStack() > 0 ? session.getCurrentCashStack() : null);
		manualAdjustmentTracker.setRsnSupplier(() -> plugin.getCurrentRsnSafe().orElse(null));
		manualAdjustmentTracker.setFilledSlotsSupplier(plugin::getFilledGESlotCount);
		manualAdjustmentTracker.setMembersWorldSupplier(plugin::isMembersWorld);

		grandExchangeTracker.setManualAdjustmentTracker(manualAdjustmentTracker);
		grandExchangeTracker.setActiveOfferAdvisorService(activeOfferAdvisorService);
		grandExchangeTracker.setAdjustmentPromptsEnabled(config::showAdjustmentPrompts);
		grandExchangeTracker.setConfig(config);

		return manualAdjustmentTracker;
	}

	/**
	 * Register the single transaction-recording listener on the shared OfferStore.
	 *
	 * The logger uses the SAME rsn supplier the tracker was wired with
	 * ({@code plugin::getCurrentRsnSafe}) so recorded RSN is identical to the old inline
	 * recording path. It must subscribe to the same OfferStore instance the tracker writes
	 * to, so every state change the tracker applies is recorded exactly once.
	 */
	public void wireTransactionLogger(FlipSmartPlugin plugin, PlayerSession session, OfferStore offerStore,
		RoundTripLedger roundTripLedger)
	{
		TransactionLogger logger = new TransactionLogger(
			plugin.getApiClient(), session, plugin::getCurrentRsnSafe, roundTripLedger);
		offerStore.addListener(logger::onOfferEvent);
	}

	/**
	 * Wire all GrandExchangeTracker callbacks.
	 * (formerly FlipSmartPlugin.wireGrandExchangeTrackerCallbacks)
	 *
	 * API-backed refreshes triggered by GE offer events route through the
	 * {@link PanelRefreshCoalescer} so a burst produces one refresh per window.
	 * Immediate responsiveness comes from the OfferStore listener registered
	 * here, which redisplays the Active Flips tab from local state on the EDT.
	 */
	public void wireGrandExchangeTrackerCallbacks(FlipSmartPlugin plugin, GrandExchangeTracker grandExchangeTracker,
		AutoRecommendService autoRecommendService, GEHistoryService geHistoryService,
		OfferStore offerStore, PanelRefreshCoalescer refreshCoalescer)
	{
		grandExchangeTracker.setAutoRecommendService(autoRecommendService);
		grandExchangeTracker.setRsnSupplier(plugin::getCurrentRsnSafe);
		grandExchangeTracker.setOnPanelRefresh(() -> refreshCoalescer.request(true));
		grandExchangeTracker.setOnActiveFlipsRefresh(() -> { if (plugin.getFlipFinderPanel() != null) plugin.getFlipFinderPanel().reevaluateSlotLimitDisplay(); plugin.maybeEventPollAdvisor(); refreshCoalescer.request(false); });
		offerStore.addListener(event -> SwingUtilities.invokeLater(() -> {
			if (plugin.getFlipFinderPanel() != null)
			{
				plugin.getFlipFinderPanel().applyLocalOfferEvent(event);
			}
		}));
		grandExchangeTracker.setOnPendingOrdersUpdate(orders -> { if (plugin.getFlipFinderPanel() != null) plugin.getFlipFinderPanel().updatePendingOrders(plugin.getPendingBuyOrders()); });
		grandExchangeTracker.setOnFocusChanged(plugin::handleGETrackerFocusChanged);
		grandExchangeTracker.setOnFocusClear(plugin::handleGETrackerFocusClear);
		grandExchangeTracker.setDisplayedSellPriceProvider(itemId -> plugin.getFlipFinderPanel() != null ? plugin.getFlipFinderPanel().getDisplayedSellPrice(itemId) : null);
		grandExchangeTracker.setOneShotScheduler(plugin::scheduleOneShot);

		geHistoryService.setOnBackfillComplete(() -> {
			if (plugin.getFlipFinderPanel() != null)
			{
				SwingUtilities.invokeLater(plugin.getFlipFinderPanel()::refresh);
			}
		});
	}
}
