package com.flipsmart;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
	private TooltipManager tooltipManager;

	@Inject
	private FlipSmartOverlay overlay;

	@Inject
	private FlipSmartInventoryOverlay inventoryOverlay;

	@Inject
	private FlipSmartApiClient apiClient;

	@Inject
	private net.runelite.client.ui.ClientToolbar clientToolbar;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	// Store analysis results for items
	@Getter
	private final Map<Integer, FlipAnalysis> itemAnalysisCache = new ConcurrentHashMap<>();

	// Track which items we've already requested analysis for
	private final Set<Integer> pendingAnalysisRequests = new HashSet<>();

	// Currently hovered item for display in overlay
	@Getter
	private volatile FlipAnalysis hoveredItemAnalysis;

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

	/**
	 * Helper class to track GE offers
	 */
	private static class TrackedOffer
	{
		int itemId;
		String itemName;
		boolean isBuy;
		int totalQuantity;
		int price;
		int previousQuantitySold;

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

	/**
	 * Set the currently hovered item analysis for display in the overlay
	 */
	public void setHoveredItemAnalysis(FlipAnalysis analysis)
	{
		this.hoveredItemAnalysis = analysis;
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Flip Smart started!");
		overlayManager.add(overlay);
		overlayManager.add(inventoryOverlay);
		
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
		overlayManager.remove(overlay);
		overlayManager.remove(inventoryOverlay);
		itemAnalysisCache.clear();
		pendingAnalysisRequests.clear();
		
		// Remove flip finder panel
		if (flipFinderNavButton != null)
		{
			clientToolbar.removeNavigation(flipFinderNavButton);
		}
		
		// Stop auto-refresh timer
		stopFlipFinderRefreshTimer();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState gameState = gameStateChanged.getGameState();
		
		// Track login/hopping to avoid recording existing GE offers
		if (gameState == GameState.LOGGING_IN || gameState == GameState.HOPPING || gameState == GameState.CONNECTION_LOST)
		{
			lastLoginTick = client.getTickCount();
			log.debug("Login state change detected, setting lastLoginTick to {}", lastLoginTick);
		}
		
		if (gameState == GameState.LOGGED_IN)
		{
			log.info("Player logged in");
			syncRSN();
			checkInventoryItems();
			updateCashStack();
			
			// Refresh flip finder with current cash stack
			if (flipFinderPanel != null)
			{
				flipFinderPanel.refresh();
			}
		}
	}
	
	/**
	 * Sync the player's RSN with the API
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
			log.debug("Syncing RSN: {}", rsn);
			apiClient.updateRSN(rsn);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Only process inventory changes
		if (event.getContainerId() != 93) // 93 is the inventory container ID
		{
			return;
		}

		checkInventoryItems();
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
						recommendedSellPrice
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
			
			// Clean up tracked offer
			trackedOffers.remove(slot);
			return;
		}
		
		// Handle empty state (offer collected/cleared)
		if (state == GrandExchangeOfferState.EMPTY)
		{
			trackedOffers.remove(slot);
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
					recommendedSellPrice
				);
				
				// Clear recommended price after recording (only for buys)
				if (isBuy && recommendedSellPrice != null)
				{
					recommendedPrices.remove(itemId);
				}

				// Refresh active flips panel if it exists
				if (flipFinderPanel != null)
				{
					javax.swing.SwingUtilities.invokeLater(() -> {
						// Small delay to allow the backend to process
						try
						{
							Thread.sleep(500);
						}
						catch (InterruptedException e)
						{
							// Ignore
						}
						// This will update both pending orders and active flips
						flipFinderPanel.refresh();
					});
				}
			}

			// Update tracked offer
			trackedOffers.put(slot, new TrackedOffer(itemId, itemName, isBuy, totalQuantity, price, quantitySold));
		}
		else
		{
			// New offer with no items sold yet, track it
			trackedOffers.put(slot, new TrackedOffer(itemId, itemName, isBuy, totalQuantity, price, 0));
			
			// If this is a new buy order, refresh the flip finder panel to show pending order
			if (isBuy && previousOffer == null && flipFinderPanel != null)
			{
				javax.swing.SwingUtilities.invokeLater(() -> {
					flipFinderPanel.updatePendingOrders(getPendingBuyOrders());
				});
			}
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (!config.highlightGoodFlips())
		{
			return;
		}

		if (client.isMenuOpen())
		{
			return;
		}

		final MenuEntry[] menuEntries = client.getMenuEntries();
		final int last = menuEntries.length - 1;

		if (last < 0)
		{
			return;
		}

		final MenuEntry menuEntry = menuEntries[last];
		final int componentId = menuEntry.getParam1();

		// Check if this is an inventory item
		if (componentId != InterfaceID.Inventory.ITEMS)
		{
			return;
		}

		// Get the widget to find the item ID
		net.runelite.api.widgets.Widget widget = menuEntry.getWidget();
		if (widget == null)
		{
			return;
		}

		int itemId = widget.getItemId();
		if (itemId <= 0)
		{
			return;
		}

		// Check if this item is a good flip
		FlipAnalysis analysis = itemAnalysisCache.get(itemId);
		if (analysis == null || !analysis.isGoodFlip(config.minEfficiencyScore()) || !analysis.hasPositiveMargin())
		{
			return;
		}

		// Build tooltip text
		StringBuilder tooltip = new StringBuilder();

		if (analysis.getCurrentPrices() != null)
		{
			FlipAnalysis.CurrentPrices prices = analysis.getCurrentPrices();

			if (prices.getLow() != null)
			{
				tooltip.append("Buy: ").append(formatGP(prices.getLow())).append("</br>");
			}

			if (prices.getHigh() != null)
			{
				tooltip.append("Sell: ").append(formatGP(prices.getHigh())).append("</br>");
			}

			if (prices.getGrossMargin() != null)
			{
				tooltip.append("Margin: ").append(formatGP(prices.getGrossMargin())).append("</br>");
			}

			if (prices.getGeTax() != null)
			{
				tooltip.append("GE Tax: ").append(formatGP(prices.getGeTax())).append("</br>");
			}

			if (prices.getNetMargin() != null)
			{
				tooltip.append("Net Profit: ").append(formatGP(prices.getNetMargin())).append("</br>");
			}

			if (prices.getRoiPercent() != null)
			{
				tooltip.append("ROI: ").append(String.format("%.1f%%", prices.getRoiPercent())).append("</br>");
			}
		}

		if (analysis.getBuyLimit() != null)
		{
			tooltip.append("Buy Limit: ").append(analysis.getBuyLimit());
		}

		tooltipManager.add(new Tooltip(tooltip.toString()));
	}

	/**
	 * Check all items in the player's inventory and fetch analysis for new items
	 */
	private void checkInventoryItems()
	{
		ItemContainer inventory = client.getItemContainer(93); // 93 = inventory
		if (inventory == null)
		{
			return;
		}

		Item[] items = inventory.getItems();
		Set<Integer> currentItemIds = new HashSet<>();

		for (Item item : items)
		{
			int itemId = item.getId();
			
			// Skip empty slots (-1) and placeholder items
			if (itemId <= 0)
			{
				continue;
			}

			currentItemIds.add(itemId);

			// If we don't have analysis for this item and haven't requested it yet, fetch it
			if (!itemAnalysisCache.containsKey(itemId) && !pendingAnalysisRequests.contains(itemId))
			{
				pendingAnalysisRequests.add(itemId);
				fetchItemAnalysis(itemId);
			}
		}

		// Clean up analysis cache for items no longer in inventory
		// Keep the cache but allow it to be refreshed
		itemAnalysisCache.keySet().retainAll(currentItemIds);
		pendingAnalysisRequests.retainAll(currentItemIds);
	}

	/**
	 * Fetch analysis for an item from the API
	 */
	private void fetchItemAnalysis(int itemId)
	{
		// Fetch asynchronously to avoid blocking the game thread
		apiClient.getItemAnalysisAsync(itemId).thenAccept(analysis -> {
			if (analysis != null)
			{
				itemAnalysisCache.put(itemId, analysis);
				log.debug("Fetched analysis for item {}: {}", itemId, analysis.getItemName());
			}
			else
			{
				log.debug("No analysis data available for item {}", itemId);
			}
			pendingAnalysisRequests.remove(itemId);
		}).exceptionally(throwable -> {
			log.warn("Error fetching analysis for item {}: {}", itemId, throwable.getMessage());
			pendingAnalysisRequests.remove(itemId);
			return null;
		});
	}

	/**
	 * Get the cached analysis for an item
	 */
	public FlipAnalysis getItemAnalysis(int itemId)
	{
		return itemAnalysisCache.get(itemId);
	}

	/**
	 * Format GP amount for display
	 */
	private String formatGP(int amount)
	{
		if (amount >= 1_000_000)
		{
			return String.format("%.1fM", amount / 1_000_000.0);
		}
		else if (amount >= 1_000)
		{
			return String.format("%.1fK", amount / 1_000.0);
		}
		return String.valueOf(amount);
	}

	/**
	 * Initialize the Flip Finder panel and add it to the sidebar
	 */
	private void initializeFlipFinderPanel()
	{
		flipFinderPanel = new FlipFinderPanel(config, apiClient, itemManager, this)
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
}

