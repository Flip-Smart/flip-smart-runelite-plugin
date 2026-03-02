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
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.ScriptID;
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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@PluginDescriptor(
	name = "Flip Smart",
	description = "A tool to help with item flipping in the Grand Exchange",
	tags = {"grand exchange", "flipping", "trading", "money making"}
)
public class FlipSmartPlugin extends Plugin
{
	private static final int INVENTORY_CONTAINER_ID = 93;
	private static final int COINS_ITEM_ID = 995;

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
	private Gson gson;

	@Inject
	private DumpAlertService dumpAlertService;

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

	// Flip Finder panel
	private FlipFinderPanel flipFinderPanel;
	private net.runelite.client.ui.NavigationButton flipFinderNavButton;

	// Auto-recommend service
	@Getter
	private AutoRecommendService autoRecommendService;

	// Centralized session state management (provided via @Provides @Singleton)
	@Inject
	private PlayerSession session;

	// Auto-refresh timer for flip finder
	private java.util.Timer flipFinderRefreshTimer;

	// Auto-recommend refresh timer (2-minute cycle)
	private java.util.Timer autoRecommendRefreshTimer;

	// Track all active one-shot Swing timers for cleanup on shutdown
	private final List<javax.swing.Timer> activeOneShotTimers = new CopyOnWriteArrayList<>();

	// Track login to avoid recording existing offers as new transactions
	private static final int GE_LOGIN_BURST_WINDOW = 3; // ticks

	// Config keys for persisting state
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String UNKNOWN_RSN_FALLBACK = "unknown";
	private static final String AUTO_RECOMMEND_STATE_KEY_PREFIX = "autoRecommendState_";

	/** Auto-recommend queue refresh interval (2 minutes) */
	private static final long AUTO_RECOMMEND_REFRESH_INTERVAL_MS = 2 * 60 * 1000L;

	// Flip Assist input listener for hotkey handling
	private FlipAssistInputListener flipAssistInputListener;

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

	public int getFlipSlotLimit()
	{
		return isPremium() ? 8 : 2;
	}

	/**
	 * Get the inventory count for a specific item (delegate to ActiveFlipTracker).
	 */
	public int getInventoryCountForItem(int itemId)
	{
		return activeFlipTracker.getInventoryCountForItem(itemId);
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

		// Fallback to GE guide price if real-time prices unavailable
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
		log.info("Flip Smart started!");
		overlayManager.add(geOverlay);
		overlayManager.add(geSlotOverlay);
		overlayManager.add(flipAssistOverlay);
		mouseManager.registerMouseListener(overlayMouseListener);

		// Initialize Flip Assist input listener for hotkey support
		flipAssistInputListener = new FlipAssistInputListener(client, clientThread, config, flipAssistOverlay);
		keyManager.registerKeyListener(flipAssistInputListener);

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
		wireGrandExchangeTrackerCallbacks();

		// Start dump alert service
		dumpAlertService.start();

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
	}

	private void handleAutoRecommendFocusChanged(FocusedFlip focus)
	{
		flipAssistOverlay.setFocusedFlip(focus);
		if (focus != null)
		{
			log.info("Auto-recommend focus set: {} {} @ {} gp",
				focus.getStep(), focus.getItemName(), focus.getCurrentStepPrice());
		}
		else
		{
			flipAssistOverlay.clearFocus();
			// Also clear panel focus to prevent the active flip refresh cycle
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
		grandExchangeTracker.setOnActiveFlipsRefresh(() -> { if (flipFinderPanel != null) flipFinderPanel.refreshActiveFlips(); });
		grandExchangeTracker.setOnPendingOrdersUpdate(orders -> { if (flipFinderPanel != null) flipFinderPanel.updatePendingOrders(getPendingBuyOrders()); });
		grandExchangeTracker.setOnFocusChanged(this::handleGETrackerFocusChanged);
		grandExchangeTracker.setOnFocusClear(this::handleGETrackerFocusClear);
		grandExchangeTracker.setDisplayedSellPriceProvider(itemId -> flipFinderPanel != null ? flipFinderPanel.getDisplayedSellPrice(itemId) : null);
		grandExchangeTracker.setOneShotScheduler(this::scheduleOneShot);
	}

	private void handleGETrackerFocusChanged(FocusedFlip focus)
	{
		flipAssistOverlay.setFocusedFlip(focus);
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
			log.info("Clearing Flip Assist focus - order submitted for {} ({})",
				focusedFlip.getItemName(), isBuy ? "BUY" : "SELL");
			flipAssistOverlay.clearFocus();
			if (flipFinderPanel != null)
			{
				javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.clearFocus());
			}
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Flip Smart stopped!");

		// Persist refresh token on shutdown to prevent session loss
		String currentRefreshToken = apiClient.getRefreshToken();
		if (currentRefreshToken != null && !currentRefreshToken.isEmpty())
		{
			configManager.setConfiguration(CONFIG_GROUP, "refreshToken", currentRefreshToken);
			log.debug("Persisted refresh token on shutdown");
		}

		// Persist offer state before shutting down (handles cases where client is closed without logout)
		// Only persist if we have a valid RSN to avoid overwriting good data
		if (session.getRsn() != null && !session.getRsn().isEmpty())
		{
			offlineSyncService.persistOfferState();
			log.info("Persisted offer state on shutdown for {}", session.getRsn());
		}
		
		overlayManager.remove(geOverlay);
		overlayManager.remove(geSlotOverlay);
		overlayManager.remove(flipAssistOverlay);
		mouseManager.unregisterMouseListener(overlayMouseListener);
		
		// Unregister Flip Assist input listener
		if (flipAssistInputListener != null)
		{
			keyManager.unregisterKeyListener(flipAssistInputListener);
			flipAssistInputListener = null;
		}
		
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

		// Stop auto-recommend service and timer
		stopAutoRecommendRefreshTimer();
		if (autoRecommendService != null)
		{
			persistAutoRecommendState();
			autoRecommendService.stop();
		}

		// Clear API client cache
		apiClient.clearCache();
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
		}

		if (gameState == GameState.LOGIN_SCREEN)
		{
			handleLogoutState();
		}

		if (gameState == GameState.LOGGED_IN)
		{
			handleLoggedInState();
		}
	}

	private void handleLogoutState()
	{
		session.onLogout();
		offlineSyncService.persistOfferState();
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

		if (flipFinderPanel != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.showLoggedOutOfGameState());
		}
	}

	private void handleLoggedInState()
	{
		log.info("Player logged in");
		session.onLoggedIn();
		syncRSN();
		updateCashStack();

		apiClient.fetchWikiPrices();

		apiClient.fetchEntitlementsAsync(getCurrentRsnSafe().orElse(null)).thenAccept(isPremium -> {
			log.info("User premium status: {}", isPremium);
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
		restoreAutoRecommendState();

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
		// Refresh the panel after a short delay
		if (flipFinderPanel != null)
		{
			scheduleOneShot(PANEL_REFRESH_DELAY_MS, () -> flipFinderPanel.refresh());
		}

		// Schedule stale flip cleanup after GE state is stable
		scheduleOneShot(STALE_FLIP_CLEANUP_DELAY_MS, () ->
		{
			if (!session.getTrackedOffers().isEmpty() || session.getCollectedItemIds().isEmpty())
			{
				activeFlipTracker.cleanupStaleActiveFlips();
				// After cleanup, validate inventory quantities against active flips
				scheduleOneShot(INVENTORY_VALIDATION_DELAY_MS, () ->
					clientThread.invokeLater(activeFlipTracker::validateInventoryQuantities));
			}
			else
			{
				log.info("Skipping cleanup - no GE offers detected yet, may not be safe");
			}
		});

		// Re-evaluate auto-recommend after sync — collected items may need selling
		if (autoRecommendService != null && autoRecommendService.isActive())
		{
			scheduleOneShot(AUTO_RECOMMEND_REEVALUATE_DELAY_MS, () ->
				autoRecommendService.reevaluateAfterLogin());
		}
	}

	
	/**
	 * Sync the player's RSN with the API and store locally
	 */
	private void syncRSN()
	{
		if (client.getLocalPlayer() == null)
		{
			log.warn("syncRSN called but getLocalPlayer() is null");
			return;
		}
		
		String rsn = client.getLocalPlayer().getName();
		if (rsn != null && !rsn.isEmpty())
		{
			session.setRsn(rsn);
			log.info("RSN synced: {}", rsn);
			apiClient.updateRSN(rsn);
		}
		else
		{
			log.warn("syncRSN: player name is null or empty");
		}
	}
	
	/**
	 * Get the current RSN, attempting to fetch from client if not cached.
	 * Returns Optional.empty() if RSN cannot be determined.
	 * This ensures callers explicitly handle the case where RSN is unavailable.
	 */
	public Optional<String> getCurrentRsnSafe()
	{
		if (session.getRsn() != null && !session.getRsn().isEmpty())
		{
			return Optional.of(session.getRsn());
		}
		
		// Try to get RSN from client if not cached
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			session.setRsn(client.getLocalPlayer().getName());
			log.info("RSN fetched from client on-demand: {}", session.getRsn());
			return Optional.of(session.getRsn());
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

		int offerType = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE);
		if (offerType != 1)
		{
			return;
		}

		int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId <= 0)
		{
			return;
		}

		grandExchangeTracker.autoFocusOnActiveFlip(itemId);
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
			session.putTrackedOffer(slot, TrackedOffer.createWithPreservedTimestamps(
				itemId, itemName, totalQuantity, price, quantitySold, existing, state));
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
				return session.getCurrentCashStack() > 0 ? session.getCurrentCashStack() : null;
			}

			@Override
			protected Integer getFilledSlots()
			{
				return session.getTrackedOffers().size();
			}
		};
		
		// Connect Flip Assist focus callback
		flipFinderPanel.setOnFocusChanged(focus -> {
			flipAssistOverlay.setFocusedFlip(focus);
			if (focus != null)
			{
				log.info("Flip Assist focus set: {} {} - {} @ {} gp", 
					focus.getStep(),
					focus.getItemName(),
					focus.getCurrentStepQuantity(),
					focus.getCurrentStepPrice());
			}
			else
			{
				log.info("Flip Assist focus cleared");
				flipAssistOverlay.clearFocus();
			}
		});
		
		// Connect auth success callback to sync RSN after Discord login
		flipFinderPanel.setOnAuthSuccess(() -> {
			// Sync RSN to API if we have one (player is logged in)
			if (session.getRsn() != null && !session.getRsn().isEmpty())
			{
				log.info("Auth success callback - syncing RSN: {}", session.getRsn());
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
			.tooltip("Flip Finder")
			.icon(iconImage)
			.priority(7)
			.panel(flipFinderPanel)
			.build();

		clientToolbar.addNavigation(flipFinderNavButton);
		log.info("Flip Finder panel initialized");
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

		log.info("Flip Finder auto-refresh started (every {} minutes)", refreshMinutes);
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
			log.info("Flip Finder auto-refresh stopped");
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
				if (!session.isLoggedIntoRunescape()
					|| autoRecommendService == null
					|| !autoRecommendService.isActive())
				{
					return;
				}

				if (flipFinderPanel != null)
				{
					javax.swing.SwingUtilities.invokeLater(() ->
					{
						log.debug("Auto-recommend refresh cycle");
						flipFinderPanel.refresh();
					});
				}

				// Check adjustment timers for unfilled buy offers
				autoRecommendService.checkAdjustmentTimers(
					session.getTrackedOffers(),
					flipFinderPanel != null ? flipFinderPanel.getCurrentRecommendations() : null
				);
			}
		}, AUTO_RECOMMEND_REFRESH_INTERVAL_MS, AUTO_RECOMMEND_REFRESH_INTERVAL_MS);

		log.info("Auto-recommend refresh timer started (every 2 minutes)");
	}

	void stopAutoRecommendRefreshTimer()
	{
		if (autoRecommendRefreshTimer != null)
		{
			autoRecommendRefreshTimer.cancel();
			autoRecommendRefreshTimer = null;
		}
	}

	// =====================
	// Auto-Recommend Persistence
	// =====================

	private String getAutoRecommendStateKey()
	{
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			return AUTO_RECOMMEND_STATE_KEY_PREFIX + UNKNOWN_RSN_FALLBACK;
		}
		return AUTO_RECOMMEND_STATE_KEY_PREFIX + session.getRsn();
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
		log.info("Persisted auto-recommend state ({} items in queue)", state.queue != null ? state.queue.size() : 0);
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
				log.info("Restored auto-recommend state from previous session");

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

