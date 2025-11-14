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

	// Store analysis results for items
	@Getter
	private final Map<Integer, FlipAnalysis> itemAnalysisCache = new ConcurrentHashMap<>();

	// Track which items we've already requested analysis for
	private final Set<Integer> pendingAnalysisRequests = new HashSet<>();

	// Currently hovered item for display in overlay
	@Getter
	private volatile FlipAnalysis hoveredItemAnalysis;

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
		
		// If player is already logged in, sync RSN immediately
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			syncRSN();
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Flip Smart stopped!");
		overlayManager.remove(overlay);
		overlayManager.remove(inventoryOverlay);
		itemAnalysisCache.clear();
		pendingAnalysisRequests.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			log.info("Player logged in");
			syncRSN();
			checkInventoryItems();
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

	@Provides
	FlipSmartConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipSmartConfig.class);
	}
}

