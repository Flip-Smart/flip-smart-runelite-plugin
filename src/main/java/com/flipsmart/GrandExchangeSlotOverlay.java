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
import java.text.NumberFormat;

/**
 * Overlay that draws timer and competitiveness indicators directly on the
 * native Grand Exchange interface slots.
 */
@Slf4j
public class GrandExchangeSlotOverlay extends Overlay
{
	// GE Interface constants
	private static final int GE_INTERFACE_GROUP = 465;

	// The main offer container shows all 8 slots - child IDs for slot containers
	// These are the container widgets for each of the 8 GE slots (0-7)
	// Widget 465.7 through 465.14 are the slot containers in the main view
	private static final int GE_SLOT_CONTAINER_START = 7;

	// GE offer setup panel and button widget IDs (from Flipping Copilot research)
	private static final int GE_OFFER_CONTAINER = 26;       // The offer setup container widget
	private static final int GE_OFFER_QUANTITY_BUTTON_CHILD = 51;  // Child ID for "Set quantity" button
	private static final int GE_OFFER_PRICE_BUTTON_CHILD = 54;     // Child ID for "Set price" button
	private static final int GE_OFFER_CONFIRM_BUTTON_CHILD = 58;   // Child ID for "Confirm" button

	// Colors - designed to blend with GE's brown/tan color scheme
	private static final Color COLOR_COMPETITIVE = new Color(76, 187, 23);        // OSRS green
	private static final Color COLOR_UNCOMPETITIVE = new Color(215, 75, 75);      // Soft red
	private static final Color COLOR_TIMER_TEXT = new Color(255, 255, 255);       // White
	private static final Color COLOR_TIMER_SHADOW = new Color(0, 0, 0, 160);      // Subtle shadow
	private static final Color COLOR_FLIP_ASSIST_GLOW = new Color(255, 185, 50);  // Orange glow for flip assist

	// Colorblind-safe alternative colors (blue/orange instead of green/red)
	private static final Color COLOR_COMPETITIVE_CB = new Color(0, 102, 204);           // Blue
	private static final Color COLOR_UNCOMPETITIVE_CB = new Color(255, 140, 0);         // Orange

	private static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance();

	private final Client client;
	private final FlipSmartConfig config;
	private final FlipSmartPlugin plugin;

	@Inject
	private GrandExchangeSlotOverlay(Client client, FlipSmartConfig config, FlipSmartPlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(OverlayPriority.HIGH);
	}

	/**
	 * Get the competitive color (green or blue for colorblind mode)
	 */
	private Color getCompetitiveColor()
	{
		return config.colorblindMode() ? COLOR_COMPETITIVE_CB : COLOR_COMPETITIVE;
	}

	/**
	 * Get the uncompetitive color (red or orange for colorblind mode)
	 */
	private Color getUncompetitiveColor()
	{
		return config.colorblindMode() ? COLOR_UNCOMPETITIVE_CB : COLOR_UNCOMPETITIVE;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!isGEInterfaceOpen())
		{
			return null;
		}

		// Ensure wiki prices are loaded for competitiveness indicators
		plugin.refreshWikiPrices();

		setupGraphics(graphics);
		renderFlipAssistHighlights(graphics);

		if (!hasAnySlotFeaturesEnabled())
		{
			return null;
		}

		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}

		TooltipData tooltip = renderAllSlots(graphics, offers);
		if (tooltip != null)
		{
			drawPriceTooltip(graphics, tooltip.x, tooltip.y, tooltip.offer, tooltip.wikiPrice, tooltip.offerPrice);
		}

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
	 * Setup graphics rendering hints
	 */
	private void setupGraphics(Graphics2D graphics)
	{
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

	/**
	 * Render flip assist button highlights if enabled
	 */
	private void renderFlipAssistHighlights(Graphics2D graphics)
	{
		if (config.highlightGEWidgets() && plugin.isFlipAssistActive())
		{
			drawFlipAssistButtonHighlights(graphics);
		}
	}

	/**
	 * Check if any GE slot features are enabled
	 */
	private boolean hasAnySlotFeaturesEnabled()
	{
		return config.showOfferTimers() || config.showCompetitivenessIndicators() || config.highlightSlotBorders();
	}

	/**
	 * Data class for tooltip information
	 */
	private static class TooltipData
	{
		GrandExchangeOffer offer;
		FlipSmartApiClient.WikiPrice wikiPrice;
		int offerPrice;
		int x;
		int y;
	}

	/**
	 * Render all GE slots and return tooltip data if mouse is hovering
	 */
	private TooltipData renderAllSlots(Graphics2D graphics, GrandExchangeOffer[] offers)
	{
		TooltipData tooltip = null;

		for (int slot = 0; slot < Math.min(offers.length, 8); slot++)
		{
			TooltipData slotTooltip = renderSlot(graphics, slot, offers[slot]);
			if (slotTooltip != null)
			{
				tooltip = slotTooltip;
			}
		}

		return tooltip;
	}

	/**
	 * Render a single GE slot's overlays
	 */
	private TooltipData renderSlot(Graphics2D graphics, int slot, GrandExchangeOffer offer)
	{
		if (offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			return null;
		}

		Widget slotWidget = getSlotWidget(slot);
		if (slotWidget == null || slotWidget.isHidden())
		{
			return null;
		}

		Rectangle bounds = slotWidget.getBounds();
		if (bounds == null || bounds.width == 0)
		{
			return null;
		}

		FlipSmartPlugin.TrackedOffer trackedOffer = plugin.getTrackedOffer(slot);
		FlipSmartPlugin.OfferCompetitiveness competitiveness = plugin.calculateCompetitiveness(trackedOffer);

		// Render the indicator bar with colored background and timer
		renderIndicatorBar(graphics, bounds, trackedOffer, offer, competitiveness, slot);
		renderCompletionCheckbox(graphics, bounds, offer);

		return checkForTooltip(bounds, offer);
	}

	/**
	 * Render the indicator bar at the top of the slot with colored background,
	 * and timer below the item name.
	 */
	private void renderIndicatorBar(Graphics2D graphics, Rectangle bounds, FlipSmartPlugin.TrackedOffer trackedOffer,
									GrandExchangeOffer offer, FlipSmartPlugin.OfferCompetitiveness competitiveness, int slot)
	{
		// Draw colored background indicator at top of slot
		if (config.highlightSlotBorders() && competitiveness != FlipSmartPlugin.OfferCompetitiveness.UNKNOWN)
		{
			int barHeight = 18;
			int barY = bounds.y + 2;
			int barX = bounds.x + 3;
			int barWidth = bounds.width - 6;

			Color bgColor = (competitiveness == FlipSmartPlugin.OfferCompetitiveness.COMPETITIVE)
				? getCompetitiveBackgroundColor()
				: getUncompetitiveBackgroundColor();

			graphics.setColor(bgColor);
			graphics.fillRoundRect(barX, barY, barWidth, barHeight, 4, 4);
		}

		// Draw timer below item name (to the right of item box)
		if (config.showOfferTimers() && trackedOffer != null && trackedOffer.createdAtMillis > 0)
		{
			boolean isComplete = offer.getState() == GrandExchangeOfferState.BOUGHT ||
								 offer.getState() == GrandExchangeOfferState.SOLD;

			String timerText;
			if (isComplete && trackedOffer.completedAtMillis > 0)
			{
				timerText = TimeUtils.formatFrozenElapsedTime(trackedOffer.createdAtMillis, trackedOffer.completedAtMillis);
			}
			else
			{
				timerText = TimeUtils.formatElapsedTime(trackedOffer.createdAtMillis);
			}

			Font originalFont = graphics.getFont();
			graphics.setFont(new Font("Arial", Font.BOLD, 11));

			// Position timer to the right of item box, below item name
			int textX = bounds.x + 44;
			int textY = bounds.y + (int)(bounds.height * 0.60);

			// Draw timer shadow and text
			graphics.setColor(COLOR_TIMER_SHADOW);
			graphics.drawString(timerText, textX + 1, textY + 1);
			graphics.setColor(isComplete ? getCompetitiveColor() : COLOR_TIMER_TEXT);
			graphics.drawString(timerText, textX, textY);

			graphics.setFont(originalFont);
		}
	}

	/**
	 * Get semi-transparent competitive background color
	 */
	private Color getCompetitiveBackgroundColor()
	{
		if (config.colorblindMode())
		{
			return new Color(0, 102, 204, 60);  // Semi-transparent blue
		}
		return new Color(76, 187, 23, 60);  // Semi-transparent green
	}

	/**
	 * Get semi-transparent uncompetitive background color
	 */
	private Color getUncompetitiveBackgroundColor()
	{
		if (config.colorblindMode())
		{
			return new Color(255, 140, 0, 60);  // Semi-transparent orange
		}
		return new Color(215, 75, 75, 60);  // Semi-transparent red
	}

	/**
	 * Render completion checkbox if order is complete
	 */
	private void renderCompletionCheckbox(Graphics2D graphics, Rectangle bounds, GrandExchangeOffer offer)
	{
		boolean isComplete = offer.getState() == GrandExchangeOfferState.BOUGHT ||
							 offer.getState() == GrandExchangeOfferState.SOLD;

		if (config.showCompetitivenessIndicators() && isComplete)
		{
			// Position checkbox at top-right of slot
			int topY = bounds.y + 18;
			drawCompletionCheckbox(graphics, bounds.x + bounds.width - 12, topY - 4);
		}
	}

	/**
	 * Check if mouse is hovering over slot and return tooltip data
	 */
	private TooltipData checkForTooltip(Rectangle bounds, GrandExchangeOffer offer)
	{
		net.runelite.api.Point mousePos = client.getMouseCanvasPosition();
		if (mousePos == null || !bounds.contains(mousePos.getX(), mousePos.getY()))
		{
			return null;
		}

		TooltipData tooltip = new TooltipData();
		tooltip.offer = offer;
		tooltip.wikiPrice = plugin.getWikiPrice(offer.getItemId());
		tooltip.offerPrice = offer.getPrice();
		tooltip.x = mousePos.getX() + 15;
		tooltip.y = mousePos.getY() - 30;

		if (tooltip.wikiPrice == null)
		{
			plugin.refreshWikiPrices();
		}

		return tooltip;
	}

	/**
	 * Get the widget for a specific GE slot (0-7)
	 */
	private Widget getSlotWidget(int slot)
	{
		// The GE interface has slot containers at different child IDs
		// Main GE view: slots are children 7-14 of group 465
		Widget slotWidget = client.getWidget(GE_INTERFACE_GROUP, GE_SLOT_CONTAINER_START + slot);

		if (slotWidget != null && !slotWidget.isHidden())
		{
			return slotWidget;
		}

		return null;
	}

	/**
	 * Draw the timer showing elapsed time since offer creation
	 * Uses text shadow instead of background for a cleaner look
	 * Timer turns green and stops when offer completes
	 */
	private void drawTimer(Graphics2D graphics, int x, int y, long createdAtMillis, long completedAtMillis, boolean isComplete)
	{
		String timerText;
		Color textColor;

		if (isComplete && completedAtMillis > 0)
		{
			// Show frozen elapsed time and green color for completed offers
			timerText = TimeUtils.formatFrozenElapsedTime(createdAtMillis, completedAtMillis);
			textColor = getCompetitiveColor();
		}
		else
		{
			// Show running timer with white color
			timerText = TimeUtils.formatElapsedTime(createdAtMillis);
			textColor = COLOR_TIMER_TEXT;
		}

		Font originalFont = graphics.getFont();
		graphics.setFont(new Font("Arial", Font.BOLD, 11));

		// Draw shadow for readability (offset by 1 pixel)
		graphics.setColor(COLOR_TIMER_SHADOW);
		graphics.drawString(timerText, x + 1, y + 1);

		// Draw main text
		graphics.setColor(textColor);
		graphics.drawString(timerText, x, y);

		graphics.setFont(originalFont);
	}

	/**
	 * Draw completion checkbox indicator (green/blue checkmark for orders ready to collect)
	 */
	private void drawCompletionCheckbox(Graphics2D graphics, int x, int y)
	{
		int size = 10;
		int centerX = x - size / 2;
		int centerY = y - size / 2;

		// Draw outer shadow for depth
		graphics.setColor(new Color(0, 0, 0, 80));
		graphics.fillOval(centerX + 1, centerY + 1, size, size);

		// Draw filled circle (green or blue for colorblind mode)
		graphics.setColor(getCompetitiveColor());
		graphics.fillOval(centerX, centerY, size, size);

		// Draw checkmark
		graphics.setColor(Color.WHITE);
		graphics.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		int padding = 3;
		int left = centerX + padding;
		int right = centerX + size - padding;
		int bottom = centerY + size - padding;
		int midY = centerY + size / 2;
		int checkMidX = centerX + size / 3;

		graphics.drawLine(left, midY, checkMidX, bottom - 1);
		graphics.drawLine(checkMidX, bottom - 1, right, centerY + padding);

		graphics.setStroke(new BasicStroke(1));
	}

	/**
	 * Draw a custom tooltip with background showing real-time insta prices
	 */
	private void drawPriceTooltip(Graphics2D graphics, int x, int y, GrandExchangeOffer offer,
								  FlipSmartApiClient.WikiPrice wikiPrice, int offerPrice)
	{
		boolean isBuy = isOfferBuyType(offer);

		Font originalFont = graphics.getFont();
		graphics.setFont(new Font("Arial", Font.PLAIN, 11));
		FontMetrics fm = graphics.getFontMetrics();

		java.util.List<String> lines = buildTooltipLines(wikiPrice, offerPrice);
		Color yourPriceColor = determineYourPriceColor(wikiPrice, offerPrice, isBuy);

		drawTooltipBackground(graphics, x, y, lines, fm);
		drawTooltipText(graphics, x, y, lines, yourPriceColor, fm);

		graphics.setFont(originalFont);
	}

	/**
	 * Check if offer is a buy type (BUYING, BOUGHT, or CANCELLED_BUY)
	 */
	private boolean isOfferBuyType(GrandExchangeOffer offer)
	{
		GrandExchangeOfferState state = offer.getState();
		return state == GrandExchangeOfferState.BUYING ||
			   state == GrandExchangeOfferState.BOUGHT ||
			   state == GrandExchangeOfferState.CANCELLED_BUY;
	}

	/**
	 * Build tooltip text lines based on wiki price data
	 */
	private java.util.List<String> buildTooltipLines(FlipSmartApiClient.WikiPrice wikiPrice, int offerPrice)
	{
		java.util.List<String> lines = new java.util.ArrayList<>();

		if (wikiPrice != null && (wikiPrice.instaBuy > 0 || wikiPrice.instaSell > 0))
		{
			if (wikiPrice.instaBuy > 0)
			{
				lines.add("Insta Buy: " + NUMBER_FORMAT.format(wikiPrice.instaBuy) + " gp");
			}
			if (wikiPrice.instaSell > 0)
			{
				lines.add("Insta Sell: " + NUMBER_FORMAT.format(wikiPrice.instaSell) + " gp");
			}
		}
		else
		{
			lines.add("Price data loading...");
		}

		lines.add("Your Price: " + NUMBER_FORMAT.format(offerPrice) + " gp");
		return lines;
	}

	/**
	 * Determine color for user's price based on competitiveness
	 */
	private Color determineYourPriceColor(FlipSmartApiClient.WikiPrice wikiPrice, int offerPrice, boolean isBuy)
	{
		if (wikiPrice == null || (wikiPrice.instaBuy <= 0 && wikiPrice.instaSell <= 0))
		{
			return Color.WHITE;
		}

		boolean isCompetitive = isBuy
			? offerPrice >= wikiPrice.instaSell
			: offerPrice <= wikiPrice.instaBuy;

		return isCompetitive ? getCompetitiveColor() : getUncompetitiveColor();
	}

	/**
	 * Draw tooltip background and border
	 */
	private void drawTooltipBackground(Graphics2D graphics, int x, int y, java.util.List<String> lines, FontMetrics fm)
	{
		int lineHeight = fm.getHeight();
		int padding = 5;
		int maxWidth = lines.stream().mapToInt(fm::stringWidth).max().orElse(0);
		int tooltipWidth = maxWidth + padding * 2;
		int tooltipHeight = lineHeight * lines.size() + padding * 2;

		graphics.setColor(new Color(30, 30, 30, 220));
		graphics.fillRoundRect(x, y, tooltipWidth, tooltipHeight, 4, 4);

		graphics.setColor(new Color(80, 80, 80));
		graphics.drawRoundRect(x, y, tooltipWidth, tooltipHeight, 4, 4);
	}

	/**
	 * Draw tooltip text lines
	 */
	private void drawTooltipText(Graphics2D graphics, int x, int y, java.util.List<String> lines,
								 Color yourPriceColor, FontMetrics fm)
	{
		int padding = 5;
		int textY = y + padding + fm.getAscent();

		for (String line : lines)
		{
			drawTooltipLine(graphics, line, x + padding, textY, yourPriceColor, fm);
			textY += fm.getHeight();
		}
	}

	/**
	 * Draw a single tooltip line with appropriate coloring
	 */
	private void drawTooltipLine(Graphics2D graphics, String line, int x, int y, Color yourPriceColor, FontMetrics fm)
	{
		if (line.startsWith("Your Price:"))
		{
			String prefix = "Your Price: ";
			graphics.setColor(Color.WHITE);
			graphics.drawString(prefix, x, y);

			graphics.setColor(yourPriceColor);
			graphics.drawString(line.substring(prefix.length()), x + fm.stringWidth(prefix), y);
		}
		else
		{
			graphics.setColor(Color.WHITE);
			graphics.drawString(line, x, y);
		}
	}

	/**
	 * Draw orange glow highlights on GE buttons based on the current flip assist step.
	 * Uses widget child IDs discovered from Flipping Copilot source code.
	 */
	private void drawFlipAssistButtonHighlights(Graphics2D graphics)
	{
		FlipAssistOverlay.FlipAssistStep step = plugin.getFlipAssistStep();
		if (step == null)
		{
			return;
		}

		// Get the offer container widget (465, 26)
		Widget offerContainer = client.getWidget(GE_INTERFACE_GROUP, GE_OFFER_CONTAINER);
		if (offerContainer == null || offerContainer.isHidden())
		{
			return;
		}

		Widget targetWidget = null;
		int childId = -1;

		switch (step)
		{
			case SET_QUANTITY:
				// Quantity button is child 51 of the offer container
				childId = GE_OFFER_QUANTITY_BUTTON_CHILD;
				break;
			case SET_PRICE:
			case SET_SELL_PRICE:
				// Price button is child 54 of the offer container
				childId = GE_OFFER_PRICE_BUTTON_CHILD;
				break;
			case CONFIRM_OFFER:
			case CONFIRM_SELL:
				// Confirm button is child 58 of the offer container
				childId = GE_OFFER_CONFIRM_BUTTON_CHILD;
				break;
			default:
				return;
		}

		if (childId >= 0)
		{
			targetWidget = offerContainer.getChild(childId);
		}

		if (targetWidget != null && !targetWidget.isHidden())
		{
			Rectangle bounds = targetWidget.getBounds();
			if (bounds != null && bounds.width > 0)
			{
				// For quantity/price buttons, shrink the highlight to fit just the button area
				// These widgets include extra space above/below the actual button
				if (step == FlipAssistOverlay.FlipAssistStep.SET_QUANTITY ||
					step == FlipAssistOverlay.FlipAssistStep.SET_PRICE ||
					step == FlipAssistOverlay.FlipAssistStep.SET_SELL_PRICE)
				{
					// Shrink height and center vertically
					int shrinkAmount = bounds.height / 3;
					bounds = new Rectangle(
						bounds.x,
						bounds.y + shrinkAmount / 2,
						bounds.width,
						bounds.height - shrinkAmount
					);
				}
				drawOrangeGlow(graphics, bounds);
			}
		}
	}

	/**
	 * Draw an animated orange glow around a widget bounds
	 */
	private void drawOrangeGlow(Graphics2D graphics, Rectangle bounds)
	{
		// Calculate pulse animation
		long elapsed = System.currentTimeMillis() % 1500;
		float pulseAlpha = (float) (0.5 + 0.5 * Math.sin(elapsed / 1500.0 * 2 * Math.PI));

		// Draw outer glow layers
		for (int i = 3; i >= 1; i--)
		{
			int alpha = (int) ((40 + 30 * pulseAlpha) / i);
			Color glowColor = new Color(
				COLOR_FLIP_ASSIST_GLOW.getRed(),
				COLOR_FLIP_ASSIST_GLOW.getGreen(),
				COLOR_FLIP_ASSIST_GLOW.getBlue(),
				alpha
			);
			graphics.setColor(glowColor);
			graphics.setStroke(new BasicStroke(i * 2.0f));
			graphics.drawRoundRect(
				bounds.x - i * 2,
				bounds.y - i * 2,
				bounds.width + i * 4,
				bounds.height + i * 4,
				4, 4
			);
		}

		// Draw inner border
		int borderAlpha = (int) (180 + 75 * pulseAlpha);
		Color borderColor = new Color(
			COLOR_FLIP_ASSIST_GLOW.getRed(),
			COLOR_FLIP_ASSIST_GLOW.getGreen(),
			COLOR_FLIP_ASSIST_GLOW.getBlue(),
			borderAlpha
		);
		graphics.setColor(borderColor);
		graphics.setStroke(new BasicStroke(2));
		graphics.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 4, 4);
	}
}
