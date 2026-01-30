package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.*;

/**
 * Overlay that draws competitiveness borders on GE slots.
 * Uses UNDER_WIDGETS layer so borders appear BEHIND the native GE text.
 */
@Slf4j
public class GrandExchangeSlotBorderOverlay extends Overlay
{
	// GE Interface constants
	private static final int GE_INTERFACE_GROUP = 465;
	private static final int GE_SLOT_CONTAINER_START = 7;

	// Border colors - semi-transparent to blend with GE UI
	private static final Color COLOR_BORDER_COMPETITIVE = new Color(76, 187, 23, 180);
	private static final Color COLOR_BORDER_UNCOMPETITIVE = new Color(215, 75, 75, 180);

	// Colorblind-safe alternative colors (blue/orange instead of green/red)
	private static final Color COLOR_BORDER_COMPETITIVE_CB = new Color(0, 102, 204, 180);
	private static final Color COLOR_BORDER_UNCOMPETITIVE_CB = new Color(255, 140, 0, 180);

	private final Client client;
	private final FlipSmartConfig config;
	private final FlipSmartPlugin plugin;

	@Inject
	private GrandExchangeSlotBorderOverlay(Client client, FlipSmartConfig config, FlipSmartPlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);  // Render above widgets but below ALWAYS_ON_TOP
		setPriority(OverlayPriority.LOW);  // Lower priority within the layer
	}

	/**
	 * Get the competitive border color (green or blue for colorblind mode)
	 */
	private Color getBorderCompetitiveColor()
	{
		return config.colorblindMode() ? COLOR_BORDER_COMPETITIVE_CB : COLOR_BORDER_COMPETITIVE;
	}

	/**
	 * Get the uncompetitive border color (red or orange for colorblind mode)
	 */
	private Color getBorderUncompetitiveColor()
	{
		return config.colorblindMode() ? COLOR_BORDER_UNCOMPETITIVE_CB : COLOR_BORDER_UNCOMPETITIVE;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Border rendering is now disabled - competitiveness is shown via
		// the colored indicator bar in GrandExchangeSlotOverlay instead
		return null;
	}

	/**
	 * Check if the GE interface is currently open
	 */
	private boolean isGEInterfaceOpen()
	{
		Widget geMainWidget = client.getWidget(GE_INTERFACE_GROUP, 0);
		return geMainWidget != null && !geMainWidget.isHidden();
	}

	/**
	 * Render border for a single GE slot
	 */
	private void renderSlotBorder(Graphics2D graphics, int slot, GrandExchangeOffer offer)
	{
		if (offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			return;
		}

		Widget slotWidget = getSlotWidget(slot);
		if (slotWidget == null || slotWidget.isHidden())
		{
			return;
		}

		Rectangle bounds = slotWidget.getBounds();
		if (bounds == null || bounds.width == 0)
		{
			return;
		}

		FlipSmartPlugin.TrackedOffer trackedOffer = plugin.getTrackedOffer(slot);
		FlipSmartPlugin.OfferCompetitiveness competitiveness = plugin.calculateCompetitiveness(trackedOffer);

		drawSlotBorder(graphics, bounds, competitiveness);
	}

	/**
	 * Get the widget for a specific GE slot (0-7)
	 */
	private Widget getSlotWidget(int slot)
	{
		Widget slotWidget = client.getWidget(GE_INTERFACE_GROUP, GE_SLOT_CONTAINER_START + slot);

		if (slotWidget != null && !slotWidget.isHidden())
		{
			return slotWidget;
		}

		return null;
	}

	/**
	 * Draw a colored border around the top portion of the slot only,
	 * avoiding the bottom area where the yellow info box appears.
	 */
	private void drawSlotBorder(Graphics2D graphics, Rectangle bounds, FlipSmartPlugin.OfferCompetitiveness competitiveness)
	{
		Color borderColor;

		switch (competitiveness)
		{
			case COMPETITIVE:
				borderColor = getBorderCompetitiveColor();
				break;
			case UNCOMPETITIVE:
				borderColor = getBorderUncompetitiveColor();
				break;
			default:
				return; // No border for unknown
		}

		Stroke originalStroke = graphics.getStroke();
		graphics.setColor(borderColor);
		graphics.setStroke(new BasicStroke(2f));

		int inset = 2;
		int left = bounds.x + inset;
		int top = bounds.y + inset;
		int right = bounds.x + bounds.width - inset;

		// Draw down to ~55% of the slot height to avoid the info box area at the bottom
		int sideBottom = bounds.y + (int)(bounds.height * 0.55);

		// Top border with rounded corners
		graphics.drawLine(left + 4, top, right - 4, top);
		graphics.drawArc(left, top, 8, 8, 90, 90);  // Top-left corner
		graphics.drawArc(right - 8, top, 8, 8, 0, 90);  // Top-right corner

		// Left and right sides (partial - stops before info box)
		graphics.drawLine(left, top + 4, left, sideBottom);
		graphics.drawLine(right, top + 4, right, sideBottom);

		graphics.setStroke(originalStroke);
	}
}
