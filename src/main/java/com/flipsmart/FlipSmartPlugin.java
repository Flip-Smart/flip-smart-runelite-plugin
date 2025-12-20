package com.flipsmart;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.config.ConfigManager;
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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@PluginDescriptor(
	name = "Flip Smart",
	description = "A tool to help with item flipping in the Grand Exchange",
	tags = {"grand exchange", "flipping", "trading", "money making"}
)
public class FlipSmartPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private FlipSmartConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private GrandExchangeOverlay geOverlay;

	@Inject
	private FlipSmartApiClient apiClient;

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

	// Flip Finder panel
	private FlipFinderPanel flipFinderPanel;
	private net.runelite.client.ui.NavigationButton flipFinderNavButton;

	// Player's current cash stack (detected from inventory)
	@Getter
	private int currentCashStack = 0;

	// Auto-refresh timer for flip finder
	private java.util.Timer flipFinderRefreshTimer;
	private long lastFlipFinderRefresh = 0;

	// Track GE offers to detect when they complete
	private final Map<Integer, TrackedOffer> trackedOffers = new ConcurrentHashMap<>();
	
	// Track login to avoid recording existing offers as new transactions
	private static final int GE_LOGIN_BURST_WINDOW = 3; // ticks
	private int lastLoginTick = 0;
	
	// Track recommended prices from flip finder (item_id -> recommended_sell_price)
	private final Map<Integer, Integer> recommendedPrices = new ConcurrentHashMap<>();

	// Config keys for persisting offer state
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String PERSISTED_OFFERS_KEY_PREFIX = "persistedOffers_";
	
	// Flag to track if we've synced offline fills on this login
	private boolean offlineSyncCompleted = false;
	
	// Current RSN (set on login)
	@Getter
	private String currentRsn = null;
	
	// Track items collected from GE in current session (waiting to be sold)
	// These should show as active flips even though they're no longer in GE slots
	private final java.util.Set<Integer> collectedItemIds = ConcurrentHashMap.newKeySet();

	/**
	 * Helper class to track GE offers (serializable for persistence)
	 */
	public static class TrackedOffer
	{
		int itemId;
		String itemName;
		boolean isBuy;
		int totalQuantity;
		int price;
		int previousQuantitySold;

		// Default constructor for Gson deserialization
		TrackedOffer() {}

		TrackedOffer(int itemId, String itemName, boolean isBuy, int totalQuantity, int price, int quantitySold)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.isBuy = isBuy;
			this.totalQuantity = totalQuantity;
			this.price = price;
			this.previousQuantitySold = quantitySold;
		}
	}
	
	/**
	 * Store recommended sell price when user views/acts on a flip recommendation
	 */
	public void setRecommendedSellPrice(int itemId, int recommendedSellPrice)
	{
		recommendedPrices.put(itemId, recommendedSellPrice);
		log.debug("Stored recommended sell price for item {}: {}", itemId, recommendedSellPrice);
	}
	
	/**
	 * Get current pending buy orders (placed but not filled yet)
	 */
	public java.util.List<PendingOrder> getPendingBuyOrders()
	{
		java.util.List<PendingOrder> pendingOrders = new java.util.ArrayList<>();
		
		for (java.util.Map.Entry<Integer, TrackedOffer> entry : trackedOffers.entrySet())
		{
			TrackedOffer offer = entry.getValue();
			
			// Only include buy orders with 0 fills
			if (offer.isBuy && offer.previousQuantitySold == 0)
			{
				Integer recommendedSellPrice = recommendedPrices.get(offer.itemId);
				
				PendingOrder pending = new PendingOrder(
					offer.itemId,
					offer.itemName, // Use cached name
					offer.totalQuantity,
					offer.price,
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
		java.util.Set<Integer> itemIds = new java.util.HashSet<>();
		
		for (TrackedOffer offer : trackedOffers.values())
		{
			if (offer.isBuy)
			{
				itemIds.add(offer.itemId);
			}
		}
		
		return itemIds;
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
		java.util.Set<Integer> itemIds = new java.util.HashSet<>();
		
		// Add items currently in ANY GE slots (buy OR sell)
		// Items in sell slots are still part of an active flip until sold
		for (TrackedOffer offer : trackedOffers.values())
		{
			itemIds.add(offer.itemId);
		}
		
		// Add items collected from GE (waiting to be sold)
		itemIds.addAll(collectedItemIds);
		
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
		if (collectedItemIds.remove(itemId))
		{
			log.debug("Removed item {} from collected tracking (sold)", itemId);
		}
		
		// Check if this item is still in a GE sell slot (not yet fully sold)
		boolean hasActiveSellSlot = false;
		for (TrackedOffer offer : trackedOffers.values())
		{
			if (offer.itemId == itemId && !offer.isBuy)
			{
				hasActiveSellSlot = true;
				break;
			}
		}
		
		// Only auto-close if inventory is empty AND there's no active sell slot
		// This prevents closing flips that are still selling in the GE
		if (!hasActiveSellSlot)
		{
			int inventoryCount = getInventoryCountForItem(itemId);
			if (inventoryCount == 0)
			{
				log.info("Inventory empty and no active sell slot for item {}, auto-closing active flip", itemId);
				apiClient.dismissActiveFlipAsync(itemId, currentRsn).thenAccept(success ->
				{
					if (Boolean.TRUE.equals(success))
					{
						log.info("Successfully auto-closed active flip for item {} (no items remaining)", itemId);
						// Refresh the panel to reflect the change
						if (flipFinderPanel != null)
						{
							javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.refresh());
						}
					}
				});
			}
		}
		else
		{
			log.debug("Item {} still has active sell slot, keeping flip open", itemId);
		}
	}
	
	/**
	 * Get the count of a specific item in the player's inventory
	 * @param itemId The item ID to check
	 * @return The quantity of the item in inventory (0 if not found)
	 */
	private int getInventoryCountForItem(int itemId)
	{
		ItemContainer inventory = client.getItemContainer(93); // 93 = inventory
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
	
	/**
	 * Helper class for pending orders
	 */
	public static class PendingOrder
	{
		public final int itemId;
		public final String itemName;
		public final int quantity;
		public final int pricePerItem;
		public final Integer recommendedSellPrice;
		public final int slot;
		
		public PendingOrder(int itemId, String itemName, int quantity, int pricePerItem, Integer recommendedSellPrice, int slot)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
			this.pricePerItem = pricePerItem;
			this.recommendedSellPrice = recommendedSellPrice;
			this.slot = slot;
		}
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Flip Smart started!");
		overlayManager.add(geOverlay);
		mouseManager.registerMouseListener(overlayMouseListener);
		
		// Initialize Flip Finder panel
		if (config.showFlipFinder())
		{
			initializeFlipFinderPanel();
		}

		// Start auto-refresh timer for flip finder
		startFlipFinderRefreshTimer();
		
		// Note: Cash stack and RSN will be synced when player logs in via onGameStateChanged
		// Don't access client data during startup - must be on client thread
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Flip Smart stopped!");
		overlayManager.remove(geOverlay);
		mouseManager.unregisterMouseListener(overlayMouseListener);
		
		// Remove flip finder panel
		if (flipFinderNavButton != null)
		{
			clientToolbar.removeNavigation(flipFinderNavButton);
		}
		
		// Stop auto-refresh timer
		stopFlipFinderRefreshTimer();
		
		// Clear API client cache
		apiClient.clearCache();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState gameState = gameStateChanged.getGameState();
		
		// Track login/hopping to avoid recording existing GE offers
		if (gameState == GameState.LOGGING_IN || gameState == GameState.HOPPING || gameState == GameState.CONNECTION_LOST)
		{
			lastLoginTick = client.getTickCount();
			offlineSyncCompleted = false;
			collectedItemIds.clear(); // Reset collected tracking on login
			log.debug("Login state change detected, setting lastLoginTick to {}", lastLoginTick);
		}
		
		// Persist offer state when logging out
		if (gameState == GameState.LOGIN_SCREEN)
		{
			persistOfferState();
		}
		
		if (gameState == GameState.LOGGED_IN)
		{
			log.info("Player logged in");
			syncRSN();
			updateCashStack();
			
			// Schedule offline sync after a delay to ensure all GE events have been processed
			// This must run AFTER syncRSN() so we have the correct RSN for the config key
			if (!offlineSyncCompleted)
			{
				javax.swing.Timer syncTimer = new javax.swing.Timer(2000, e -> syncOfflineFills());
				syncTimer.setRepeats(false);
				syncTimer.start();
			}
			
			// Refresh flip finder with current cash stack
			if (flipFinderPanel != null)
			{
				flipFinderPanel.refresh();
			}
		}
	}
	
	/**
	 * Persist the current GE offer state to config for offline tracking.
	 * Called when the player logs out.
	 * Uses RSN-specific key to support multiple accounts.
	 */
	private void persistOfferState()
	{
		String key = getPersistedOffersKey();
		
		if (trackedOffers.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, key);
			log.debug("No tracked offers to persist for {}", currentRsn);
			return;
		}
		
		try
		{
			// Convert to a regular HashMap for serialization
			Map<Integer, TrackedOffer> offersToSave = new HashMap<>(trackedOffers);
			String json = gson.toJson(offersToSave);
			configManager.setConfiguration(CONFIG_GROUP, key, json);
			log.info("Persisted {} tracked offers for {} (offline sync)", offersToSave.size(), currentRsn);
		}
		catch (Exception e)
		{
			log.error("Failed to persist offer state for {}: {}", currentRsn, e.getMessage());
		}
	}
	
	/**
	 * Load previously persisted offer state from config.
	 * Called during login burst to compare with current state.
	 * Uses RSN-specific key to support multiple accounts.
	 */
	private Map<Integer, TrackedOffer> loadPersistedOfferState()
	{
		String key = getPersistedOffersKey();
		
		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, key);
			if (json == null || json.isEmpty())
			{
				return new HashMap<>();
			}
			
			Type type = new TypeToken<Map<Integer, TrackedOffer>>(){}.getType();
			Map<Integer, TrackedOffer> offers = gson.fromJson(json, type);
			log.debug("Loaded {} persisted offers for {}", offers != null ? offers.size() : 0, currentRsn);
			return offers != null ? offers : new HashMap<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted offer state for {}: {}", currentRsn, e.getMessage());
			return new HashMap<>();
		}
	}
	
	/**
	 * Sync fills that occurred while offline by comparing persisted state to current state.
	 * Should be called after all login burst offer events have been processed.
	 */
	private void syncOfflineFills()
	{
		if (offlineSyncCompleted)
		{
			return;
		}
		offlineSyncCompleted = true;
		
		Map<Integer, TrackedOffer> persistedOffers = loadPersistedOfferState();
		if (persistedOffers.isEmpty())
		{
			log.debug("No persisted offers to compare for offline sync");
			return;
		}
		
		log.info("Checking {} persisted offers against {} current offers for offline fills",
			persistedOffers.size(), trackedOffers.size());
		
		for (Map.Entry<Integer, TrackedOffer> entry : trackedOffers.entrySet())
		{
			int slot = entry.getKey();
			TrackedOffer currentOffer = entry.getValue();
			TrackedOffer persistedOffer = persistedOffers.get(slot);
			
			if (persistedOffer == null)
			{
				// New offer placed while offline (unlikely but possible)
				continue;
			}
			
			// Check if the same item in the same slot
			if (persistedOffer.itemId != currentOffer.itemId)
			{
				log.debug("Slot {} has different item (was {}, now {}), skipping offline sync for this slot",
					slot, persistedOffer.itemId, currentOffer.itemId);
				continue;
			}
			
			// Check if more items were sold while offline
			int offlineFills = currentOffer.previousQuantitySold - persistedOffer.previousQuantitySold;
			if (offlineFills > 0)
			{
				log.info("Detected {} offline fills for {} (slot {}): {} -> {}",
					offlineFills,
					currentOffer.itemName,
					slot,
					persistedOffer.previousQuantitySold,
					currentOffer.previousQuantitySold);
				
				// Record the offline transaction
				Integer recommendedSellPrice = currentOffer.isBuy ? recommendedPrices.get(currentOffer.itemId) : null;
				
				apiClient.recordTransactionAsync(
					currentOffer.itemId,
					currentOffer.itemName,
					currentOffer.isBuy,
					offlineFills,
					currentOffer.price,
					slot,
					recommendedSellPrice,
					currentRsn
				);
			}
		}
		
		// Check for offers that completed (became empty) while offline
		for (Map.Entry<Integer, TrackedOffer> entry : persistedOffers.entrySet())
		{
			int slot = entry.getKey();
			TrackedOffer persistedOffer = entry.getValue();
			
			if (!trackedOffers.containsKey(slot))
			{
				// This slot is now empty - the offer either completed, was cancelled, or was collected while offline
				// We CANNOT safely assume it completed with all remaining items - it may have been cancelled
				// with only partial fills. The safest approach is to NOT auto-record anything here.
				// The actual fill amount should have been recorded when the items were collected in-game.
				log.info("Slot {} is now empty (was tracking {} x{}). Cannot determine if completed or cancelled offline - skipping auto-record.",
					slot, persistedOffer.itemName, persistedOffer.totalQuantity);
				
				// If this was a buy with some items already filled, add to collected tracking
				// so the user can still sell those items and complete the flip
				if (persistedOffer.isBuy && persistedOffer.previousQuantitySold > 0)
				{
					log.info("Adding {} to collected tracking (had {} items filled before going offline)",
						persistedOffer.itemName, persistedOffer.previousQuantitySold);
					collectedItemIds.add(persistedOffer.itemId);
				}
			}
		}
		
		// Clear persisted state after sync (RSN-specific key)
		configManager.unsetConfiguration(CONFIG_GROUP, getPersistedOffersKey());
		log.info("Offline sync completed for {}", currentRsn);
		
		// Refresh the flip finder panel to show updated data after offline sync
		if (flipFinderPanel != null)
		{
			javax.swing.Timer refreshTimer = new javax.swing.Timer(1000, e -> flipFinderPanel.refresh());
			refreshTimer.setRepeats(false);
			refreshTimer.start();
		}
	}

	
	/**
	 * Sync the player's RSN with the API and store locally
	 */
	private void syncRSN()
	{
		if (client.getLocalPlayer() == null)
		{
			return;
		}
		
		String rsn = client.getLocalPlayer().getName();
		if (rsn != null && !rsn.isEmpty())
		{
			currentRsn = rsn;
			log.debug("Syncing RSN: {}", rsn);
			apiClient.updateRSN(rsn);
		}
	}
	
	/**
	 * Get the RSN-specific config key for persisted offers
	 */
	private String getPersistedOffersKey()
	{
		if (currentRsn == null || currentRsn.isEmpty())
		{
			return PERSISTED_OFFERS_KEY_PREFIX + "unknown";
		}
		return PERSISTED_OFFERS_KEY_PREFIX + currentRsn;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Only process inventory changes
		if (event.getContainerId() != 93) // 93 is the inventory container ID
		{
			return;
		}

		updateCashStack();
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
		String itemName = itemManager.getItemComposition(itemId).getName();
		
		// Check if this is during the login burst window
		int currentTick = client.getTickCount();
		boolean isLoginBurst = (currentTick - lastLoginTick) <= GE_LOGIN_BURST_WINDOW;
		
		if (isLoginBurst && state != GrandExchangeOfferState.EMPTY)
		{
			// During login, just track existing offers without recording transactions
			log.debug("Login burst: initializing tracking for slot {} with {} items sold", slot, quantitySold);
			
			boolean isBuy = state == GrandExchangeOfferState.BUYING || 
							state == GrandExchangeOfferState.BOUGHT ||
							state == GrandExchangeOfferState.CANCELLED_BUY;
			
			// Track the current state so future changes are detected correctly
			trackedOffers.put(slot, new TrackedOffer(itemId, itemName, isBuy, totalQuantity, price, quantitySold));
			
			// Note: offline sync is now scheduled from LOGGED_IN state change
			// after syncRSN() to ensure correct RSN-specific config key
			return;
		}

		// Check if this is a buy or sell offer
		boolean isBuy = state == GrandExchangeOfferState.BUYING || 
						state == GrandExchangeOfferState.BOUGHT ||
						state == GrandExchangeOfferState.CANCELLED_BUY;
		
		// Handle cancelled offers
		if (state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL)
		{
			// Only record the cancellation if some items were actually filled
			if (quantitySold > 0)
			{
				TrackedOffer previousOffer = trackedOffers.get(slot);
				
				// Check if we have any unfilled items that need to be recorded as cancelled
				if (previousOffer != null && quantitySold > previousOffer.previousQuantitySold)
				{
					// Record the final partial fill before cancellation
					int newQuantity = quantitySold - previousOffer.previousQuantitySold;
					int pricePerItem = spent / quantitySold;

					log.info("Recording final transaction before cancellation: {} {} x{} @ {} gp each",
						isBuy ? "BUY" : "SELL",
						previousOffer.itemName,
						newQuantity,
						pricePerItem);

					// Get recommended sell price if available
					Integer recommendedSellPrice = isBuy ? recommendedPrices.get(itemId) : null;
					
					apiClient.recordTransactionAsync(
						itemId,
						previousOffer.itemName,
						isBuy,
						newQuantity,
						pricePerItem,
						slot,
						recommendedSellPrice,
						currentRsn
					);
				}
				
				log.info("Order cancelled: {} {} - {} items filled out of {}",
					isBuy ? "BUY" : "SELL",
					previousOffer != null ? previousOffer.itemName : itemName,
					quantitySold,
					totalQuantity);
			}
			else
			{
				TrackedOffer previousOffer = trackedOffers.get(slot);
				log.info("Order cancelled with no fills: {} {}",
					isBuy ? "BUY" : "SELL",
					previousOffer != null ? previousOffer.itemName : itemName);
			}
			
			// For cancelled BUY orders with partial fills, track the items as collected
			// so they still show as active flips until the user sells them
			if (isBuy && quantitySold > 0)
			{
				log.info("Cancelled buy order had {} items filled - tracking until sold", quantitySold);
				collectedItemIds.add(itemId);
				
				// Refresh panel to show the flip is still active
				if (flipFinderPanel != null)
				{
					javax.swing.SwingUtilities.invokeLater(() -> flipFinderPanel.refreshActiveFlips());
				}
			}
			
			// Clean up tracked offer
			trackedOffers.remove(slot);
			return;
		}
		
		// Handle empty state (offer collected/cleared)
		if (state == GrandExchangeOfferState.EMPTY)
		{
			TrackedOffer collectedOffer = trackedOffers.remove(slot);
			
			// Track collected buy offers so they still show as active flips until sold
			if (collectedOffer != null && collectedOffer.isBuy && collectedOffer.previousQuantitySold > 0)
			{
				log.info("Buy offer collected from GE: {} x{} - tracking until sold", 
					collectedOffer.itemName, collectedOffer.previousQuantitySold);
				collectedItemIds.add(collectedOffer.itemId);
				
				// Refresh panel to show updated state
				if (flipFinderPanel != null)
				{
					javax.swing.Timer refreshTimer = new javax.swing.Timer(500, e -> flipFinderPanel.refresh());
					refreshTimer.setRepeats(false);
					refreshTimer.start();
				}
			}
			return;
		}

		// Get the previously tracked offer for this slot
		TrackedOffer previousOffer = trackedOffers.get(slot);

		// Detect if quantity sold has increased (partial or full fill)
		if (quantitySold > 0)
		{
			int newQuantity = 0;

			if (previousOffer != null)
			{
				// Calculate how many items were just sold/bought
				newQuantity = quantitySold - previousOffer.previousQuantitySold;
			}
			else
			{
				// First time seeing this offer with sold items
				newQuantity = quantitySold;
			}

			// Record transaction if we have new items
			if (newQuantity > 0)
			{
				// Calculate the actual price per item from the spent amount
				int pricePerItem = spent / quantitySold;

				log.info("Recording transaction: {} {} x{} @ {} gp each (slot {}, {}/{})",
					isBuy ? "BUY" : "SELL",
					itemName,
					newQuantity,
					pricePerItem,
					slot,
					quantitySold,
					totalQuantity);

				// Get recommended sell price if this was a buy from a recommendation
				Integer recommendedSellPrice = isBuy ? recommendedPrices.get(itemId) : null;
				
				// Record the transaction asynchronously
				apiClient.recordTransactionAsync(
					itemId,
					itemName,
					isBuy,
					newQuantity,
					pricePerItem,
					slot,
					recommendedSellPrice,
					currentRsn
				);
				
				// Clear recommended price after recording (only for buys)
				if (isBuy && recommendedSellPrice != null)
				{
					recommendedPrices.remove(itemId);
				}
				
				// If this was a sell, remove from collected tracking
				if (!isBuy)
				{
					markItemSold(itemId);
				}

				// Refresh active flips panel if it exists
				// Use a Swing Timer to add a small delay without blocking the EDT
				if (flipFinderPanel != null)
				{
					javax.swing.Timer refreshTimer = new javax.swing.Timer(500, e -> {
						// This will update both pending orders and active flips
						flipFinderPanel.refresh();
					});
					refreshTimer.setRepeats(false);
					refreshTimer.start();
				}
			}

			// Update tracked offer
			trackedOffers.put(slot, new TrackedOffer(itemId, itemName, isBuy, totalQuantity, price, quantitySold));
		}
		else
		{
			// New offer with no items sold yet, track it
			trackedOffers.put(slot, new TrackedOffer(itemId, itemName, isBuy, totalQuantity, price, 0));
			
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
	 * Initialize the Flip Finder panel and add it to the sidebar
	 */
	private void initializeFlipFinderPanel()
	{
		flipFinderPanel = new FlipFinderPanel(config, apiClient, itemManager, this, configManager)
		{
			@Override
			protected Integer getCashStack()
			{
				return currentCashStack > 0 ? currentCashStack : null;
			}
		};

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
		ItemContainer inventory = client.getItemContainer(93); // 93 = inventory
		if (inventory == null)
		{
			currentCashStack = 0;
			return;
		}

		int totalCash = 0;
		Item[] items = inventory.getItems();

		// Item IDs for coins
		final int COINS_995 = 995;

		for (Item item : items)
		{
			if (item.getId() == COINS_995)
			{
				totalCash += item.getQuantity();
			}
		}

		if (totalCash != currentCashStack)
		{
			currentCashStack = totalCash;
			log.debug("Updated cash stack: {}", currentCashStack);

			// If cash stack changed significantly and we have a flip finder panel, refresh it
			if (flipFinderPanel != null && totalCash > 100_000)
			{
				// Only auto-refresh if it's been more than 30 seconds since last refresh
				long now = System.currentTimeMillis();
				if (now - lastFlipFinderRefresh > 30_000)
				{
					lastFlipFinderRefresh = now;
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
				if (flipFinderPanel != null && config.showFlipFinder())
				{
					javax.swing.SwingUtilities.invokeLater(() ->
					{
						log.debug("Auto-refreshing flip finder");
						lastFlipFinderRefresh = System.currentTimeMillis();
						flipFinderPanel.refresh();
					});
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

	@Provides
	FlipSmartConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipSmartConfig.class);
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

