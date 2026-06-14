package com.flipsmart;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@PluginDescriptor(
	name = "FlipSmart",
	description = "A tool to help with item flipping in the Grand Exchange",
	tags = {"grand exchange", "flipping", "trading", "money making"}
)
public class FlipSmartPlugin extends Plugin
{
	private static final int INVENTORY_CONTAINER_ID = 93;
	private static final int COINS_ITEM_ID = 995;
	private static final int GE_REGION_ID = 12598;

	@Inject
	private Client client;

	@Inject
	private FlipSmartConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private GrandExchangeOverlay geOverlay;

	@Inject
	private GrandExchangeSlotOverlay geSlotOverlay;

	@Inject
	private FlipAssistOverlay flipAssistOverlay;

	@Inject
	private InventoryHighlightOverlay inventoryHighlightOverlay;

	@Inject
	private FlipSmartApiClient apiClient;
	
	@Inject
	private KeyManager keyManager;
	
	@Inject
	private ClientThread clientThread;

	@Inject
	private net.runelite.client.ui.ClientToolbar clientToolbar;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private DumpAlertService dumpAlertService;

	@Inject
	private MotdService motdService;

	@Inject
	private OfflineSyncService offlineSyncService;

	@Inject
	private BankSnapshotService bankSnapshotService;

	@Inject
	private ActiveFlipTracker activeFlipTracker;

	@Inject
	private GrandExchangeTracker grandExchangeTracker;

	@Inject
	private WebhookSyncService webhookSyncService;

	@Inject
	private GEHistoryService geHistoryService;

	@Inject
	private GeOfferDescriptionService geOfferDescriptionService;

	@Inject
	private TradeStationSlotPushService tradeStationSlotPushService;

	@Inject
	private Gson gson;

	// Flip Finder panel
	private FlipFinderPanel flipFinderPanel;
	private net.runelite.client.ui.NavigationButton flipFinderNavButton;

	// Auto-recommend service
	@Getter
	private AutoRecommendService autoRecommendService;

	// Manual flip adjustment tracker (API-based staleness detection)
	private ManualAdjustmentTracker manualAdjustmentTracker;

	// Centralized session state management (provided via @Provides @Singleton)
	@Inject
	private PlayerSession session;

	// Auto-refresh timer for flip finder
	private java.util.Timer flipFinderRefreshTimer;

	// Auto-recommend refresh timer (2-minute cycle)
	private java.util.Timer autoRecommendRefreshTimer;

	// Active-offer advisor service + poll timer (30-second cycle)
	private ActiveOfferAdvisorService activeOfferAdvisorService;
	private java.util.Timer activeOfferAdvisorTimer;

	// Track all active one-shot Swing timers for cleanup on shutdown
	private final List<javax.swing.Timer> activeOneShotTimers = new CopyOnWriteArrayList<>();

	// Last known RSN — saved when we learn it, used as fallback for persistence on shutdown
	// when session.getRsn() may already be null
	private volatile String lastKnownRsn;

	// True when handleLoggedInState ran but the local player was not yet
	// populated, so syncRSN couldn't capture the current account's name. The
	// onGameTick handler retries until it succeeds. Issue #556.
	private volatile boolean rsnSyncPending;

	// Cached world-type flag — updated on the client thread (WorldChanged, login) and read
	// from any thread (Swing EDT, scheduler). Defaults to true so unlinked callers see more items.
	private volatile boolean membersWorld = true;

	// Cached GE location flag — updated each game tick on the client thread.
	private volatile boolean atGrandExchange = false;

	// Track login to avoid recording existing offers as new transactions
	private static final int GE_LOGIN_BURST_WINDOW = 3; // ticks

	// Config keys for persisting state
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String UNKNOWN_RSN_FALLBACK = "unknown";
	private static final String AUTO_RECOMMEND_STATE_KEY_PREFIX = "autoRecommendState_";
	private static final String LAST_KNOWN_RSN_KEY = "lastKnownRsn";

	/** Auto-recommend queue refresh interval (2 minutes) */
	private static final long AUTO_RECOMMEND_REFRESH_INTERVAL_MS = 2 * 60 * 1000L;

	/** Active-offer advisor poll interval (30 seconds) */
	private static final long ACTIVE_OFFER_ADVISOR_INTERVAL_MS = 30_000L;

	// Flip Assist input listener for hotkey handling
	private FlipAssistInputListener flipAssistInputListener;

	// Injects the clickable "FlipSmart item" shortcut into GE search results
	private GeSearchSuggestion geSearchSuggestion;

	// Timer delay constants (in milliseconds)
	/** Delay before syncing offline fills after login */
	private static final int OFFLINE_SYNC_DELAY_MS = 2000;
	/** Delay before refreshing panel after sync */
	private static final int PANEL_REFRESH_DELAY_MS = 1000;
	/** Delay before cleaning up stale flips (allows GE state to stabilize) */
	private static final int STALE_FLIP_CLEANUP_DELAY_MS = 15000;
	/** Delay before validating inventory quantities */
	private static final int INVENTORY_VALIDATION_DELAY_MS = 2000;
	/** Delay before re-evaluating auto-recommend after login sync */
	private static final int AUTO_RECOMMEND_REEVALUATE_DELAY_MS = 3000;

	// Threshold constants
	/** Minimum interval between auto-refreshes (30 seconds) */
	private static final long AUTO_REFRESH_MIN_INTERVAL_MS = 30_000;
	/** Minimum cash stack to trigger auto-refresh on change */
	private static final int AUTO_REFRESH_CASH_THRESHOLD = 100_000;

	/**
	 * Enum representing offer competitiveness relative to Wiki prices
	 */
	public enum OfferCompetitiveness
	{
		COMPETITIVE,      // Green checkmark - price is at or better than Wiki price
		UNCOMPETITIVE,    // Red X - price is worse than Wiki price
		UNKNOWN           // Gray ? - Wiki price unavailable
	}

	
	/**
	 * Get the centralized player session state.
	 */
	public PlayerSession getSession()
	{
		return session;
	}

	public FlipSmartApiClient getApiClient()
	{
		return apiClient;
	}

	/**
	 * Get current RSN (delegates to session for backwards compatibility).
	 */
	public String getCurrentRsn()
	{
		return session.getRsn();
	}

	/**
	 * Get current cash stack (delegates to session for backwards compatibility).
	 */
	public int getCurrentCashStack()
	{
		return session.getCurrentCashStack();
	}

	/**
	 * Check if player is logged into RuneScape (delegates to session for backwards compatibility).
	 */
	public boolean isLoggedIntoRunescape()
	{
		return session.isLoggedIntoRunescape();
	}

	/**
	 * Check if the current user has premium subscription.
	 */
	public boolean isPremium()
	{
		return apiClient.isPremium();
	}

	/**
	 * Returns true if recommendations should include members items.
	 * Returns false when on an F2P world, or when F2P Mode is enabled in config.
	 *
	 * Reads a cached value updated on the client thread to avoid off-thread Client API access.
	 */
	public boolean isMembersWorld()
	{
		if (config.f2pMode())
		{
			return false;
		}
		return membersWorld;
	}

	public boolean isAtGrandExchange()
	{
		return atGrandExchange;
	}

	/**
	 * Refresh the cached members-world state from the Client API.
	 * Must be called on the client thread.
	 */
	private void updateMembersWorldCache()
	{
		membersWorld = client.getWorldType().contains(WorldType.MEMBERS);
	}

	public int getFlipSlotLimit()
	{
		return isPremium() ? 8 : 2;
	}

	public List<ActiveFlip> getCurrentActiveFlips()
	{
		return flipFinderPanel != null ? flipFinderPanel.getCurrentActiveFlips() : null;
	}

	public boolean isAutoRecommendActive()
	{
		return autoRecommendService != null && autoRecommendService.isActive();
	}

	public String getAutoRecommendOverlayMessage()
	{
		return autoRecommendService != null ? autoRecommendService.getLastOverlayMessage() : null;
	}

	public FlipAssistOverlay.FlipAssistStep getFlipAssistOverlayStep()
	{
		return flipAssistOverlay != null ? flipAssistOverlay.getCurrentStep() : FlipAssistOverlay.FlipAssistStep.SELECT_ITEM;
	}

	/**
	 * Count the number of GE slots that are currently occupied (non-EMPTY) in the game.
	 * Returns the flip slot limit if GE offers are not yet available (conservative).
	 */
	public int getFilledGESlotCount()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return getFlipSlotLimit();
		}
		int count = 0;
		for (GrandExchangeOffer offer : offers)
		{
			if (offer.getState() != GrandExchangeOfferState.EMPTY)
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Get the inventory count for a specific item (delegate to ActiveFlipTracker).
	 */
	public int getInventoryCountForItem(int itemId)
	{
		return activeFlipTracker.getInventoryCountForItem(itemId);
	}

	public String getItemName(int itemId)
	{
		return ItemUtils.getItemName(itemManager, itemId);
	}

	/**
	 * Store recommended sell price when user views/acts on a flip recommendation
	 */
	public void setRecommendedSellPrice(int itemId, int recommendedSellPrice)
	{
		session.setRecommendedPrice(itemId, recommendedSellPrice);
	}

	/**
	 * Get tracked offer for a specific GE slot (for overlay access)
	 */
	public TrackedOffer getTrackedOffer(int slot)
	{
		return session.getTrackedOffer(slot);
	}

	/**
	 * Get the current flip assist step for GE button highlighting
	 */
	public FlipAssistOverlay.FlipAssistStep getFlipAssistStep()
	{
		if (flipAssistOverlay == null || flipAssistOverlay.getFocusedFlip() == null)
		{
			return null;
		}
		return flipAssistOverlay.getCurrentStep();
	}

	/**
	 * Check if flip assist is active with a focused flip
	 */
	public boolean isFlipAssistActive()
	{
		return flipAssistOverlay != null && flipAssistOverlay.getFocusedFlip() != null;
	}

	/**
	 * Calculate competitiveness of an offer compared to real-time wiki prices.
	 *
	 * For BUY offers: competitive if player price >= insta-sell (low) price
	 * For SELL offers: competitive if player price <= insta-buy (high) price
	 *
	 * This shows if your offer is "in the margin" and likely to fill.
	 */
	public OfferCompetitiveness calculateCompetitiveness(TrackedOffer offer)
	{
		if (offer == null)
		{
			return OfferCompetitiveness.UNKNOWN;
		}

		// Try to get real-time wiki prices first
		FlipSmartApiClient.WikiPrice wikiPrice = apiClient.getWikiPrice(offer.getItemId());

		if (wikiPrice != null)
		{
			int targetPrice = offer.isBuy() ? wikiPrice.instaSell : wikiPrice.instaBuy;
			return compareOfferPrice(offer.getPrice(), targetPrice, offer.isBuy());
		}

		// Fallback to GE guide price if real-time prices unavailable.
		// getItemPrice requires the client thread — return UNKNOWN if called off-thread.
		if (!client.isClientThread())
		{
			return OfferCompetitiveness.UNKNOWN;
		}
		int guidePrice = itemManager.getItemPrice(offer.getItemId());
		if (guidePrice <= 0)
		{
			return OfferCompetitiveness.UNKNOWN;
		}

		return compareOfferPrice(offer.getPrice(), guidePrice, offer.isBuy());
	}

	/**
	 * Compare offer price against target price to determine competitiveness.
	 * Buy offers are competitive if price >= target, sell offers if price <= target.
	 */
	private OfferCompetitiveness compareOfferPrice(int offerPrice, int targetPrice, boolean isBuy)
	{
		boolean isCompetitive = isBuy ? offerPrice >= targetPrice : offerPrice <= targetPrice;
		return isCompetitive ? OfferCompetitiveness.COMPETITIVE : OfferCompetitiveness.UNCOMPETITIVE;
	}

	/**
	 * Get real-time wiki price for an item (for tooltip display)
	 */
	public FlipSmartApiClient.WikiPrice getWikiPrice(int itemId)
	{
		return apiClient.getWikiPrice(itemId);
	}

	/**
	 * Trigger a refresh of wiki prices if needed
	 */
	public void refreshWikiPrices()
	{
		if (apiClient.needsWikiPriceRefresh())
		{
			apiClient.fetchWikiPrices();
		}
	}

	/**
	 * Get current buy orders in GE slots (pending or partially filled).
	 * These are buy orders that haven't been fully collected yet.
	 */
	public java.util.List<PendingOrder> getPendingBuyOrders()
	{
		java.util.List<PendingOrder> pendingOrders = new java.util.ArrayList<>();
		
		for (java.util.Map.Entry<Integer, TrackedOffer> entry : session.getTrackedOffers().entrySet())
		{
			TrackedOffer offer = entry.getValue();
			
			// Include all buy orders (pending or partially filled)
			if (offer.isBuy())
			{
				Integer recommendedSellPrice = session.getRecommendedPrice(offer.getItemId());
				
				PendingOrder pending = new PendingOrder(
					offer.getItemId(),
					offer.getItemName(),
					offer.getTotalQuantity(),
					offer.getPreviousQuantitySold(), // How many filled so far
					offer.getPrice(),
					recommendedSellPrice,
					entry.getKey() // slot
				);
				
				pendingOrders.add(pending);
			}
		}
		
		return pendingOrders;
	}
	
	/**
	 * Get the set of item IDs currently in GE buy slots.
	 */
	public java.util.Set<Integer> getCurrentGEBuyItemIds()
	{
		return session.getCurrentGEBuyItemIds();
	}

	/**
	 * Get all active flip item IDs - items that should show as active flips.
	 * This includes:
	 * 1. Items currently in GE buy slots (pending or filled)
	 * 2. Items currently in GE sell slots (pending sale)
	 * 3. Items collected from GE in this session (waiting to be sold)
	 */
	public java.util.Set<Integer> getActiveFlipItemIds()
	{
		return session.getActiveFlipItemIds();
	}
	
	/**
	 * Get all active flip item IDs including items in inventory.
	 * This is used for filtering active flips display to show items the player
	 * actually has (in GE slots or inventory).
	 */
	public java.util.Set<Integer> getActiveFlipItemIdsWithInventory()
	{
		java.util.Set<Integer> itemIds = getActiveFlipItemIds();
		
		// Also include items currently in inventory
		ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item.getId() > 0)
				{
					itemIds.add(item.getId());
				}
			}
		}
		
		return itemIds;
	}
	
	@Override
	protected void startUp() throws Exception
	{
		log.debug("FlipSmart started!");
		overlayManager.add(geOverlay);
		overlayManager.add(geSlotOverlay);
		overlayManager.add(flipAssistOverlay);
		overlayManager.add(inventoryHighlightOverlay);
		mouseManager.registerMouseListener(overlayMouseListener);

		// Initialize Flip Assist input listener for hotkey support
		flipAssistInputListener = new FlipAssistInputListener(client, clientThread, config, flipAssistOverlay);
		keyManager.registerKeyListener(flipAssistInputListener);

		// GE search suggestion injector (clickable "FlipSmart item" shortcut row)
		geSearchSuggestion = new GeSearchSuggestion(client, config, flipAssistOverlay);

		// Initialize Flip Finder panel
		if (config.showFlipFinder())
		{
			initializeFlipFinderPanel();
		}

		// Start auto-refresh timer for flip finder
		startFlipFinderRefreshTimer();

		// Wire service callbacks and initialize auto-recommend
		wireServiceCallbacks();
		initializeAutoRecommendService();
		initializeActiveOfferAdvisor();
		initializeManualAdjustmentTracker();
		wireGrandExchangeTrackerCallbacks();

		// Start dump alert service
		dumpAlertService.start();
		motdService.start();

		// Sync webhook config to backend if configured
		webhookSyncService.syncIfChanged();

		// Note: Cash stack and RSN will be synced when player logs in via onGameStateChanged
		// Don't access client data during startup - must be on client thread
	}

	/**
	 * Wire callbacks for offline sync and active flip tracker services.
	 */
	private void wireServiceCallbacks()
	{
		offlineSyncService.setOnSyncComplete(this::schedulePostSyncTasks);
		activeFlipTracker.setOnPanelRefreshNeeded(() -> { if (flipFinderPanel != null) flipFinderPanel.refresh(); });
		activeFlipTracker.setOnActiveFlipsRefreshNeeded(() -> { if (flipFinderPanel != null) flipFinderPanel.refreshActiveFlips(); });
	}

	/**
	 * Initialize auto-recommend service and wire its callbacks.
	 */
	private void initializeAutoRecommendService()
	{
		autoRecommendService = new AutoRecommendService(config, this);
		autoRecommendService.setOnFocusChanged(this::handleAutoRecommendFocusChanged);
		autoRecommendService.setOnOverlayMessageChanged(flipAssistOverlay::setAutoStatusMessage);
		autoRecommendService.setDisplayedSellPriceProvider(itemId -> flipFinderPanel != null ? flipFinderPanel.getDisplayedSellPrice(itemId) : null);
		autoRecommendService.setOnStaleOfferPrompted(this::highlightSlotForItem);
		flipAssistOverlay.setOnStepChanged(autoRecommendService::onOverlayStepChanged);
	}

	/**
	 * Construct the active-offer advisor service, wire its callbacks, and start
	 * the 30-second poll timer.
	 */
	private void initializeActiveOfferAdvisor()
	{
		activeOfferAdvisorService = new ActiveOfferAdvisorService();
		activeOfferAdvisorService.setCallbacks(
			resp -> javax.swing.SwingUtilities.invokeLater(() -> applyActiveOfferSurface(resp)),
			itemId -> handleActiveOfferHandoff(itemId),
			itemId -> javax.swing.SwingUtilities.invokeLater(() -> clearActiveOfferSurface(itemId)));

		activeOfferAdvisorTimer = new java.util.Timer("ActiveOfferAdvisorTimer", true);
		activeOfferAdvisorTimer.scheduleAtFixedRate(new java.util.TimerTask()
		{
			@Override
			public void run()
			{
				pollActiveOfferAdvisor();
			}
		}, ACTIVE_OFFER_ADVISOR_INTERVAL_MS, ACTIVE_OFFER_ADVISOR_INTERVAL_MS);
	}

	/**
	 * Initialize manual adjustment tracker for staleness detection on manual flips.
	 */
	private void initializeManualAdjustmentTracker()
	{
		manualAdjustmentTracker = new ManualAdjustmentTracker(apiClient, config);

		// Wire callback: show adjustment prompts in FlipAssistOverlay
		manualAdjustmentTracker.setOnAdjustmentPrompt(flipAssistOverlay::setAutoStatusMessage);

		// Wire callback: focus a buy overlay when adjustment recommends a new price
		manualAdjustmentTracker.setOnFocusFlip((focus, statusMsg) ->
		{
			flipAssistOverlay.setFocusedFlip(focus);
			flipAssistOverlay.setAutoStatusMessage(statusMsg, focus.getItemId());
			updateInventoryHighlightForFocus(focus);
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

		// Wire suppliers for ditch logic (replacement recommendations)
		manualAdjustmentTracker.setCashStackSupplier(() ->
			session.getCurrentCashStack() > 0 ? session.getCurrentCashStack() : null);
		manualAdjustmentTracker.setRsnSupplier(() -> getCurrentRsnSafe().orElse(null));
		manualAdjustmentTracker.setFilledSlotsSupplier(this::getFilledGESlotCount);
		manualAdjustmentTracker.setMembersWorldSupplier(this::isMembersWorld);

		grandExchangeTracker.setManualAdjustmentTracker(manualAdjustmentTracker);
		grandExchangeTracker.setActiveOfferAdvisorService(activeOfferAdvisorService);
		grandExchangeTracker.setAdjustmentPromptsEnabled(config::showAdjustmentPrompts);
		grandExchangeTracker.setConfig(config);
	}

	private void highlightSlotForItem(int itemId)
	{
		geSlotOverlay.clearAllAdjustmentHighlights();
		for (Map.Entry<Integer, TrackedOffer> entry : session.getTrackedOffers().entrySet())
		{
			if (entry.getValue().getItemId() == itemId)
			{
				geSlotOverlay.setAdjustmentHighlight(entry.getKey(), 0);
				return;
			}
		}
	}

	private void updateInventoryHighlightForFocus(FocusedFlip focus)
	{
		inventoryHighlightOverlay.clearAll();
		if (focus != null && focus.isSelling())
		{
			inventoryHighlightOverlay.addHighlight(focus.getItemId());
		}
	}

	private void handleAutoRecommendFocusChanged(FocusedFlip focus)
	{
		flipAssistOverlay.setFocusedFlip(focus);
		updateInventoryHighlightForFocus(focus);
		if (focus != null)
		{
			geSlotOverlay.clearAllAdjustmentHighlights();
			log.debug("Auto-recommend focus set: {} {} @ {} gp",
				focus.getStep(), focus.getItemName(), focus.getCurrentStepPrice());
			// Keep panel focus in sync so the active flip refresh cycle doesn't
			// re-create a stale sell overlay for a previously focused item
			if (flipFinderPanel != null)
			{
				javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.setExternalFocus(focus));
			}
		}
		else
		{
			// Don't call flipAssistOverlay.clearFocus() here — setFocusedFlip(null) above
			// already clears the focused flip, and clearFocus() would also clear
			// autoStatusMessage which destroys the "Waiting for flips" overlay message
			// set by promptCollection() via invokeOverlayMessageCallback.

			// Clear panel focus to prevent the active flip refresh cycle
			// from re-creating the sell overlay after a sell order was placed
			if (flipFinderPanel != null)
			{
				javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.clearFocus());
			}
		}
	}

	/**
	 * Wire all GrandExchangeTracker callbacks.
	 */
	private void wireGrandExchangeTrackerCallbacks()
	{
		grandExchangeTracker.setAutoRecommendService(autoRecommendService);
		grandExchangeTracker.setRsnSupplier(this::getCurrentRsnSafe);
		grandExchangeTracker.setOnPanelRefresh(() -> { if (flipFinderPanel != null) flipFinderPanel.refresh(); });
		grandExchangeTracker.setOnActiveFlipsRefresh(() -> { if (flipFinderPanel != null) { flipFinderPanel.refreshActiveFlips(); flipFinderPanel.reevaluateSlotLimitDisplay(); } });
		grandExchangeTracker.setOnPendingOrdersUpdate(orders -> { if (flipFinderPanel != null) flipFinderPanel.updatePendingOrders(getPendingBuyOrders()); });
		grandExchangeTracker.setOnFocusChanged(this::handleGETrackerFocusChanged);
		grandExchangeTracker.setOnFocusClear(this::handleGETrackerFocusClear);
		grandExchangeTracker.setDisplayedSellPriceProvider(itemId -> flipFinderPanel != null ? flipFinderPanel.getDisplayedSellPrice(itemId) : null);
		grandExchangeTracker.setOneShotScheduler(this::scheduleOneShot);

		geHistoryService.setOnBackfillComplete(() -> {
			if (flipFinderPanel != null)
			{
				javax.swing.SwingUtilities.invokeLater(flipFinderPanel::refresh);
			}
		});
	}

	private void handleGETrackerFocusChanged(FocusedFlip focus)
	{
		flipAssistOverlay.setFocusedFlip(focus);
		updateInventoryHighlightForFocus(focus);
		if (flipFinderPanel != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.setExternalFocus(focus));
		}
	}

	private void handleGETrackerFocusClear(int itemId, boolean isBuy)
	{
		FocusedFlip focusedFlip = flipAssistOverlay.getFocusedFlip();
		if (focusedFlip == null || focusedFlip.getItemId() != itemId)
		{
			return;
		}
		boolean stepMatches = (isBuy && focusedFlip.isBuying()) || (!isBuy && focusedFlip.isSelling());
		if (stepMatches)
		{
			log.debug("Clearing Flip Assist focus - order submitted for {} ({})",
				focusedFlip.getItemName(), isBuy ? "BUY" : "SELL");
			flipAssistOverlay.clearFocus();
			updateInventoryHighlightForFocus(null);
			if (flipFinderPanel != null)
			{
				javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.clearFocus());
			}
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("FlipSmart stopped!");

		// Persist refresh token on shutdown to prevent session loss
		String currentRefreshToken = apiClient.getRefreshToken();
		if (currentRefreshToken != null && !currentRefreshToken.isEmpty())
		{
			configManager.setConfiguration(CONFIG_GROUP, "refreshToken", currentRefreshToken);
			log.debug("Persisted refresh token on shutdown");
		}

		// Persist offer state before shutting down (handles cases where client is closed without logout)
		// Try multiple RSN sources: session → lastKnownRsn → config
		String rsnForPersistence = session.getRsn();
		if (rsnForPersistence == null || rsnForPersistence.isEmpty())
		{
			rsnForPersistence = lastKnownRsn;
		}
		if (rsnForPersistence == null || rsnForPersistence.isEmpty())
		{
			rsnForPersistence = configManager.getConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY);
		}
		if (rsnForPersistence != null && !rsnForPersistence.isEmpty())
		{
			session.setRsn(rsnForPersistence);
		}
		if (!session.getTrackedOffers().isEmpty())
		{
			offlineSyncService.persistOfferState();
			log.debug("Persisted offer state on shutdown for {}", rsnForPersistence);
		}
		
		overlayManager.remove(geOverlay);
		overlayManager.remove(geSlotOverlay);
		overlayManager.remove(flipAssistOverlay);
		overlayManager.remove(inventoryHighlightOverlay);
		mouseManager.unregisterMouseListener(overlayMouseListener);
		
		// Unregister Flip Assist input listener
		if (flipAssistInputListener != null)
		{
			keyManager.unregisterKeyListener(flipAssistInputListener);
			flipAssistInputListener = null;
		}
		geSearchSuggestion = null;
		
		// Remove flip finder panel
		if (flipFinderNavButton != null)
		{
			clientToolbar.removeNavigation(flipFinderNavButton);
		}
		
		// Shutdown flip finder panel (cleanup device auth polling, etc.)
		if (flipFinderPanel != null)
		{
			flipFinderPanel.shutdown();
		}
		
		// Stop auto-refresh timer
		stopFlipFinderRefreshTimer();

		// Stop all pending one-shot timers
		stopAllOneShotTimers();

		// Stop dump alert service
		dumpAlertService.stop();
		motdService.stop();

		// Stop auto-recommend service and timer
		stopAutoRecommendRefreshTimer();
		if (autoRecommendService != null)
		{
			persistAutoRecommendState();
			autoRecommendService.stop();
		}

		// Stop active-offer advisor poll timer
		stopActiveOfferAdvisorTimer();

		// Clear API client cache
		apiClient.clearCache();

		// Shut down the trade-station snapshot pusher's executor.
		tradeStationSlotPushService.shutdown();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (!CONFIG_GROUP.equals(configChanged.getGroup()))
		{
			return;
		}

		if ("enableAutoRecommend".equals(configChanged.getKey()) && flipFinderPanel != null)
		{
			flipFinderPanel.setAutoRecommendVisible(config.enableAutoRecommend());
		}

		if ("f2pMode".equals(configChanged.getKey()) && flipFinderPanel != null)
		{
			flipFinderPanel.refresh();
		}

		if (("cashstackOverrideEnabled".equals(configChanged.getKey())
			|| "cashstackOverrideAmount".equals(configChanged.getKey())) && flipFinderPanel != null)
		{
			flipFinderPanel.updateCashstackOverrideIndicator();
			flipFinderPanel.refresh();
		}

		// Sync webhook config changes to backend
		String key = configChanged.getKey();
		if ("discordWebhookUrl".equals(key) || "notifySaleCompleted".equals(key))
		{
			webhookSyncService.syncIfChanged();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState gameState = gameStateChanged.getGameState();

		// Track login/hopping to avoid recording existing GE offers
		if (gameState == GameState.LOGGING_IN || gameState == GameState.HOPPING || gameState == GameState.CONNECTION_LOST)
		{
			session.onLoginStateChange(client.getTickCount());

			// Persist offer state before hop/reconnect so timestamps survive.
			// handleLogoutState only runs on LOGIN_SCREEN, not hops.
			if (!session.getTrackedOffers().isEmpty())
			{
				offlineSyncService.persistOfferState();
			}
		}

		if (gameState == GameState.LOGIN_SCREEN)
		{
			handleLogoutState();
		}

		if (gameState == GameState.LOGGED_IN)
		{
			handleLoggedInState();
			motdService.onLogin();
		}
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		updateMembersWorldCache();
		if (flipFinderPanel != null)
		{
			flipFinderPanel.refresh();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player localPlayer = client.getLocalPlayer();
		atGrandExchange = localPlayer != null && localPlayer.getWorldLocation().getRegionID() == GE_REGION_ID;

		// On the first LOGGED_IN tick the local player is sometimes not yet
		// populated, so syncRSN()'s early-return fires and we'd be stuck with
		// either no RSN or (worse) the previous account's name. Retry here on
		// each tick until the live name is captured. Issue #556.
		if (rsnSyncPending && localPlayer != null && localPlayer.getName() != null && !localPlayer.getName().isEmpty())
		{
			syncRSN();
		}

		geHistoryService.onGameTick();

		releaseOfferLockIfSetupClosed();
	}

	private void releaseOfferLockIfSetupClosed()
	{
		if (autoRecommendService == null || autoRecommendService.getLockedItemId() == null)
		{
			return;
		}
		Widget setupDesc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		if (setupDesc == null || setupDesc.isHidden())
		{
			autoRecommendService.releaseOfferLock();
			autoRecommendService.refreshFocusAfterUnlock();
		}
	}

	private void handleLogoutState()
	{
		session.onLogout();
		offlineSyncService.persistOfferState();
		geHistoryService.reset();
		persistAutoRecommendState();

		// Stop auto-recommend on logout
		if (autoRecommendService != null && autoRecommendService.isActive())
		{
			autoRecommendService.stop();
			if (flipFinderPanel != null)
			{
				flipFinderPanel.updateAutoRecommendButton(false);
			}
		}
		stopAutoRecommendRefreshTimer();

		// Clear manual adjustment timers on logout
		if (manualAdjustmentTracker != null)
		{
			manualAdjustmentTracker.clearAll();
		}

		if (flipFinderPanel != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.showLoggedOutOfGameState());
		}
	}

	private void handleLoggedInState()
	{
		log.debug("Player logged in");
		updateMembersWorldCache();
		session.onLoggedIn();
		syncRSN();

		// If syncRSN couldn't capture the live name yet (player object not
		// populated on the first LOGGED_IN tick), fall back to the persisted
		// last-known RSN so offer preloading and the entitlements call below
		// have *something* to work with. onGameTick will refresh to the live
		// name as soon as it's available. Issue #556.
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			String persistedRsn = configManager.getConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY);
			if (persistedRsn != null && !persistedRsn.isEmpty())
			{
				session.setRsn(persistedRsn);
				lastKnownRsn = persistedRsn;
				log.debug("Using persisted RSN fallback: {}", persistedRsn);
			}
		}

		updateCashStack();

		apiClient.fetchWikiPrices();

		apiClient.fetchEntitlementsAsync(getCurrentRsnSafe().orElse(null)).thenAccept(isPremium -> {
			log.debug("User premium status: {}", isPremium);
			if (flipFinderPanel != null)
			{
				javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.updatePremiumStatus());
			}

			// Pull webhook config after auth is confirmed
			webhookSyncService.pullFromBackend();
		});

		// Restore collected items from config (items bought but not yet sold)
		// Must be after syncRSN() so we have the correct RSN for the config key
		offlineSyncService.restoreCollectedItems();

		// Preload persisted offers into the session BEFORE login burst fires.
		// This ensures createWithPreservedTimestamps() finds the existing offer
		// with its original timestamp, giving us accurate timers from the start.
		offlineSyncService.preloadPersistedOffers();

		restoreAutoRecommendState();

		// Start the refresh timer if not already running (needed for manual adjustment checks)
		if (autoRecommendRefreshTimer == null)
		{
			startAutoRecommendRefreshTimer();
		}

		// Schedule offline sync after a delay to ensure all GE events have been processed
		if (!session.isOfflineSyncCompleted())
		{
			scheduleOneShot(OFFLINE_SYNC_DELAY_MS, offlineSyncService::syncOfflineFills);
		}

		if (flipFinderPanel != null)
		{
			flipFinderPanel.refresh();
		}
	}
	
	
	/**
	 * Schedule panel refresh and stale flip cleanup after offline sync.
	 */
	private void schedulePostSyncTasks()
	{
		// Backfill any missing timestamps from backend before panel refresh
		backfillMissingTimestamps();

		if (flipFinderPanel != null)
		{
			scheduleOneShot(PANEL_REFRESH_DELAY_MS, () -> flipFinderPanel.refresh());
		}

		scheduleOneShot(STALE_FLIP_CLEANUP_DELAY_MS, this::performStaleFlipCleanup);

		if (autoRecommendService != null && autoRecommendService.isActive())
		{
			scheduleOneShot(AUTO_RECOMMEND_REEVALUATE_DELAY_MS, this::reevaluateAutoRecommendAfterLogin);
		}
	}

	/**
	 * Correct offer timestamps using backend active flips as source of truth.
	 * Always runs — if the backend has an OLDER timestamp than the local one,
	 * the local one was likely reset (login burst, rebuild, hop) and should
	 * be replaced with the backend's more accurate value.
	 */
	private void backfillMissingTimestamps()
	{
		Map<Integer, TrackedOffer> tracked = session.getTrackedOffers();
		if (tracked.isEmpty())
		{
			return;
		}

		log.debug("Checking offer timestamps against backend active flips");
		String rsn = getCurrentRsnSafe().orElse(null);
		apiClient.getActiveFlipsAsync(rsn).thenAccept(response ->
		{
			if (response == null || response.getActiveFlips() == null)
			{
				return;
			}

			Map<Integer, ActiveFlip> flipsByItem = new java.util.HashMap<>();
			for (ActiveFlip flip : response.getActiveFlips())
			{
				flipsByItem.put(flip.getItemId(), flip);
			}

			int corrected = 0;
			for (TrackedOffer offer : tracked.values())
			{
				if (correctOfferTimestamp(offer, flipsByItem))
				{
					corrected++;
				}
			}

			if (corrected > 0)
			{
				log.debug("Corrected {} offer timestamps from backend active flips", corrected);
			}
		}).exceptionally(e ->
		{
			log.debug("Failed to check timestamps against backend: {}", e.getMessage());
			return null;
		});
	}

	/**
	 * Backfill a missing timestamp from the backend.
	 * Only fills in timestamps that are 0 (unknown) — does NOT override existing
	 * local timestamps, since they're more accurate (set at offer placement time).
	 * The backend's last_buy_time can be from older transactions for the same item.
	 */
	private boolean correctOfferTimestamp(TrackedOffer offer, Map<Integer, ActiveFlip> flipsByItem)
	{
		if (offer.getCreatedAtMillis() > 0)
		{
			return false; // Local timestamp exists — trust it
		}

		ActiveFlip flip = flipsByItem.get(offer.getItemId());
		if (flip == null)
		{
			return false;
		}

		long backendMs = (!offer.isBuy() && flip.getSellPlacedTime() != null)
			? TimeUtils.parseIsoToMillis(flip.getSellPlacedTime())
			: TimeUtils.parseIsoToMillis(flip.getLastBuyTime());

		if (backendMs > 0)
		{
			log.debug("Backfilled missing timestamp for {} from backend ({}m ago)",
				offer.getItemName(), (System.currentTimeMillis() - backendMs) / 60000);
			offer.setCreatedAtMillis(backendMs);
			return true;
		}
		return false;
	}

	private void performStaleFlipCleanup()
	{
		if (!session.getTrackedOffers().isEmpty() || !session.getCollectedItemIds().isEmpty())
		{
			activeFlipTracker.cleanupStaleActiveFlips();
			scheduleOneShot(INVENTORY_VALIDATION_DELAY_MS, () ->
				clientThread.invokeLater(activeFlipTracker::validateInventoryQuantities));
		}
		else
		{
			log.debug("Skipping cleanup - no GE offers or collected items detected, may not be safe");
		}
	}

	private void reevaluateAutoRecommendAfterLogin()
	{
		boolean hasStaleOffers = autoRecommendService.reevaluateAfterLogin();
		if (hasStaleOffers)
		{
			scheduleOneShot(10_000, this::runEarlyAdjustmentCheck);
		}
	}

	private void runEarlyAdjustmentCheck()
	{
		PlayerSession sess = getSession();
		if (sess != null)
		{
			autoRecommendService.checkAdjustmentTimers(
				sess.getTrackedOffers(),
				flipFinderPanel != null ? flipFinderPanel.getCurrentRecommendations() : null
			);
			autoRecommendService.checkSellAdjustmentTimers(sess.getTrackedOffers());
		}
	}

	
	/**
	 * Sync the player's RSN with the API and store locally.
	 *
	 * On the first LOGGED_IN tick the local player can still be null or
	 * unnamed; in that case we set rsnSyncPending so onGameTick retries
	 * until the live name is captured. Issue #556.
	 */
	private void syncRSN()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			log.debug("syncRSN: getLocalPlayer() is null, will retry on next tick");
			rsnSyncPending = true;
			return;
		}

		String rsn = localPlayer.getName();
		if (rsn == null || rsn.isEmpty())
		{
			log.debug("syncRSN: player name is null or empty, will retry on next tick");
			rsnSyncPending = true;
			return;
		}

		rsnSyncPending = false;
		String previous = session.getRsn();
		session.setRsn(rsn);
		lastKnownRsn = rsn;
		configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY, rsn);
		if (previous != null && !previous.equals(rsn))
		{
			log.debug("RSN switched: {} -> {}", previous, rsn);
		}
		else
		{
			log.debug("RSN synced: {}", rsn);
		}
		apiClient.updateRSN(rsn);
	}

	/**
	 * Get the current RSN.
	 *
	 * When the player is logged in, the live client is authoritative — the
	 * cache may still hold the previous account's name from a quick character
	 * switch (issue #556). When offline, fall back to the cached or persisted
	 * value so background work (offer persistence, config keys) still resolves.
	 */
	public Optional<String> getCurrentRsnSafe()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			Player localPlayer = client.getLocalPlayer();
			if (localPlayer != null && localPlayer.getName() != null && !localPlayer.getName().isEmpty())
			{
				String liveRsn = localPlayer.getName();
				String cached = session.getRsn();
				if (!liveRsn.equals(cached))
				{
					log.debug("RSN refreshed from client: {} -> {}", cached, liveRsn);
					session.setRsn(liveRsn);
					lastKnownRsn = liveRsn;
					configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY, liveRsn);
				}
				return Optional.of(liveRsn);
			}
			// Logged in but the player object isn't ready yet — fall through
			// to the cache so the request still has *some* RSN. onGameTick
			// will refresh once the live name is available.
		}

		String cached = session.getRsn();
		if (cached != null && !cached.isEmpty())
		{
			return Optional.of(cached);
		}

		log.warn("Unable to determine RSN - transactions will be recorded without RSN");
		return Optional.empty();
	}
	
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();
		
		// Handle inventory changes
		if (containerId == INVENTORY_CONTAINER_ID)
		{
			updateCashStack();
			return;
		}
		
		// Handle bank container changes (bank opened/updated)
		if (containerId == InventoryID.BANK.getId())
		{
			bankSnapshotService.onBankContainerChanged(getCurrentRsnSafe().orElse(null));
		}
	}
	
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.GE_OFFERS_SETUP_BUILD)
		{
			return;
		}

		// Issue #665 — hide the convenience-fee info icon (SETUP_GRAPHIC4) so it
		// doesn't overlap our multi-line description. Fires for both buy and
		// sell, not just the auto-focus path below.
		if (geOfferDescriptionService != null)
		{
			geOfferDescriptionService.onSetupBuildScriptPostFired();
		}

		int openItemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (openItemId > 0 && autoRecommendService != null)
		{
			autoRecommendService.acquireOfferLock(openItemId);
		}

		// GE_NEWOFFER_TYPE == 1 is a sell; anything else is a buy.
		int offerType = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE);
		if (offerType != 1)
		{
			// Buy setup: focus the queued buy recommendation for this item (if any) so
			// the overlay guides the buy the player is setting up, taking priority over
			// a pending collect/history prompt. Focus returns to the queue on lock
			// release once the setup screen closes.
			if (openItemId > 0 && autoRecommendService != null)
			{
				autoRecommendService.overrideFocusForBuy(openItemId);
			}
			return;
		}

		if (openItemId <= 0)
		{
			return;
		}

		grandExchangeTracker.autoFocusOnActiveFlip(openItemId);
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerEvent)
	{
		final int slot = offerEvent.getSlot();
		final GrandExchangeOffer offer = offerEvent.getOffer();

		if (client.getGameState() != GameState.LOGGED_IN && offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			return;
		}

		// Warm the Trade Station "Import from RuneLite" cache (issue #683
		// AC7). Captured here on the client thread; pushed off-thread.
		tradeStationSlotPushService.scheduleSnapshotPush(
			tradeStationSlotPushService.readCurrentSlotIds());

		int itemId = offer.getItemId();
		int quantitySold = offer.getQuantitySold();
		int totalQuantity = offer.getTotalQuantity();
		int price = offer.getPrice();
		int spent = offer.getSpent();
		GrandExchangeOfferState state = offer.getState();

		String itemName = ItemUtils.getItemName(itemManager, itemId);

		// Check if this is during the login burst window
		int currentTick = client.getTickCount();
		boolean isLoginBurst = (currentTick - session.getLastLoginTick()) <= GE_LOGIN_BURST_WINDOW;

		if (isLoginBurst && state != GrandExchangeOfferState.EMPTY)
		{
			log.debug("Login burst: initializing tracking for slot {} with {} items sold", slot, quantitySold);
			TrackedOffer existing = session.getTrackedOffer(slot);
			TrackedOffer updated = TrackedOffer.createWithPreservedTimestamps(
				itemId, itemName, totalQuantity, price, quantitySold, existing, state);
			updated.setPreviousSpent(spent);
			session.putTrackedOffer(slot, updated);
			return;
		}

		grandExchangeTracker.handleOfferChanged(GrandExchangeTracker.OfferContext.builder()
			.slot(slot)
			.itemId(itemId)
			.itemName(itemName)
			.quantitySold(quantitySold)
			.totalQuantity(totalQuantity)
			.price(price)
			.spent(spent)
			.isBuy(TrackedOffer.isBuyState(state))
			.state(state)
			.build());
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (event.getIndex() == FlipAssistInputListener.VARCLIENT_INPUT_TYPE && flipAssistInputListener != null)
		{
			int inputType = client.getVarcIntValue(FlipAssistInputListener.VARCLIENT_INPUT_TYPE);
			flipAssistInputListener.updateInputType(inputType);

			// When the GE item-search dialog opens, inject the clickable
			// "FlipSmart item" shortcut row. Deferred so the search-results
			// widget is built before we add children to it.
			if (inputType == FlipAssistInputListener.INPUT_TYPE_GE_ITEM_SEARCH && geSearchSuggestion != null)
			{
				clientThread.invokeLater(geSearchSuggestion::showSuggestedItemInSearch);
			}
		}
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged event)
	{
		// Keep the listener's cached search text fresh so its EDT-side consume
		// gate (suppressing the stray hotkey char in GE item search) can compare
		// what's typed against the focused item name.
		if (event.getIndex() == FlipAssistInputListener.VARCLIENT_INPUT_TEXT && flipAssistInputListener != null)
		{
			flipAssistInputListener.updateInputText(client.getVarcStrValue(FlipAssistInputListener.VARCLIENT_INPUT_TEXT));
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.GE_HISTORY)
		{
			geHistoryService.onHistoryWidgetLoaded();
		}
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		// Issue #665 — replace GE buy/sell window description text with FlipSmart
		// contextual data. The runelite-injected GeExamineInfoText script fires
		// geBuyExamineText / geSellExamineText every time the description rebuilds,
		// so dynamic updates on price/qty edits are free.
		if (geOfferDescriptionService != null)
		{
			geOfferDescriptionService.onScriptCallbackEvent(event);
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		// Issue #665 — in-flight Offer status panel (DETAILS_DESC). Hooked at
		// frame-render time (not script-fire) because OSRS rebuilds the widget
		// from ~30 scripts per tick; reacting per-script is an unwinnable race.
		// Once-per-frame check with text-equality short-circuit gives us the
		// final say each frame without performance overhead.
		if (geOfferDescriptionService != null)
		{
			geOfferDescriptionService.onBeforeRender(event);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Issue #665 — record the GE slot the user clicks so the offer-status
		// panel renders contextual data for the open panel rather than the
		// hovered slot tile (which is what GE_SELECTEDSLOT tracks).
		if (geOfferDescriptionService != null)
		{
			geOfferDescriptionService.onMenuOptionClicked(event);
		}
	}


	/**
	 * Initialize the Flip Finder panel and add it to the sidebar
	 */
	private void initializeFlipFinderPanel()
	{
		flipFinderPanel = new FlipFinderPanel(config, apiClient, itemManager, this, configManager)
		{
			@Override
			protected Integer getCashStack()
			{
				if (config.cashstackOverrideEnabled())
				{
					java.util.OptionalInt override = GpUtils.parseGp(config.cashstackOverrideAmount());
					if (override.isPresent())
					{
						return override.getAsInt();
					}
				}
				return session.getCurrentCashStack() > 0 ? session.getCurrentCashStack() : null;
			}

			@Override
			protected Integer getFilledSlots()
			{
				return getFilledGESlotCount();
			}
		};
		
		// Connect Flip Assist focus callback
		flipFinderPanel.setOnFocusChanged(focus -> {
			flipAssistOverlay.setFocusedFlip(focus);
			if (focus != null)
			{
				log.debug("Flip Assist focus set: {} {} - {} @ {} gp", 
					focus.getStep(),
					focus.getItemName(),
					focus.getCurrentStepQuantity(),
					focus.getCurrentStepPrice());
			}
			else
			{
				log.debug("Flip Assist focus cleared");
				flipAssistOverlay.clearFocus();
			}
		});

		flipFinderPanel.setOfferDispositionLookup(id -> activeOfferAdvisorService.getDisposition(id));

		// Connect auth success callback to sync RSN after Discord login
		flipFinderPanel.setOnAuthSuccess(() -> {
			// Sync RSN to API if we have one (player is logged in)
			if (session.getRsn() != null && !session.getRsn().isEmpty())
			{
				log.debug("Auth success callback - syncing RSN: {}", session.getRsn());
				apiClient.updateRSN(session.getRsn());
			}
			else
			{
				log.debug("Auth success callback - no RSN to sync yet");
			}
		});

		// Try to load custom icon from resources
		java.awt.image.BufferedImage iconImage = null;
		try
		{
			iconImage = net.runelite.client.util.ImageUtil.loadImageResource(getClass(), "/flip_finder_icon.png");
		}
		catch (Exception e)
		{
			log.debug("Could not load flip finder icon, using default icon");
		}

		// If custom icon not found, create a default one
		if (iconImage == null)
		{
			iconImage = createDefaultIcon();
		}

		// Create navigation button
		flipFinderNavButton = net.runelite.client.ui.NavigationButton.builder()
			.tooltip("FlipSmart")
			.icon(iconImage)
			.priority(7)
			.panel(flipFinderPanel)
			.build();

		clientToolbar.addNavigation(flipFinderNavButton);
		log.debug("Flip Finder panel initialized");
	}

	/**
	 * Create a default icon for the Flip Finder button
	 */
	private java.awt.image.BufferedImage createDefaultIcon()
	{
		// Create a simple default icon
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		g.setColor(java.awt.Color.ORANGE);
		g.fillRect(2, 2, 12, 12);
		g.setColor(java.awt.Color.WHITE);
		g.drawString("F", 5, 12);
		g.dispose();
		return image;
	}

	/**
	 * Update the player's current cash stack from inventory
	 */
	private void updateCashStack()
	{
		ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
		if (inventory == null)
		{
			session.setCashStack(0);
			return;
		}

		int totalCash = 0;
		Item[] items = inventory.getItems();

		for (Item item : items)
		{
			if (item.getId() == COINS_ITEM_ID)
			{
				totalCash += item.getQuantity();
			}
		}

		if (totalCash != session.getCurrentCashStack())
		{
			session.setCashStack(totalCash);
			log.debug("Updated cash stack: {}", session.getCurrentCashStack());

			// If cash stack changed significantly and we have a flip finder panel, refresh it
			if (flipFinderPanel != null && totalCash > AUTO_REFRESH_CASH_THRESHOLD)
			{
				// Only auto-refresh if it's been more than the minimum interval since last refresh
				long now = System.currentTimeMillis();
				if (now - session.getLastFlipFinderRefresh() > AUTO_REFRESH_MIN_INTERVAL_MS)
				{
					session.setLastFlipFinderRefresh(now);
					flipFinderPanel.refresh();
				}
			}
		}
	}

	/**
	 * Start the auto-refresh timer for flip finder
	 */
	private void startFlipFinderRefreshTimer()
	{
		if (flipFinderRefreshTimer != null)
		{
			flipFinderRefreshTimer.cancel();
		}

		flipFinderRefreshTimer = new java.util.Timer("FlipFinderRefreshTimer", true);
		
		// Schedule refresh based on config
		int refreshMinutes = Math.max(1, Math.min(60, config.flipFinderRefreshMinutes()));
		long refreshIntervalMs = refreshMinutes * 60 * 1000L;

		flipFinderRefreshTimer.scheduleAtFixedRate(new java.util.TimerTask()
		{
			@Override
			public void run()
			{
				// Skip API calls if player is not logged into RuneScape
				// This saves API requests and battery when at the login screen
				if (!session.isLoggedIntoRunescape())
				{
					log.debug("Skipping auto-refresh - player not logged into RuneScape");
					return;
				}
				
				// Webhook sync — pull latest config from backend
				webhookSyncService.pullAndSync();

				if (flipFinderPanel != null && config.showFlipFinder())
				{
					javax.swing.SwingUtilities.invokeLater(() ->
					{
						log.debug("Auto-refreshing flip finder");
						session.setLastFlipFinderRefresh(System.currentTimeMillis());
						flipFinderPanel.refresh();
					});
				}

				// Check for inactive auto-recommend offers
				if (autoRecommendService != null && autoRecommendService.isActive() && flipFinderPanel != null)
				{
					autoRecommendService.checkInactiveOffers(
						session.getTrackedOffers(),
						flipFinderPanel.getCurrentRecommendations()
					);
				}
			}
		}, refreshIntervalMs, refreshIntervalMs);

		log.debug("Flip Finder auto-refresh started (every {} minutes)", refreshMinutes);
	}

	/**
	 * Stop the auto-refresh timer for flip finder
	 */
	private void stopFlipFinderRefreshTimer()
	{
		if (flipFinderRefreshTimer != null)
		{
			flipFinderRefreshTimer.cancel();
			flipFinderRefreshTimer = null;
			log.debug("Flip Finder auto-refresh stopped");
		}
	}

	/**
	 * Create and start a tracked one-shot Swing timer.
	 * The timer is automatically removed from tracking after it fires.
	 * All tracked timers are stopped during {@link #shutDown()}.
	 */
	private void scheduleOneShot(int delayMs, Runnable action)
	{
		javax.swing.Timer timer = new javax.swing.Timer(delayMs, null);
		timer.addActionListener(e ->
		{
			try
			{
				action.run();
			}
			finally
			{
				activeOneShotTimers.remove(timer);
			}
		});
		timer.setRepeats(false);
		activeOneShotTimers.add(timer);
		timer.start();
	}

	/**
	 * Stop all active one-shot Swing timers.
	 */
	private void stopAllOneShotTimers()
	{
		for (javax.swing.Timer timer : activeOneShotTimers)
		{
			timer.stop();
		}
		activeOneShotTimers.clear();
	}

	/**
	 * Start the auto-recommend refresh timer (2-minute interval).
	 * Fetches fresh recommendations and feeds them to the auto-recommend queue.
	 * Also checks manual adjustment timers when auto-recommend is inactive.
	 */
	void startAutoRecommendRefreshTimer()
	{
		stopAutoRecommendRefreshTimer();

		autoRecommendRefreshTimer = new java.util.Timer("AutoRecommendRefreshTimer", true);
		autoRecommendRefreshTimer.scheduleAtFixedRate(new java.util.TimerTask()
		{
			@Override
			public void run()
			{
				if (session.isLoggedIntoRunescape())
				{
					runRefreshCycle();
				}
			}
		}, AUTO_RECOMMEND_REFRESH_INTERVAL_MS, AUTO_RECOMMEND_REFRESH_INTERVAL_MS);

		log.debug("Auto-recommend refresh timer started (every 2 minutes)");
	}

	private void runRefreshCycle()
	{
		boolean autoActive = autoRecommendService != null && autoRecommendService.isActive();

		if (autoActive)
		{
			if (flipFinderPanel != null)
			{
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					log.debug("Auto-recommend refresh cycle");
					flipFinderPanel.refresh();
				});
			}

			java.util.Map<Integer, TrackedOffer> offers = session.getTrackedOffers();
			autoRecommendService.ensureAllOffersHaveTimers(offers);
			autoRecommendService.checkAdjustmentTimers(
				offers, flipFinderPanel != null ? flipFinderPanel.getCurrentRecommendations() : null);
			autoRecommendService.checkSellAdjustmentTimers(offers);
		}

		if (manualAdjustmentTracker != null)
		{
			manualAdjustmentTracker.checkTimers(session.getTrackedOffers());
		}
	}

	void stopAutoRecommendRefreshTimer()
	{
		if (autoRecommendRefreshTimer != null)
		{
			autoRecommendRefreshTimer.cancel();
			autoRecommendRefreshTimer = null;
		}
	}

	void stopActiveOfferAdvisorTimer()
	{
		if (activeOfferAdvisorTimer != null)
		{
			activeOfferAdvisorTimer.cancel();
			activeOfferAdvisorTimer = null;
		}
	}

	private void pollActiveOfferAdvisor()
	{
		if (!config.enableActiveOfferAdvisor() || config.flipTimeframe() != FlipSmartConfig.FlipTimeframe.ACTIVE)
		{
			return;
		}
		PlayerSession sess = getSession();
		if (sess == null)
		{
			return;
		}
		for (Map.Entry<Integer, TrackedOffer> entry : sess.getTrackedOffers().entrySet())
		{
			TrackedOffer offer = entry.getValue();
			if (offer == null || offer.isCompleted())
			{
				continue;
			}
			if (offer.getCreatedAtMillis() <= 0)
			{
				continue;
			}
			if (autoRecommendService != null
				&& Integer.valueOf(offer.getItemId()).equals(autoRecommendService.getLockedItemId()))
			{
				continue;
			}
			Integer dailyVolume = apiClient.getCachedDailyVolume(offer.getItemId());
			FlipSmartApiClient.WikiPrice market = apiClient.getWikiPrice(offer.getItemId());
			Integer avgBuy = offer.isBuy() ? null
				: BuyPriceLookup.findAverageBuyPrice(getCurrentActiveFlips(), offer.getItemId());
			OfferAdviceRequest req = ActiveOfferAdvisorService.buildSnapshot(offer, market, avgBuy, dailyVolume);
			apiClient.postOfferActionAsync(req)
				.thenAccept(resp -> activeOfferAdvisorService.applyResponse(offer.getItemId(), resp))
				.exceptionally(ex ->
				{
					if (log.isDebugEnabled())
					{
						log.debug("offer-action poll failed for {}: {}", offer.getItemId(), ex.getMessage());
					}
					return null;
				});
		}
	}

	private void applyActiveOfferSurface(OfferAdviceResponse resp)
	{
		if (resp == null || resp.getNewPrice() == null)
		{
			return;
		}
		PlayerSession sess = getSession();
		if (sess == null)
		{
			return;
		}
		Integer itemId = resp.getItemIdHint();
		if (itemId == null)
		{
			return;
		}
		sess.setRecommendedPrice(itemId, resp.getNewPrice());
		Integer slot = findSlotForItem(sess, itemId);
		if (slot != null && geSlotOverlay != null)
		{
			geSlotOverlay.setAdjustmentHighlight(slot, resp.getNewPrice());
		}
		if (flipFinderPanel != null)
		{
			flipFinderPanel.refreshActiveFlips();
		}
	}

	private void clearActiveOfferSurface(int itemId)
	{
		PlayerSession sess = getSession();
		if (sess == null)
		{
			return;
		}
		Integer slot = findSlotForItem(sess, itemId);
		if (slot != null && geSlotOverlay != null)
		{
			geSlotOverlay.clearAdjustmentHighlight(slot);
		}
		if (flipFinderPanel != null)
		{
			flipFinderPanel.refreshActiveFlips();
		}
	}

	private Integer findSlotForItem(PlayerSession sess, int itemId)
	{
		for (Map.Entry<Integer, TrackedOffer> e : sess.getTrackedOffers().entrySet())
		{
			TrackedOffer o = e.getValue();
			if (o != null && o.getItemId() == itemId && !o.isCompleted())
			{
				return e.getKey();
			}
		}
		return null;
	}

	private void handleActiveOfferHandoff(int itemId)
	{
		if (autoRecommendService == null)
		{
			return;
		}
		PlayerSession sess = getSession();
		if (sess == null)
		{
			return;
		}
		for (TrackedOffer offer : sess.getTrackedOffers().values())
		{
			if (offer != null && offer.getItemId() == itemId)
			{
				autoRecommendService.addToStaleQueue(offer);
				break;
			}
		}
	}

	// =====================
	// Auto-Recommend Persistence
	// =====================

	private String getAutoRecommendStateKey()
	{
		String rsn = session.getRsn();
		if (rsn == null || rsn.isEmpty())
		{
			rsn = lastKnownRsn;
		}
		if (rsn == null || rsn.isEmpty())
		{
			return AUTO_RECOMMEND_STATE_KEY_PREFIX + UNKNOWN_RSN_FALLBACK;
		}
		return AUTO_RECOMMEND_STATE_KEY_PREFIX + rsn;
	}

	/**
	 * Persist auto-recommend state to config for session survival.
	 */
	private void persistAutoRecommendState()
	{
		if (autoRecommendService == null || !autoRecommendService.isActive())
		{
			// Clean up any stale persisted state
			configManager.unsetConfiguration(CONFIG_GROUP, getAutoRecommendStateKey());
			return;
		}

		AutoRecommendService.PersistedState state = autoRecommendService.getStateForPersistence();
		String json = gson.toJson(state);
		configManager.setConfiguration(CONFIG_GROUP, getAutoRecommendStateKey(), json);
		log.debug("Persisted auto-recommend state ({} items in queue)", state.queue != null ? state.queue.size() : 0);
	}

	/**
	 * Restore auto-recommend state from config after login.
	 */
	private void restoreAutoRecommendState()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, getAutoRecommendStateKey());
		if (json == null || json.isEmpty())
		{
			return;
		}

		try
		{
			AutoRecommendService.PersistedState state = gson.fromJson(json, AutoRecommendService.PersistedState.class);
			if (autoRecommendService.restoreState(state, AutoRecommendService.MAX_PERSISTED_AGE_MS))
			{
				log.debug("Restored auto-recommend state from previous session");

				// Update the panel button
				if (flipFinderPanel != null)
				{
					flipFinderPanel.updateAutoRecommendButton(true);
				}

				// Start the refresh timer
				startAutoRecommendRefreshTimer();
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to restore auto-recommend state: {}", e.getMessage());
		}

		// Always clear the persisted state after attempting restore
		configManager.unsetConfiguration(CONFIG_GROUP, getAutoRecommendStateKey());
	}

	@Provides
	FlipSmartConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipSmartConfig.class);
	}

	@Provides
	@javax.inject.Singleton
	PlayerSession providePlayerSession()
	{
		return new PlayerSession();
	}

	// Mouse listener for GE overlay clicks
	private final MouseListener overlayMouseListener = new MouseListener()
	{
		@Override
		public java.awt.event.MouseEvent mouseClicked(java.awt.event.MouseEvent e)
		{
			// Get the overlay bounds
			Rectangle overlayBounds = geOverlay.getBounds();
			if (overlayBounds == null)
			{
				return e;
			}
			
			// Convert absolute click to relative coordinates
			Point relativeClick = new Point(
				e.getX() - overlayBounds.x,
				e.getY() - overlayBounds.y
			);
			
			// Check if click is on the collapse button
			Rectangle buttonBounds = geOverlay.getCollapseButtonBounds();
			if (buttonBounds.contains(relativeClick))
			{
				geOverlay.toggleCollapse();
				e.consume();
			}
			
			return e;
		}
		
		@Override
		public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
		{
			return e;
		}
		
		@Override
		public java.awt.event.MouseEvent mouseReleased(java.awt.event.MouseEvent e)
		{
			return e;
		}
		
		@Override
		public java.awt.event.MouseEvent mouseEntered(java.awt.event.MouseEvent e)
		{
			return e;
		}
		
		@Override
		public java.awt.event.MouseEvent mouseExited(java.awt.event.MouseEvent e)
		{
			return e;
		}
		
		@Override
		public java.awt.event.MouseEvent mouseDragged(java.awt.event.MouseEvent e)
		{
			return e;
		}
		
		@Override
		public java.awt.event.MouseEvent mouseMoved(java.awt.event.MouseEvent e)
		{
			return e;
		}
	};
}

