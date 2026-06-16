package com.flipsmart.plugin;

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
		ActiveFlipTracker activeFlipTracker)
	{
		offlineSyncService.setOnSyncComplete(plugin::schedulePostSyncTasks);
		activeFlipTracker.setOnPanelRefreshNeeded(() -> { if (plugin.getFlipFinderPanel() != null) plugin.getFlipFinderPanel().refresh(); });
		activeFlipTracker.setOnActiveFlipsRefreshNeeded(() -> { if (plugin.getFlipFinderPanel() != null) plugin.getFlipFinderPanel().refreshActiveFlips(); });
	}

	/**
	 * Initialize auto-recommend service and wire its callbacks.
	 * (formerly FlipSmartPlugin.initializeAutoRecommendService)
	 *
	 * @return the constructed AutoRecommendService
	 */
	public AutoRecommendService initializeAutoRecommendService(FlipSmartPlugin plugin, FlipSmartConfig config,
		FlipAssistOverlay flipAssistOverlay, GrandExchangeSlotOverlay geSlotOverlay)
	{
		AutoRecommendService autoRecommendService = new AutoRecommendService(config, plugin);
		autoRecommendService.setOnFocusChanged(plugin::handleAutoRecommendFocusChanged);
		autoRecommendService.setOnOverlayMessageChanged(flipAssistOverlay::setAutoStatusMessage);
		autoRecommendService.setDisplayedSellPriceProvider(itemId -> plugin.getFlipFinderPanel() != null ? plugin.getFlipFinderPanel().getDisplayedSellPrice(itemId) : null);
		autoRecommendService.setOnStaleOfferPrompted(plugin::highlightSlotForItem);
		autoRecommendService.setOnClearAllHighlights(geSlotOverlay::clearAllAdjustmentHighlights);
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
			itemId -> plugin.handleActiveOfferHandoff(itemId),
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
		GrandExchangeTracker grandExchangeTracker, ActiveOfferAdvisorService activeOfferAdvisorService)
	{
		ManualAdjustmentTracker manualAdjustmentTracker = new ManualAdjustmentTracker(plugin.getApiClient(), config);

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
	 * Wire all GrandExchangeTracker callbacks.
	 * (formerly FlipSmartPlugin.wireGrandExchangeTrackerCallbacks)
	 */
	public void wireGrandExchangeTrackerCallbacks(FlipSmartPlugin plugin, GrandExchangeTracker grandExchangeTracker,
		AutoRecommendService autoRecommendService, GEHistoryService geHistoryService)
	{
		grandExchangeTracker.setAutoRecommendService(autoRecommendService);
		grandExchangeTracker.setRsnSupplier(plugin::getCurrentRsnSafe);
		grandExchangeTracker.setOnPanelRefresh(() -> { if (plugin.getFlipFinderPanel() != null) plugin.getFlipFinderPanel().refresh(); });
		grandExchangeTracker.setOnActiveFlipsRefresh(() -> { if (plugin.getFlipFinderPanel() != null) { plugin.getFlipFinderPanel().refreshActiveFlips(); plugin.getFlipFinderPanel().reevaluateSlotLimitDisplay(); } plugin.maybeEventPollAdvisor(); });
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
