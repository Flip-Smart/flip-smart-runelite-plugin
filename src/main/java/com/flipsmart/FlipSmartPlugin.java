package com.flipsmart;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
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
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
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
		flipFinderPanel = new FlipFinderPanel(config, apiClient)
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

