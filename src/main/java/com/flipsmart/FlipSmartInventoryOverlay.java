package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.TooltipComponent;

import javax.inject.Inject;
import java.awt.*;

public class FlipSmartInventoryOverlay extends WidgetItemOverlay
{
	private final Client client;
	private final FlipSmartPlugin plugin;
	private final FlipSmartConfig config;

	@Inject
	private FlipSmartInventoryOverlay(Client client, FlipSmartPlugin plugin, FlipSmartConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		showOnInventory();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.highlightGoodFlips())
		{
			return;
		}

		// Check if this item is a good flip
		FlipAnalysis analysis = plugin.getItemAnalysis(itemId);
		if (analysis == null)
		{
			return;
		}

		// Determine if this item should be highlighted
		if (analysis.isGoodFlip(config.minEfficiencyScore()) && analysis.hasPositiveMargin())
		{
			// Draw a brighter, sharper blue box around the item
			Rectangle bounds = widgetItem.getCanvasBounds();
			
			// Outer glow effect
			graphics.setColor(new Color(0, 200, 255, 100));
			graphics.setStroke(new BasicStroke(4));
			graphics.drawRect(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2);
			
			// Inner bright blue border
			graphics.setColor(new Color(0, 220, 255, 255)); // Bright cyan blue
			graphics.setStroke(new BasicStroke(2));
			graphics.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);

			// Update the plugin's currently hovered item for the overlay panel
			if (bounds.contains(client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY()))
			{
				plugin.setHoveredItemAnalysis(analysis);
			}
		}
	}

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
}

