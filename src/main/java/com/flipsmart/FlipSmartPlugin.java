package com.flipsmart;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
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
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
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
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
	private ChatMessageManager chatMessageManager;

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

	// Config keys for persisting offer state
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String UNKNOWN_RSN_FALLBACK = "unknown";
	private static final String PERSISTED_OFFERS_KEY_PREFIX = "persistedOffers_";
	private static final String COLLECTED_ITEMS_KEY_PREFIX = "collectedItems_";
	private static final String AUTO_RECOMMEND_STATE_KEY_PREFIX = "autoRecommendState_";

	/** Auto-recommend queue refresh interval (2 minutes) */
	private static final long AUTO_RECOMMEND_REFRESH_INTERVAL_MS = 2 * 60 * 1000L;

	// Flip Assist input listener for hotkey handling
	private FlipAssistInputListener flipAssistInputListener;

	// Bank snapshot cooldown constant
	private static final long BANK_SNAPSHOT_COOLDOWN_MS = 60_000; // 1 minute cooldown between attempts

	// Timer delay constants (in milliseconds)
	/** Delay before syncing offline fills after login */
	private static final int OFFLINE_SYNC_DELAY_MS = 2000;
	/** Delay before refreshing panel after sync */
	private static final int PANEL_REFRESH_DELAY_MS = 1000;
	/** Delay before cleaning up stale flips (allows GE state to stabilize) */
	private static final int STALE_FLIP_CLEANUP_DELAY_MS = 15000;
	/** Delay before validating inventory quantities */
	private static final int INVENTORY_VALIDATION_DELAY_MS = 2000;
	/** Delay before refreshing panel after transaction/collection */
	private static final int TRANSACTION_REFRESH_DELAY_MS = 500;

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
	
	/**
	 * Mark an item as sold - removes it from the collected tracking.
	 * Called when a sell transaction is recorded.
	 * Also checks if inventory is empty AND no active GE sell slot for this item,
	 * then auto-closes the active flip.
	 */
	public void markItemSold(int itemId)
	{
		if (session.removeCollectedItem(itemId))
		{
			log.debug("Removed item {} from collected tracking (sold)", itemId);
		}
		
		boolean hasActiveSellSlot = hasActiveSellSlotForItem(itemId);
		
		if (hasActiveSellSlot)
		{
			log.debug("Item {} still has active sell slot, keeping flip open", itemId);
			return;
		}
		
		// Only auto-close if inventory is empty AND there's no active sell slot
		tryAutoCloseFlip(itemId);
	}
	
	/**
	 * Check if an item has an active sell slot in the GE.
	 */
	private boolean hasActiveSellSlotForItem(int itemId)
	{
		return session.hasActiveSellSlotForItem(itemId);
	}
	
	/**
	 * Attempt to auto-close a flip if inventory is empty.
	 */
	private void tryAutoCloseFlip(int itemId)
	{
		int inventoryCount = getInventoryCountForItem(itemId);
		if (inventoryCount > 0)
		{
			return;
		}
		
		log.info("Inventory empty and no active sell slot for item {}, auto-closing active flip", itemId);
		apiClient.dismissActiveFlipAsync(itemId, getCurrentRsnSafe().orElse(null)).thenAccept(success ->
		{
			if (Boolean.TRUE.equals(success))
			{
				log.info("Successfully auto-closed active flip for item {} (no items remaining)", itemId);
				refreshPanelOnSwingThread();
			}
		});
	}
	
	/**
	 * Refresh the flip finder panel on the Swing EDT.
	 */
	private void refreshPanelOnSwingThread()
	{
		if (flipFinderPanel != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.refresh());
		}
	}
	
	/**
	 * Get the count of a specific item in the player's inventory
	 * @param itemId The item ID to check
	 * @return The quantity of the item in inventory (0 if not found)
	 */
	private int getInventoryCountForItem(int itemId)
	{
		ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
		if (inventory == null)
		{
			return 0;
		}
		
		int count = 0;
		Item[] items = inventory.getItems();
		for (Item item : items)
		{
			if (item.getId() == itemId)
			{
				count += item.getQuantity();
			}
		}
		
		return count;
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

		// Start dump alert service
		dumpAlertService.start();

		// Sync webhook config to backend if configured
		webhookSyncService.syncIfChanged();

		// Initialize auto-recommend service
		autoRecommendService = new AutoRecommendService(config, this);
		autoRecommendService.setOnFocusChanged(focus -> {
			flipAssistOverlay.setFocusedFlip(focus);
			if (focus != null)
			{
				log.info("Auto-recommend focus set: {} {} @ {} gp",
					focus.getStep(), focus.getItemName(), focus.getCurrentStepPrice());
			}
			else
			{
				flipAssistOverlay.clearFocus();
			}
		});
		autoRecommendService.setOnOverlayMessageChanged(flipAssistOverlay::setAutoStatusMessage);

		// Note: Cash stack and RSN will be synced when player logs in via onGameStateChanged
		// Don't access client data during startup - must be on client thread
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Flip Smart stopped!");
		
		// Persist offer state before shutting down (handles cases where client is closed without logout)
		// Only persist if we have a valid RSN to avoid overwriting good data
		if (session.getRsn() != null && !session.getRsn().isEmpty())
		{
			persistOfferState();
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
		persistOfferState();

		// Stop auto-recommend on logout (state was already persisted above)
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
		restoreCollectedItems();
		restoreAutoRecommendState();

		// Schedule offline sync after a delay to ensure all GE events have been processed
		if (!session.isOfflineSyncCompleted())
		{
			scheduleOneShot(OFFLINE_SYNC_DELAY_MS, this::syncOfflineFills);
		}

		if (flipFinderPanel != null)
		{
			flipFinderPanel.refresh();
		}
	}
	
	/**
	 * Restore collected item IDs from persisted config.
	 * These are items that were bought but not yet sold when the player logged out.
	 */
	private void restoreCollectedItems()
	{
		String key = getCollectedItemsKey();
		log.info("Attempting to restore collected items for RSN: {} (key: {})", session.getRsn(), key);
		
		java.util.Set<Integer> persisted = loadPersistedCollectedItems();
		if (!persisted.isEmpty())
		{
			session.restoreCollectedItems(persisted);
			log.info("Restored {} collected items from previous session: {}", persisted.size(), persisted);
		}
		else
		{
			log.info("No collected items found in config for RSN: {}", session.getRsn());
			session.clearCollectedItems();
		}
	}
	
	/**
	 * Persist the current GE offer state to config for offline tracking.
	 * Called when the player logs out.
	 * Uses RSN-specific key to support multiple accounts.
	 */
	private void persistOfferState()
	{
		String offersKey = getPersistedOffersKey();
		String collectedKey = getCollectedItemsKey();
		
		// Persist tracked offers
		if (session.getTrackedOffers().isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, offersKey);
			log.debug("No tracked offers to persist for {}", session.getRsn());
		}
		else
		{
			try
			{
				Map<Integer, TrackedOffer> offersToSave = session.getOffersForPersistence();
				String json = gson.toJson(offersToSave);
				configManager.setConfiguration(CONFIG_GROUP, offersKey, json);
				log.info("Persisted {} tracked offers for {} (offline sync)", offersToSave.size(), session.getRsn());
			}
			catch (Exception e)
			{
				log.error("Failed to persist offer state for {}: {}", session.getRsn(), e.getMessage());
			}
		}
		
		// Persist collected item IDs (items bought but not yet sold)
		if (session.getCollectedItemIds().isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, collectedKey);
			log.debug("No collected items to persist for {}", session.getRsn());
		}
		else
		{
			try
			{
				String json = gson.toJson(new java.util.ArrayList<>(session.getCollectedItemsForPersistence()));
				configManager.setConfiguration(CONFIG_GROUP, collectedKey, json);
				log.info("Persisted {} collected item IDs for {} (active flips)", session.getCollectedItemIds().size(), session.getRsn());
			}
			catch (Exception e)
			{
				log.error("Failed to persist collected items for {}: {}", session.getRsn(), e.getMessage());
			}
		}

		// Persist auto-recommend state
		persistAutoRecommendState();
	}

	/**
	 * Sync fills that occurred while offline.
	 * Records current GE state to the backend. Cleanup of stale flips happens
	 * later via schedulePostSyncTasks() after GE state is fully loaded.
	 */
	private void syncOfflineFills()
	{
		if (session.isOfflineSyncCompleted())
		{
			return;
		}
		session.setOfflineSyncCompleted(true);
		
		// Load persisted offers from last session to compare against current state
		Map<Integer, TrackedOffer> persistedOffers = loadPersistedOffers();
		log.debug("Loaded {} persisted offers, comparing with {} current offers", 
			persistedOffers.size(), session.getTrackedOffers().size());
		
		// Always sync current offers - handles both:
		// 1. Offers with persisted state (compare for offline fills)
		// 2. Offers WITHOUT persisted state (record as new transactions)
		if (!session.getTrackedOffers().isEmpty())
		{
			syncCurrentOffersWithPersisted(persistedOffers);
		}
		
		// Handle slots that became empty while offline
		if (!persistedOffers.isEmpty())
		{
			handleEmptyPersistedSlots(persistedOffers);
			// Clear persisted state after sync (RSN-specific key)
			configManager.unsetConfiguration(CONFIG_GROUP, getPersistedOffersKey());
		}
		
		log.info("Offline sync completed for {}", session.getRsn());
		
		// Schedule panel refresh and cleanup
		schedulePostSyncTasks();
	}
	
	/**
	 * Sync current GE offers against persisted state to detect offline fills.
	 */
	private void syncCurrentOffersWithPersisted(Map<Integer, TrackedOffer> persistedOffers)
	{
		for (Map.Entry<Integer, TrackedOffer> entry : session.getTrackedOffers().entrySet())
		{
			int slot = entry.getKey();
			TrackedOffer currentOffer = entry.getValue();
			TrackedOffer persistedOffer = persistedOffers.get(slot);
			
			if (persistedOffer != null && persistedOffer.getItemId() == currentOffer.getItemId())
			{
				// Restore the original timestamp from persisted offer for timer continuity
				currentOffer.setCreatedAtMillis(persistedOffer.getCreatedAtMillis());

				// Have persisted state - check for offline fills
				recordOfflineFillsIfAny(slot, currentOffer, persistedOffer);
			}
			else if (currentOffer.getTotalQuantity() > 0)
			{
				// No persisted state but there's an active order - record it
				log.debug("Recording GE order for {} {} (slot {}): {}/{} items at {} gp",
					currentOffer.isBuy() ? "BUY" : "SELL",
					currentOffer.getItemName(), slot, currentOffer.getPreviousQuantitySold(), 
					currentOffer.getTotalQuantity(), currentOffer.getPrice());
				
				Integer recommendedSellPrice = currentOffer.isBuy() ? session.getRecommendedPrice(currentOffer.getItemId()) : null;
				
				apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
					.builder(currentOffer.getItemId(), currentOffer.getItemName(), currentOffer.isBuy(),
						currentOffer.getPreviousQuantitySold(), currentOffer.getPrice())
					.geSlot(slot)
					.recommendedSellPrice(recommendedSellPrice)
					.rsn(getCurrentRsnSafe().orElse(null))
					.totalQuantity(currentOffer.getTotalQuantity())
					.build());
				
				// For buy orders with fills, add to collected tracking so it shows in active flips
				if (currentOffer.isBuy() && currentOffer.getPreviousQuantitySold() > 0)
				{
					session.addCollectedItem(currentOffer.getItemId());
				}
			}
		}
	}
	
	/**
	 * Record offline fills if the current offer has more fills than persisted.
	 */
	private void recordOfflineFillsIfAny(int slot, TrackedOffer currentOffer, TrackedOffer persistedOffer)
	{
		int offlineFills = currentOffer.getPreviousQuantitySold() - persistedOffer.getPreviousQuantitySold();
		if (offlineFills <= 0)
		{
			return;
		}
		
		log.debug("Detected {} offline fills for {} (slot {}): {} -> {} (order size: {})",
			offlineFills, currentOffer.getItemName(), slot,
			persistedOffer.getPreviousQuantitySold(), currentOffer.getPreviousQuantitySold(),
			currentOffer.getTotalQuantity());
		
		Integer recommendedSellPrice = currentOffer.isBuy() ? session.getRecommendedPrice(currentOffer.getItemId()) : null;
		
		apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
			.builder(currentOffer.getItemId(), currentOffer.getItemName(), currentOffer.isBuy(),
				offlineFills, currentOffer.getPrice())
			.geSlot(slot)
			.recommendedSellPrice(recommendedSellPrice)
			.rsn(getCurrentRsnSafe().orElse(null))
			.totalQuantity(currentOffer.getTotalQuantity())
			.build());
	}
	
	/**
	 * Handle persisted slots that are now empty (offer completed or cancelled offline).
	 * Checks inventory to detect orders that completed while offline.
	 */
	private void handleEmptyPersistedSlots(Map<Integer, TrackedOffer> persistedOffers)
	{
		for (Map.Entry<Integer, TrackedOffer> entry : persistedOffers.entrySet())
		{
			int slot = entry.getKey();
			TrackedOffer persistedOffer = entry.getValue();
			
			if (session.getTrackedOffers().containsKey(slot))
			{
				continue;
			}
			
			log.info("Slot {} is now empty (was tracking {} x{}). Checking for offline completions.",
				slot, persistedOffer.getItemName(), persistedOffer.getTotalQuantity());
			
			if (persistedOffer.isBuy())
			{
				handleEmptyBuySlot(persistedOffer);
			}
			else
			{
				handleEmptySellSlot(persistedOffer);
			}
		}
	}
	
	/**
	 * Handle an empty slot that was previously a sell order.
	 * If items sold offline, record them as SELL transactions.
	 */
	private void handleEmptySellSlot(TrackedOffer persistedOffer)
	{
		int soldQuantity = persistedOffer.getPreviousQuantitySold();
		
		if (soldQuantity > 0)
		{
			log.info("Detected {} {} sold offline. Recording SELL transaction.",
				soldQuantity, persistedOffer.getItemName());
			
			// Record the offline sell transaction
			recordOfflineSellTransaction(persistedOffer, soldQuantity);
		}
		else
		{
			log.info("Sell order for {} was cancelled or no items sold.", persistedOffer.getItemName());
		}
	}
	
	/**
	 * Record a SELL transaction for items that sold while offline.
	 */
	private void recordOfflineSellTransaction(TrackedOffer persistedOffer, int soldQuantity)
	{
		String rsn = getCurrentRsnSafe().orElse(null);
		if (rsn == null)
		{
			log.warn("Cannot record offline sell - no RSN available");
			return;
		}
		
		// Create a SELL transaction for the items that sold offline
		apiClient.recordTransactionAsync(
			persistedOffer.getItemId(),
			persistedOffer.getItemName(),
			"SELL",
			soldQuantity,
			persistedOffer.getPrice(),
			rsn
		);
	}
	
	/**
	 * Handle an empty slot that was previously a buy order.
	 * Checks inventory and syncs with backend if needed.
	 */
	private void handleEmptyBuySlot(TrackedOffer persistedOffer)
	{
		int inventoryCount = getInventoryCountForItem(persistedOffer.getItemId());
		int trackedFills = persistedOffer.getPreviousQuantitySold();
		
		if (inventoryCount > 0)
		{
			handleBuyOrderWithInventory(persistedOffer, inventoryCount, trackedFills);
		}
		else if (trackedFills > 0)
		{
			log.info("No {} found in inventory (had {} fills tracked). Items may have been sold/used offline.",
				persistedOffer.getItemName(), trackedFills);
		}
	}
	
	/**
	 * Handle a completed buy order that has items in inventory.
	 * Syncs with backend if inventory count exceeds tracked fills.
	 */
	private void handleBuyOrderWithInventory(TrackedOffer persistedOffer, int inventoryCount, int trackedFills)
	{
		session.addCollectedItem(persistedOffer.getItemId());
		
		int actualFills = calculateActualFills(persistedOffer, inventoryCount, trackedFills);
		
		if (actualFills > trackedFills)
		{
			syncOfflineCompletedOrder(persistedOffer, inventoryCount, trackedFills, actualFills);
		}
		else
		{
			log.info("Adding {} to collected tracking (had {} items filled before going offline)",
				persistedOffer.getItemName(), trackedFills);
		}
	}
	
	/**
	 * Calculate actual fills based on inventory count, tracked fills, and order size.
	 */
	private int calculateActualFills(TrackedOffer persistedOffer, int inventoryCount, int trackedFills)
	{
		int actualFills = Math.max(inventoryCount, trackedFills);
		
		// If we have at least order_size items, the order completed fully
		if (inventoryCount >= persistedOffer.getTotalQuantity())
		{
			actualFills = persistedOffer.getTotalQuantity();
		}
		
		return actualFills;
	}
	
	/**
	 * Sync an offline-completed order with the backend.
	 */
	private void syncOfflineCompletedOrder(TrackedOffer persistedOffer, int inventoryCount, int trackedFills, int actualFills)
	{
		log.info("Detected offline completion for {} - tracked {} fills but have {} in inventory. Syncing {} items with backend.",
			persistedOffer.getItemName(), trackedFills, inventoryCount, actualFills);
		
		String rsn = getCurrentRsnSafe().orElse(null);
		if (rsn == null)
		{
			return;
		}
		
		apiClient.syncActiveFlipAsync(
			persistedOffer.getItemId(),
			persistedOffer.getItemName(),
			actualFills,
			persistedOffer.getTotalQuantity(),
			persistedOffer.getPrice(),
			rsn
		);
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
				cleanupStaleActiveFlips();
				// After cleanup, validate inventory quantities against active flips
				scheduleInventoryQuantityValidation();
			}
			else
			{
				log.info("Skipping cleanup - no GE offers detected yet, may not be safe");
			}
		});
	}
	
	/**
	 * Schedule validation of inventory quantities against active flips.
	 * If inventory has fewer items than an active flip shows, sync down.
	 */
	private void scheduleInventoryQuantityValidation()
	{
		// Delay slightly to ensure cleanup has completed
		scheduleOneShot(INVENTORY_VALIDATION_DELAY_MS, () -> clientThread.invokeLater(this::validateInventoryQuantities));
	}
	
	/**
	 * Validate inventory quantities against active flips and sync down if needed.
	 * Must be called on client thread.
	 * 
	 * This is a safeguard for when items were sold/used without plugin tracking.
	 * It counts items in BOTH inventory AND GE sell slots to get the true count.
	 */
	private void validateInventoryQuantities()
	{
		String rsn = getCurrentRsnSafe().orElse(null);
		if (rsn == null)
		{
			return;
		}
		
		// Get total item counts (inventory + items in sell slots)
		Map<Integer, Integer> totalItemCounts = getTotalItemCounts();
		
		// Fetch current active flips from backend
		apiClient.getActiveFlipsAsync(rsn).thenAccept(response -> {
			if (response == null || response.getActiveFlips() == null)
			{
				return;
			}
			
			for (ActiveFlip flip : response.getActiveFlips())
			{
				validateAndSyncFlipQuantity(flip, totalItemCounts, rsn);
			}
		}).exceptionally(e -> {
			log.debug("Failed to validate inventory quantities: {}", e.getMessage());
			return null;
		});
	}
	
	/**
	 * Validate a single flip's quantity against actual item count and sync if needed.
	 * GE + Inventory is the source of truth - syncs both UP and DOWN to match reality.
	 */
	private void validateAndSyncFlipQuantity(ActiveFlip flip, Map<Integer, Integer> totalItemCounts, String rsn)
	{
		int itemId = flip.getItemId();
		int activeFlipQty = flip.getTotalQuantity();
		int actualQty = totalItemCounts.getOrDefault(itemId, 0);
		
		// Skip if item is in an active BUY slot (still being purchased)
		if (isItemInActiveBuySlot(itemId))
		{
			log.debug("Skipping validation for {} - still in buy slot", flip.getItemName());
			return;
		}
		
		// Skip if quantities match
		if (actualQty == activeFlipQty)
		{
			return;
		}
		
		// Skip if we have 0 items - might be a stale flip or items elsewhere
		if (actualQty == 0)
		{
			log.debug("Skipping validation for {} - no items in inventory/sell slots", flip.getItemName());
			return;
		}
		
		// Check for significant difference (at least 10 items or 10% difference)
		int difference = Math.abs(activeFlipQty - actualQty);
		boolean significantDifference = difference >= 10 || (activeFlipQty > 0 && difference > activeFlipQty * 0.1);
		
		if (!significantDifference)
		{
			log.debug("Skipping validation for {} - difference of {} is not significant", flip.getItemName(), difference);
			return;
		}
		
		// Sync to actual quantity (both up and down)
		String direction = actualQty > activeFlipQty ? "up" : "down";
		log.info("Inventory quantity mismatch for {} - active flip shows {} but have {} (inv + sell slots). Syncing {}.",
			flip.getItemName(), activeFlipQty, actualQty, direction);
		
		int orderQty = flip.getOrderQuantity() > 0 ? flip.getOrderQuantity() : Math.max(activeFlipQty, actualQty);
		apiClient.syncActiveFlipAsync(
			itemId,
			flip.getItemName(),
			actualQty,
			orderQty,
			flip.getAverageBuyPrice(),
			rsn
		);
	}
	
	/**
	 * Check if an item is currently in an active BUY slot (still purchasing).
	 * Sell slots are okay - items there are "ours" and counted separately.
	 */
	private boolean isItemInActiveBuySlot(int itemId)
	{
		for (TrackedOffer offer : session.getTrackedOffers().values())
		{
			if (offer.getItemId() == itemId && offer.isBuy())
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Get counts of all items we own: inventory + items in GE sell slots.
	 * Must be called on client thread.
	 */
	private Map<Integer, Integer> getTotalItemCounts()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		
		// Count items in inventory
		ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item.getId() > 0)
				{
					counts.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		
		// Count items in active SELL slots (these are still "ours")
		for (TrackedOffer offer : session.getTrackedOffers().values())
		{
			if (!offer.isBuy() && offer.getItemId() > 0)
			{
				// For sell offers: total - sold = remaining in slot
				int remainingInSlot = offer.getTotalQuantity() - offer.getPreviousQuantitySold();
				if (remainingInSlot > 0)
				{
					counts.merge(offer.getItemId(), remainingInSlot, Integer::sum);
				}
			}
		}
		
		return counts;
	}
	
	/**
	 * Clean up stale active flips on the backend.
	 * Sends the list of item IDs that are "truly active" (in GE slots or inventory)
	 * to the API, which will mark all other active flips as closed.
	 */
	private void cleanupStaleActiveFlips()
	{
		// Must run on client thread to access game state
		clientThread.invokeLater(this::executeStaleFlipCleanup);
	}
	
	/**
	 * Execute the stale flip cleanup (must be called on client thread).
	 */
	private void executeStaleFlipCleanup()
	{
		java.util.Set<Integer> activeItemIds = collectAllActiveItemIds();
		
		log.info("Cleaning up stale flips - {} item IDs are truly active", activeItemIds.size());
		
		apiClient.cleanupStaleFlipsAsync(activeItemIds, getCurrentRsnSafe().orElse(null))
			.thenAccept(this::handleCleanupResult);
	}
	
	/**
	 * Collect all item IDs that are currently active (in GE slots or inventory).
	 * Must be called on client thread.
	 */
	private java.util.Set<Integer> collectAllActiveItemIds()
	{
		java.util.Set<Integer> activeItemIds = getActiveFlipItemIds();
		addInventoryItemIds(activeItemIds);
		return activeItemIds;
	}
	
	/**
	 * Add all item IDs from inventory to the given set.
	 * Must be called on client thread.
	 */
	private void addInventoryItemIds(java.util.Set<Integer> itemIds)
	{
		ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
		if (inventory == null)
		{
			return;
		}
		
		for (Item item : inventory.getItems())
		{
			if (item.getId() > 0)
			{
				itemIds.add(item.getId());
			}
		}
	}
	
	/**
	 * Handle the result of stale flip cleanup.
	 */
	private void handleCleanupResult(Boolean success)
	{
		if (Boolean.TRUE.equals(success))
		{
			log.info("Stale flip cleanup completed successfully");
			refreshActiveFlipsOnSwingThread();
		}
	}
	
	/**
	 * Refresh the active flips panel on the Swing EDT.
	 */
	private void refreshActiveFlipsOnSwingThread()
	{
		if (flipFinderPanel != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.refreshActiveFlips());
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
	
	/**
	 * Get the RSN-specific config key for persisted offers
	 */
	private String getPersistedOffersKey()
	{
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			return PERSISTED_OFFERS_KEY_PREFIX + UNKNOWN_RSN_FALLBACK;
		}
		return PERSISTED_OFFERS_KEY_PREFIX + session.getRsn();
	}
	
	private String getCollectedItemsKey()
	{
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			return COLLECTED_ITEMS_KEY_PREFIX + UNKNOWN_RSN_FALLBACK;
		}
		return COLLECTED_ITEMS_KEY_PREFIX + session.getRsn();
	}
	
	/**
	 * Load previously persisted collected item IDs from config.
	 * These are items that were bought but not yet sold when the player logged out.
	 */
	private java.util.Set<Integer> loadPersistedCollectedItems()
	{
		String key = getCollectedItemsKey();
		
		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, key);
			if (json == null || json.isEmpty())
			{
				return new java.util.HashSet<>();
			}
			
			Type type = new TypeToken<java.util.List<Integer>>(){}.getType();
			java.util.List<Integer> items = gson.fromJson(json, type);
			log.debug("Loaded {} persisted collected items for {}", items != null ? items.size() : 0, session.getRsn());
			return items != null ? new java.util.HashSet<>(items) : new java.util.HashSet<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted collected items for {}: {}", session.getRsn(), e.getMessage());
			return new java.util.HashSet<>();
		}
	}
	
	/**
	 * Load previously persisted tracked offers from config.
	 * These represent the GE offer state when the player last logged out.
	 */
	private Map<Integer, TrackedOffer> loadPersistedOffers()
	{
		String key = getPersistedOffersKey();
		
		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, key);
			if (json == null || json.isEmpty())
			{
				log.debug("No persisted offers found for {}", session.getRsn());
				return new HashMap<>();
			}
			
			Type type = new TypeToken<Map<Integer, TrackedOffer>>(){}.getType();
			Map<Integer, TrackedOffer> offers = gson.fromJson(json, type);
			log.info("Loaded {} persisted offers for {}", offers != null ? offers.size() : 0, session.getRsn());
			return offers != null ? offers : new HashMap<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted offers for {}: {}", session.getRsn(), e.getMessage());
			return new HashMap<>();
		}
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
			onBankContainerChanged();
		}
	}
	
	/**
	 * Handle bank container changes - attempt to capture snapshot when bank is opened.
	 */
	private void onBankContainerChanged()
	{
		// Don't attempt if snapshot is already in progress
		if (session.isBankSnapshotInProgress())
		{
			return;
		}
		
		// Enforce local cooldown to prevent spam
		long now = System.currentTimeMillis();
		if (now - session.getLastBankSnapshotAttempt() < BANK_SNAPSHOT_COOLDOWN_MS)
		{
			return;
		}
		
		// Must be logged in and have RSN
		String rsn = getCurrentRsnSafe().orElse(null);
		if (rsn == null)
		{
			return;
		}
		
		// Must be authenticated
		if (!apiClient.isAuthenticated())
		{
			return;
		}
		
		session.setLastBankSnapshotAttempt(now);
		session.setBankSnapshotInProgress(true);
		
		// Check rate limit first
		apiClient.checkBankSnapshotStatusAsync(rsn).thenAccept(status ->
		{
			if (status == null)
			{
				log.debug("Failed to check bank snapshot status");
				postBankSnapshotMessage("Failed to check snapshot status - will retry", true);
				session.setBankSnapshotInProgress(false);
				// Reset cooldown on failure to allow retry
				session.setLastBankSnapshotAttempt(0);
				return;
			}
			
			if (!status.isCanSnapshot())
			{
				log.debug("Bank snapshot not available: {}", status.getMessage());
				// Don't spam the user - only show message if they might be expecting a snapshot
				// The rate limit message from server contains the next available time
				session.setBankSnapshotInProgress(false);
				return;
			}
			
			// Rate limit passed - capture the snapshot on client thread
			clientThread.invokeLater(() -> captureBankSnapshot(rsn));
		}).exceptionally(e ->
		{
			log.debug("Error checking bank snapshot status: {}", e.getMessage());
			postBankSnapshotMessage("Connection error - will retry", true);
			session.setBankSnapshotInProgress(false);
			// Reset cooldown on network failure to allow retry
			session.setLastBankSnapshotAttempt(0);
			return null;
		});
	}
	
	/**
	 * Capture the current bank contents and send to API.
	 * Must be called on client thread.
	 */
	private void captureBankSnapshot(String rsn)
	{
		try
		{
			java.util.List<FlipSmartApiClient.BankItem> items = collectTradeableBankItems();

			if (items == null || items.isEmpty())
			{
				session.setBankSnapshotInProgress(false);
				return;
			}

			// Calculate additional wealth components for total wealth tracking
			long inventoryValue = calculateInventoryValue();
			long geOffersValue = calculateGEOffersValue();

			log.info("Capturing bank snapshot: {} tradeable items, inv={}, ge={} for RSN {}",
				items.size(), inventoryValue, geOffersValue, rsn);

			// Send snapshot to API with wealth components
			apiClient.createBankSnapshotAsync(rsn, items, inventoryValue, geOffersValue).thenAccept(response ->
			{
				if (response != null)
				{
					String wealthStr = String.format("%,d", response.getTotalWealth());
					log.info("Bank snapshot captured: {} items, {} GP total wealth",
						response.getItemCount(), wealthStr);
					postBankSnapshotMessage(
						String.format("Bank snapshot saved: %s GP total wealth", wealthStr),
						false);
				}
				else
				{
					log.debug("Failed to create bank snapshot");
					postBankSnapshotMessage("Failed to save bank snapshot", true);
				}
				session.setBankSnapshotInProgress(false);
			}).exceptionally(e ->
			{
				log.debug("Error creating bank snapshot: {}", e.getMessage());
				postBankSnapshotMessage("Connection error - will retry", true);
				session.setBankSnapshotInProgress(false);
				// Reset cooldown on network failure to allow retry
				session.setLastBankSnapshotAttempt(0);
				return null;
			});
		}
		catch (Exception e)
		{
			log.error("Error capturing bank snapshot: {}", e.getMessage());
			postBankSnapshotMessage("Error capturing bank data", true);
			session.setBankSnapshotInProgress(false);
		}
	}
	
	/**
	 * Post a bank snapshot message to game chat.
	 * @param message The message to display (without prefix)
	 * @param isError Whether this is an error message (changes color)
	 */
	private void postBankSnapshotMessage(String message, boolean isError)
	{
		ChatMessageBuilder builder = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Flip Smart] ")
			.append(isError ? ChatColorType.HIGHLIGHT : ChatColorType.NORMAL)
			.append(message);
		
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(builder.build())
			.build());
	}
	
	/**
	 * Collect all tradeable items from the bank with their GE prices.
	 * @return List of bank items, or null if bank is unavailable/empty
	 */
	private java.util.List<FlipSmartApiClient.BankItem> collectTradeableBankItems()
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			log.debug("Bank container is null - bank may have been closed");
			return null;
		}
		
		Item[] bankItems = bank.getItems();
		if (bankItems == null || bankItems.length == 0)
		{
			log.debug("Bank is empty");
			return null;
		}
		
		java.util.List<FlipSmartApiClient.BankItem> items = new java.util.ArrayList<>();
		
		for (Item item : bankItems)
		{
			FlipSmartApiClient.BankItem bankItem = toTradeableBankItem(item);
			if (bankItem != null)
			{
				items.add(bankItem);
			}
		}
		
		if (items.isEmpty())
		{
			log.debug("No tradeable items with prices found in bank");
		}
		
		return items;
	}
	
	/**
	 * Convert a bank item to a BankItem if it's tradeable and has a price, or if it's coins.
	 * @return BankItem or null if item should be skipped
	 */
	private FlipSmartApiClient.BankItem toTradeableBankItem(Item item)
	{
		int itemId = item.getId();
		int quantity = item.getQuantity();

		// Skip empty slots and placeholder items
		if (itemId <= 0 || quantity <= 0)
		{
			return null;
		}

		// Coins are worth 1 GP each
		if (itemId == COINS_ITEM_ID)
		{
			return new FlipSmartApiClient.BankItem(itemId, quantity, 1);
		}

		// Check if tradeable
		if (!ItemUtils.isTradeable(itemManager, itemId))
		{
			return null;
		}

		// Get GE price - itemManager uses cached wiki prices
		int gePrice = itemManager.getItemPrice(itemId);
		if (gePrice <= 0)
		{
			return null;
		}

		return new FlipSmartApiClient.BankItem(itemId, quantity, gePrice);
	}

	/**
	 * Calculate the total value of items in the player's inventory.
	 * Includes coins (as GP value) and tradeable items (at GE price).
	 * @return Total value in GP, or 0 if inventory is unavailable
	 */
	private long calculateInventoryValue()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null)
		{
			return 0;
		}

		long totalValue = 0;

		for (Item item : inventory.getItems())
		{
			int itemId = item.getId();
			int quantity = item.getQuantity();

			// Skip empty slots
			if (itemId <= 0 || quantity <= 0)
			{
				continue;
			}

			// Coins are worth their quantity in GP
			if (itemId == COINS_ITEM_ID)
			{
				totalValue += quantity;
				continue;
			}

			// Only include tradeable items
			if (!ItemUtils.isTradeable(itemManager, itemId))
			{
				continue;
			}

			// Get GE price
			int gePrice = itemManager.getItemPrice(itemId);
			if (gePrice > 0)
			{
				totalValue += (long) quantity * gePrice;
			}
		}

		return totalValue;
	}

	/**
	 * Calculate the total value locked in GE offers.
	 * For buy offers: GP committed (remaining budget) + value of items already bought (pending collection)
	 * For sell offers: Value of items still listed + GP already received (pending collection)
	 * @return Total value in GP
	 */
	private long calculateGEOffersValue()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return 0;
		}

		long totalValue = 0;

		for (GrandExchangeOffer offer : offers)
		{
			if (offer == null)
			{
				continue;
			}

			GrandExchangeOfferState state = offer.getState();
			if (state == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}

			int itemId = offer.getItemId();
			int price = offer.getPrice();
			int totalQty = offer.getTotalQuantity();
			int filledQty = offer.getQuantitySold();
			int remainingQty = totalQty - filledQty;

			// Get current GE price for items
			int itemPrice = itemManager.getItemPrice(itemId);
			if (itemPrice <= 0)
			{
				itemPrice = price; // Fallback to offer price
			}

			if (TrackedOffer.isBuyState(state))
			{
				// Buy offer: GP is locked for remaining qty + items pending collection
				long remainingBudget = (long) remainingQty * price;
				long filledItemsValue = (long) filledQty * itemPrice;
				totalValue += remainingBudget + filledItemsValue;
			}
			else
			{
				// Sell offer: Items are locked + GP pending collection
				long remainingItemsValue = (long) remainingQty * itemPrice;
				long filledGP = (long) filledQty * price;
				totalValue += remainingItemsValue + filledGP;
			}
		}

		return totalValue;
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		// Check if the GE offer setup screen was just built
		if (event.getScriptId() != ScriptID.GE_OFFERS_SETUP_BUILD)
		{
			return;
		}
		
		// Check if this is a SELL offer (type 1 = sell, type 0 = buy)
		int offerType = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE);
		if (offerType != 1)
		{
			// Not a sell offer, don't auto-focus
			return;
		}
		
		// Get the item ID being set up for sale
		int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId <= 0)
		{
			return;
		}
		
		// Check if we have an active flip for this item and auto-focus on it
		autoFocusOnActiveFlip(itemId);
	}
	
	/**
	 * Auto-focus on an active flip when the player sets up a sell offer for that item.
	 * This helps them see the recommended sell price without manually clicking in the panel.
	 */
	private void autoFocusOnActiveFlip(int itemId)
	{
		// When auto-recommend is active, it manages focus transitions itself.
		// Allowing this async call to set focus would race with onSellOrderPlaced(),
		// causing the sell overlay to reappear after auto-recommend clears it.
		if (autoRecommendService != null && autoRecommendService.isActive())
		{
			return;
		}

		String rsn = getCurrentRsnSafe().orElse(null);
		
		apiClient.getActiveFlipsAsync(rsn).thenAccept(response ->
		{
			if (response == null || response.getActiveFlips() == null)
			{
				return;
			}
			
			// Find and focus on matching active flip
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
	
	/**
	 * Find an active flip matching the given item ID.
	 */
	private ActiveFlip findActiveFlipForItem(java.util.List<ActiveFlip> flips, int itemId)
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
	
	/**
	 * Set the Flip Assist focus for selling an active flip.
	 * Prioritizes the panel's displayed sell price (which considers time thresholds
	 * and market conditions), then falls back to recommended price or minimum profitable.
	 */
	private void setFocusForSell(ActiveFlip flip)
	{
		int sellPrice;
		
		// First, check if the panel has a cached "smart" sell price for this item
		// This price considers time thresholds and current market conditions
		Integer panelPrice = flipFinderPanel != null ? flipFinderPanel.getDisplayedSellPrice(flip.getItemId()) : null;
		
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
			// Calculate minimum profitable sell price (breakeven + 1gp after tax)
			// GE tax is 2%, so: sellPrice * 0.98 >= buyPrice + 1
			// sellPrice >= (buyPrice + 1) / 0.98
			sellPrice = (int) Math.ceil((flip.getAverageBuyPrice() + 1) / 0.98);
			log.debug("Using calculated min profitable price for {}: {} gp", flip.getItemName(), sellPrice);
		}
		
		FocusedFlip focus = FocusedFlip.forSell(
			flip.getItemId(),
			flip.getItemName(),
			sellPrice,
			flip.getTotalQuantity()
		);
		
		flipAssistOverlay.setFocusedFlip(focus);
		log.info("Auto-focused on active flip for sell: {} @ {} gp", flip.getItemName(), sellPrice);
		
		if (flipFinderPanel != null)
		{
			javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.setExternalFocus(focus));
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerEvent)
	{
		final int slot = offerEvent.getSlot();
		final GrandExchangeOffer offer = offerEvent.getOffer();

		// Skip if game is not in LOGGED_IN state
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
		
		// Get item name (must be called on client thread)
		String itemName = ItemUtils.getItemName(itemManager, itemId);
		
		// Check if this is during the login burst window
		int currentTick = client.getTickCount();
		boolean isLoginBurst = (currentTick - session.getLastLoginTick()) <= GE_LOGIN_BURST_WINDOW;
		
		if (isLoginBurst && state != GrandExchangeOfferState.EMPTY)
		{
			// During login, just track existing offers without recording transactions
			log.debug("Login burst: initializing tracking for slot {} with {} items sold", slot, quantitySold);

			// Track the current state so future changes are detected correctly
			// Preserve existing timestamp if we already have this offer tracked
			TrackedOffer existing = session.getTrackedOffer(slot);
			session.putTrackedOffer(slot, TrackedOffer.createWithPreservedTimestamps(
				itemId, itemName, totalQuantity, price, quantitySold, existing, state));

			// Note: offline sync is now scheduled from LOGGED_IN state change
			// after syncRSN() to ensure correct RSN-specific config key
			return;
		}

		// Check if this is a buy or sell offer
		boolean isBuy = TrackedOffer.isBuyState(state);
		
		// Handle cancelled offers
		if (state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL)
		{
			// Only record the cancellation if some items were actually filled
			if (quantitySold > 0)
			{
				TrackedOffer previousOffer = session.getTrackedOffer(slot);
				
				// Check if we have any unfilled items that need to be recorded as cancelled
				if (previousOffer != null && quantitySold > previousOffer.getPreviousQuantitySold())
				{
					// Record the final partial fill before cancellation
					int newQuantity = quantitySold - previousOffer.getPreviousQuantitySold();
					int pricePerItem = spent / quantitySold;

					log.info("Recording final transaction before cancellation: {} {} x{}/{} @ {} gp each",
						isBuy ? "BUY" : "SELL",
						previousOffer.getItemName(),
						newQuantity,
						previousOffer.getTotalQuantity(),
						pricePerItem);

					// Get recommended sell price if available
					Integer recommendedSellPrice = isBuy ? session.getRecommendedPrice(itemId) : null;
					
					apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
						.builder(itemId, previousOffer.getItemName(), isBuy, newQuantity, pricePerItem)
						.geSlot(slot)
						.recommendedSellPrice(recommendedSellPrice)
						.rsn(getCurrentRsnSafe().orElse(null))
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
			
			// For cancelled BUY orders with 0 fills, dismiss the active flip
			// There's nothing to track - the order never filled
			if (isBuy)
			{
				log.info("Dismissing active flip for {} - buy order cancelled with 0 fills", itemName);
				apiClient.dismissActiveFlipAsync(itemId, getCurrentRsnSafe().orElse(null));
				
				// Refresh panel to remove the stale flip
				if (flipFinderPanel != null)
				{
					javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.refreshActiveFlips());
				}
			}
		}
		
		// For cancelled BUY orders with partial fills, track the items as collected
			// so they still show as active flips until the user sells them
			if (isBuy && quantitySold > 0)
			{
				TrackedOffer cancelledOffer = session.getTrackedOffer(slot);
				log.info("Cancelled buy order had {} items filled (ordered {}) - syncing actual quantity and tracking until sold", 
					quantitySold, cancelledOffer != null ? cancelledOffer.getTotalQuantity() : "?");
				session.addCollectedItem(itemId);
				
				// CRITICAL: Sync the actual filled quantity with the backend
				// When order is cancelled early, the backend still has the original order quantity
				// We need to update it to reflect the actual items received
				String rsn = getCurrentRsnSafe().orElse(null);
				if (rsn != null && cancelledOffer != null)
				{
					int pricePerItem = spent / quantitySold;
					log.info("Syncing cancelled order quantity to backend: {} x{} (was {})", 
						cancelledOffer.getItemName(), quantitySold, cancelledOffer.getTotalQuantity());
					apiClient.syncActiveFlipAsync(
						itemId,
						cancelledOffer.getItemName(),
						quantitySold,  // Actual filled quantity
						quantitySold,  // New order quantity = actual filled (order is complete now)
						pricePerItem,
						rsn
					);
				}
				
				// Refresh panel to show the flip with correct quantity
				if (flipFinderPanel != null)
				{
					javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.refreshActiveFlips());
				}
			}
			
			// Clean up tracked offer and recommended price
			session.removeTrackedOffer(slot);
			session.removeRecommendedPrice(itemId);
			return;
		}

		// Handle empty state (offer collected/cleared/modified)
		// This includes when user clicks "Modify Order" - items/gold return to inventory
		if (state == GrandExchangeOfferState.EMPTY)
		{
			TrackedOffer collectedOffer = session.removeTrackedOffer(slot);
			
			if (collectedOffer != null)
			{
				// Track collected buy offers so they still show as active flips until sold
				if (collectedOffer.isBuy() && collectedOffer.getPreviousQuantitySold() > 0)
				{
					log.info("Buy offer collected from GE: {} x{} - tracking until sold", 
						collectedOffer.getItemName(), collectedOffer.getPreviousQuantitySold());
					session.addCollectedItem(collectedOffer.getItemId());
					
					// Check if the order completed fully and we might have missed fills
					// This handles the case where fills happened rapidly or while we weren't tracking
					int inventoryCount = getInventoryCountForItem(collectedOffer.getItemId());
					int trackedFills = collectedOffer.getPreviousQuantitySold();
					
					// If inventory shows more items than we tracked, sync with backend
					if (inventoryCount > trackedFills)
					{
						log.info("Order for {} may have completed offline - tracked {} fills but have {} in inventory. Syncing.",
							collectedOffer.getItemName(), trackedFills, inventoryCount);
						
						String rsn = getCurrentRsnSafe().orElse(null);
						if (rsn != null)
						{
							// Use the inventory count as the actual fill count
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
				// Handle SELL orders that go empty (modified/cancelled with items returned to inventory)
				// This is critical for "Modify Order" on sell orders - items go back to inventory
				// and should still be tracked as an active flip until actually sold
				else if (!collectedOffer.isBuy())
				{
					// Items returned to inventory from a sell order (modify/cancel)
					// Check if we have items of this type in inventory to track
					int inventoryCount = getInventoryCountForItem(collectedOffer.getItemId());
					if (inventoryCount > 0)
					{
						log.info("Sell offer collected/modified for {}: {} items returned to inventory - keeping active flip tracking", 
							collectedOffer.getItemName(), inventoryCount);
						session.addCollectedItem(collectedOffer.getItemId());
					}
					else
					{
						log.info("Sell offer for {} went empty with no items in inventory - order may have fully sold", 
							collectedOffer.getItemName());
					}
				}
				// Handle BUY orders with 0 fills that might have filled while we weren't tracking
				// This covers edge cases where fills happened between login and tracking
				else if (collectedOffer.isBuy() && collectedOffer.getPreviousQuantitySold() == 0)
				{
					int inventoryCount = getInventoryCountForItem(collectedOffer.getItemId());
					if (inventoryCount > 0)
					{
						log.info("Buy order for {} went empty but found {} items in inventory - may have filled offline", 
							collectedOffer.getItemName(), inventoryCount);
						session.addCollectedItem(collectedOffer.getItemId());
					}
				}
				
				// Ensure a recommended sell price exists for buy collections.
				// The price may have been lost (plugin restart, queue refresh, etc.).
				// Calculate a minimum profitable sell price as fallback so auto-recommend
				// can always transition to the sell step after collecting a buy.
				if (collectedOffer.isBuy() && collectedOffer.getPreviousQuantitySold() > 0
					&& session.getRecommendedPrice(collectedOffer.getItemId()) == null)
				{
					int buyPrice = collectedOffer.getPrice();
					int fallbackSellPrice = (int) Math.ceil((buyPrice + 1) / 0.98);
					session.setRecommendedPrice(collectedOffer.getItemId(), fallbackSellPrice);
					log.info("No recommended sell price for {} - using fallback {} gp (bought at {} gp)",
						collectedOffer.getItemName(), fallbackSellPrice, buyPrice);
				}

				// Notify auto-recommend service of collection
				if (autoRecommendService != null && autoRecommendService.isActive())
				{
					autoRecommendService.onOfferCollected(
						collectedOffer.getItemId(),
						collectedOffer.isBuy(),
						collectedOffer.getItemName(),
						collectedOffer.getPreviousQuantitySold()
					);
				}

				// Refresh panel to show updated state
				if (flipFinderPanel != null)
				{
					scheduleOneShot(TRANSACTION_REFRESH_DELAY_MS, () -> flipFinderPanel.refresh());
				}
			}
			return;
		}

		// Get the previously tracked offer for this slot
		TrackedOffer previousOffer = session.getTrackedOffer(slot);

		// Detect if quantity sold has increased (partial or full fill)
		if (quantitySold > 0)
		{
			int newQuantity = 0;

			if (previousOffer != null)
			{
				// Calculate how many items were just sold/bought
				newQuantity = quantitySold - previousOffer.getPreviousQuantitySold();
			}
			else
			{
				// First time seeing this offer with sold items — immediate fill on placement
				newQuantity = quantitySold;

				// For immediate-fill buys, pre-store the recommended sell price from auto-recommend
				// BEFORE the transaction recording reads it. The queue advance (onBuyOrderPlaced)
				// is deferred until AFTER putTrackedOffer so hasAvailableGESlots() is accurate.
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

			// Record transaction if we have new items
			if (newQuantity > 0)
			{
				// Calculate the actual price per item from the spent amount
				int pricePerItem = spent / quantitySold;

				log.info("Recording transaction: {} {} x{} @ {} gp each (slot {}, {}/{} filled)",
					isBuy ? "BUY" : "SELL",
					itemName,
					newQuantity,
					pricePerItem,
					slot,
					quantitySold,
					totalQuantity);

				// Get recommended sell price if this was a buy from a recommendation
				Integer recommendedSellPrice = isBuy ? session.getRecommendedPrice(itemId) : null;
				
				// Record the transaction asynchronously with total order quantity
				apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
					.builder(itemId, itemName, isBuy, newQuantity, pricePerItem)
					.geSlot(slot)
					.recommendedSellPrice(recommendedSellPrice)
					.rsn(getCurrentRsnSafe().orElse(null))
					.totalQuantity(totalQuantity)
					.build());
				
				// If this was a sell, remove from collected tracking
				if (!isBuy)
				{
					markItemSold(itemId);

					// Note: Sale completion webhooks are sent by the backend when a flip is
					// detected (with full profit/ROI data). No plugin-side notification needed.
				}

				// Refresh active flips panel if it exists
				// Use a tracked one-shot timer to add a small delay without blocking the EDT
				if (flipFinderPanel != null)
				{
					scheduleOneShot(TRANSACTION_REFRESH_DELAY_MS, () -> flipFinderPanel.refresh());
				}
			}

			// Notify auto-recommend service on order completion
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

			// Update tracked offer - preserve original timestamp to prevent timer reset
			session.putTrackedOffer(slot, TrackedOffer.createWithPreservedTimestamps(
				itemId, itemName, totalQuantity, price, quantitySold, previousOffer, state));

			// Handle immediate-fill auto-recommend transitions AFTER tracked offer is stored,
			// so hasAvailableGESlots() correctly counts this slot as occupied.
			if (previousOffer == null && totalQuantity > 0)
			{
				if (isBuy && autoRecommendService != null && autoRecommendService.isActive())
				{
					log.info("Immediate-fill buy for {} - advancing auto-recommend queue", itemName);
					autoRecommendService.onBuyOrderPlaced(itemId);
				}
				else if (!isBuy)
				{
					log.info("Immediate-fill sell for {} - performing sell bookkeeping", itemName);
					String rsn = getCurrentRsnSafe().orElse(null);
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
		}
		else
		{
			// New offer with no items sold yet, track it with fresh timestamp
			session.putTrackedOffer(slot, new TrackedOffer(itemId, itemName, isBuy, totalQuantity, price, 0));
			
			// Clear Flip Assist focus if this order matches the focused flip
			clearFlipAssistFocusIfMatches(itemId, isBuy);
			
			// Record new buy orders to the API (even with 0 fills) so webapp can track them
			if (isBuy && totalQuantity > 0 && previousOffer == null)
			{
				log.debug("Recording new buy order: {} x{} @ {} gp each (slot {}, 0/{} filled)",
					itemName, 0, price, slot, totalQuantity);

				// Notify auto-recommend FIRST so it stores the recommended sell price
				// before we read it for the transaction recording
				if (autoRecommendService != null && autoRecommendService.isActive())
				{
					autoRecommendService.onBuyOrderPlaced(itemId);
				}

				Integer recommendedSellPrice = session.getRecommendedPrice(itemId);

				apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
					.builder(itemId, itemName, true, 0, price)
					.geSlot(slot)
					.recommendedSellPrice(recommendedSellPrice)
					.rsn(getCurrentRsnSafe().orElse(null))
					.totalQuantity(totalQuantity)
					.build());
			}

			// When a SELL order is placed, mark the active flip as "selling" phase
			// This updates the backend so the webapp shows the correct phase
			if (!isBuy && totalQuantity > 0 && previousOffer == null)
			{
				log.info("Sell order placed for {} x{} - marking active flip as selling", itemName, totalQuantity);
				String rsn = getCurrentRsnSafe().orElse(null);
				if (rsn != null)
				{
					apiClient.markActiveFlipSellingAsync(itemId, rsn);
				}

				// Clean up recommended price - it has been consumed by the sell order
				session.removeRecommendedPrice(itemId);

				// Notify auto-recommend service of sell order placed
				if (autoRecommendService != null && autoRecommendService.isActive())
				{
					autoRecommendService.onSellOrderPlaced(itemId);
				}
			}
			
			// Refresh the flip finder panel when any new order is submitted
			// This ensures sell orders show up immediately in active flips
			if (previousOffer == null && flipFinderPanel != null)
			{
				javax.swing.SwingUtilities.invokeLater(() -> {
					flipFinderPanel.updatePendingOrders(getPendingBuyOrders());
					// Also refresh active flips to pick up new sell orders
					flipFinderPanel.refreshActiveFlips();
				});
			}
		}
	}
	
	/**
	 * Clear the Flip Assist focus if the submitted order matches the focused item
	 */
	private void clearFlipAssistFocusIfMatches(int itemId, boolean isBuy)
	{
		// When auto-recommend is active, it manages focus transitions itself
		if (autoRecommendService != null && autoRecommendService.isActive())
		{
			return;
		}

		FocusedFlip focusedFlip = flipAssistOverlay.getFocusedFlip();
		if (focusedFlip == null)
		{
			return;
		}

		// Clear focus if the item matches and the order type matches the step
		if (focusedFlip.getItemId() == itemId)
		{
			boolean stepMatches = (isBuy && focusedFlip.isBuying()) || (!isBuy && focusedFlip.isSelling());
			if (stepMatches)
			{
				log.info("Clearing Flip Assist focus - order submitted for {} ({})",
					focusedFlip.getItemName(), isBuy ? "BUY" : "SELL");
				flipAssistOverlay.clearFocus();

				// Also update the panel's visual state
				if (flipFinderPanel != null)
				{
					javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.clearFocus());
				}
			}
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

