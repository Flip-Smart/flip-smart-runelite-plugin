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
	private static final Color COLOR_BORDER_COMPETITIVE = new Color(76, 187, 23, 180);
	private static final Color COLOR_BORDER_UNCOMPETITIVE = new Color(215, 75, 75, 180);
	private static final Color COLOR_FLIP_ASSIST_GLOW = new Color(255, 185, 50);  // Orange glow for flip assist

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

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Check if GE interface is open
		Widget geMainWidget = client.getWidget(GE_INTERFACE_GROUP, 0);
		if (geMainWidget == null || geMainWidget.isHidden())
		{
			return null;
		}

		// Enable anti-aliasing
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Draw flip assist button highlights (if enabled and active)
		if (config.highlightGEWidgets() && plugin.isFlipAssistActive())
		{
			drawFlipAssistButtonHighlights(graphics);
		}

		// Check if any of the GE slot features are enabled
		if (!config.showOfferTimers() && !config.showCompetitivenessIndicators() && !config.highlightSlotBorders())
		{
			return null;
		}

		// Get GE offers
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}

		// Enable anti-aliasing for smoother text
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		// Store tooltip data to draw after all slots (so it renders on top)
		GrandExchangeOffer tooltipOffer = null;
		FlipSmartApiClient.WikiPrice tooltipWikiPrice = null;
		int tooltipOfferPrice = 0;
		int tooltipX = 0;
		int tooltipY = 0;

		// Render indicators for each slot
		for (int slot = 0; slot < Math.min(offers.length, 8); slot++)
		{
			GrandExchangeOffer offer = offers[slot];

			if (offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}

			// Get the slot widget to determine position
			Widget slotWidget = getSlotWidget(slot);
			if (slotWidget == null || slotWidget.isHidden())
			{
				continue;
			}

			// Get tracked offer data
			FlipSmartPlugin.TrackedOffer trackedOffer = plugin.getTrackedOffer(slot);
			FlipSmartPlugin.OfferCompetitiveness competitiveness = plugin.calculateCompetitiveness(trackedOffer);

			// Get slot bounds
			Rectangle bounds = slotWidget.getBounds();
			if (bounds == null || bounds.width == 0)
			{
				continue;
			}

			// Draw slot border highlight (if enabled)
			if (config.highlightSlotBorders())
			{
				drawSlotBorder(graphics, bounds, competitiveness);
			}

			// Position timer and indicator at the top of the slot
			int topY = bounds.y + 18;

			// Draw timer (top-left, offset for long time formats like 10:35:45)
			if (config.showOfferTimers() && trackedOffer != null && trackedOffer.createdAtMillis > 0)
			{
				drawTimer(graphics, bounds.x + 8, topY, trackedOffer.createdAtMillis);
			}

			// Check if order is complete (needs collection)
			boolean isComplete = offer.getState() == GrandExchangeOfferState.BOUGHT ||
								 offer.getState() == GrandExchangeOfferState.SOLD;

			// Draw completion checkbox indicator (top-right corner, before X button) only when complete
			if (config.showCompetitivenessIndicators() && isComplete)
			{
				int indicatorX = bounds.x + bounds.width - 12;
				int indicatorY = topY - 4;
				drawCompletionCheckbox(graphics, indicatorX, indicatorY);
			}

			// Check if mouse is hovering over slot - store for later drawing
			net.runelite.api.Point mousePos = client.getMouseCanvasPosition();
			if (mousePos != null && bounds.contains(mousePos.getX(), mousePos.getY()))
			{
				tooltipOffer = offer;
				tooltipWikiPrice = plugin.getWikiPrice(offer.getItemId());
				tooltipOfferPrice = offer.getPrice();
				tooltipX = mousePos.getX() + 15;
				tooltipY = mousePos.getY() - 30;

				// Trigger price refresh if needed
				if (tooltipWikiPrice == null)
				{
					plugin.refreshWikiPrices();
				}
			}
		}

		// Draw tooltip last so it appears on top of all other elements
		if (tooltipOffer != null)
		{
			drawPriceTooltip(graphics, tooltipX, tooltipY, tooltipOffer, tooltipWikiPrice, tooltipOfferPrice);
		}

		return null;
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
	 * Draw a subtle colored border/glow around the slot based on competitiveness
	 */
	private void drawSlotBorder(Graphics2D graphics, Rectangle bounds, FlipSmartPlugin.OfferCompetitiveness competitiveness)
	{
		Color borderColor;

		switch (competitiveness)
		{
			case COMPETITIVE:
				borderColor = COLOR_BORDER_COMPETITIVE;
				break;
			case UNCOMPETITIVE:
				borderColor = COLOR_BORDER_UNCOMPETITIVE;
				break;
			default:
				return; // No border for unknown
		}

		Stroke originalStroke = graphics.getStroke();

		// Draw a softer inner glow effect (2px border with rounded corners)
		graphics.setColor(borderColor);
		graphics.setStroke(new BasicStroke(2));
		graphics.drawRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, 4, 4);

		graphics.setStroke(originalStroke);
	}

	/**
	 * Draw the timer showing elapsed time since offer creation
	 * Uses text shadow instead of background for a cleaner look
	 */
	private void drawTimer(Graphics2D graphics, int x, int y, long createdAtMillis)
	{
		String timerText = formatElapsedTime(createdAtMillis);

		Font originalFont = graphics.getFont();
		graphics.setFont(new Font("Arial", Font.BOLD, 11));

		// Draw shadow for readability (offset by 1 pixel)
		graphics.setColor(COLOR_TIMER_SHADOW);
		graphics.drawString(timerText, x + 1, y + 1);

		// Draw main text
		graphics.setColor(COLOR_TIMER_TEXT);
		graphics.drawString(timerText, x, y);

		graphics.setFont(originalFont);
	}

	/**
	 * Format elapsed time as H:MM:SS or M:SS
	 */
	private String formatElapsedTime(long createdAtMillis)
	{
		long elapsed = Math.max(0, System.currentTimeMillis() - createdAtMillis);
		long seconds = (elapsed / 1000) % 60;
		long minutes = (elapsed / 60000) % 60;
		long hours = elapsed / 3600000;

		if (hours > 0)
		{
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format("%d:%02d", minutes, seconds);
	}

	/**
	 * Draw completion checkbox indicator (green checkmark for orders ready to collect)
	 */
	private void drawCompletionCheckbox(Graphics2D graphics, int x, int y)
	{
		int size = 10;
		int centerX = x - size / 2;
		int centerY = y - size / 2;

		// Draw outer shadow for depth
		graphics.setColor(new Color(0, 0, 0, 80));
		graphics.fillOval(centerX + 1, centerY + 1, size, size);

		// Draw filled green circle
		graphics.setColor(COLOR_COMPETITIVE);
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
		boolean isBuy = offer.getState() == GrandExchangeOfferState.BUYING ||
						offer.getState() == GrandExchangeOfferState.BOUGHT ||
						offer.getState() == GrandExchangeOfferState.CANCELLED_BUY;

		Font originalFont = graphics.getFont();
		Font tooltipFont = new Font("Arial", Font.PLAIN, 11);
		graphics.setFont(tooltipFont);
		FontMetrics fm = graphics.getFontMetrics();

		java.util.List<String> linesList = new java.util.ArrayList<>();
		Color yourPriceColor = Color.WHITE;

		if (wikiPrice != null && (wikiPrice.instaBuy > 0 || wikiPrice.instaSell > 0))
		{
			// Show insta-buy and insta-sell prices
			if (wikiPrice.instaBuy > 0)
			{
				linesList.add("Insta Buy: " + NUMBER_FORMAT.format(wikiPrice.instaBuy) + " gp");
			}
			if (wikiPrice.instaSell > 0)
			{
				linesList.add("Insta Sell: " + NUMBER_FORMAT.format(wikiPrice.instaSell) + " gp");
			}

			// Determine if user's price is competitive
			if (isBuy)
			{
				// For buy orders, competitive if price >= insta-sell
				yourPriceColor = offerPrice >= wikiPrice.instaSell ? COLOR_COMPETITIVE : COLOR_UNCOMPETITIVE;
			}
			else
			{
				// For sell orders, competitive if price <= insta-buy
				yourPriceColor = offerPrice <= wikiPrice.instaBuy ? COLOR_COMPETITIVE : COLOR_UNCOMPETITIVE;
			}
		}
		else
		{
			linesList.add("Price data loading...");
		}

		// Add user's price line
		linesList.add("Your Price: " + NUMBER_FORMAT.format(offerPrice) + " gp");

		String[] lines = linesList.toArray(new String[0]);

		// Calculate tooltip dimensions
		int lineHeight = fm.getHeight();
		int padding = 5;
		int maxWidth = 0;
		for (String line : lines)
		{
			maxWidth = Math.max(maxWidth, fm.stringWidth(line));
		}
		int tooltipWidth = maxWidth + padding * 2;
		int tooltipHeight = lineHeight * lines.length + padding * 2;

		// Draw background
		graphics.setColor(new Color(30, 30, 30, 220));
		graphics.fillRoundRect(x, y, tooltipWidth, tooltipHeight, 4, 4);

		// Draw border
		graphics.setColor(new Color(80, 80, 80));
		graphics.drawRoundRect(x, y, tooltipWidth, tooltipHeight, 4, 4);

		// Draw text
		int textY = y + padding + fm.getAscent();
		for (int i = 0; i < lines.length; i++)
		{
			String line = lines[i];
			if (line.startsWith("Your Price:"))
			{
				// Draw "Your Price: " in white, value in competitive color
				String prefix = "Your Price: ";
				graphics.setColor(Color.WHITE);
				graphics.drawString(prefix, x + padding, textY);

				graphics.setColor(yourPriceColor);
				String priceValue = line.substring(prefix.length());
				graphics.drawString(priceValue, x + padding + fm.stringWidth(prefix), textY);
			}
			else
			{
				graphics.setColor(Color.WHITE);
				graphics.drawString(line, x + padding, textY);
			}
			textY += lineHeight;
		}

		graphics.setFont(originalFont);
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
