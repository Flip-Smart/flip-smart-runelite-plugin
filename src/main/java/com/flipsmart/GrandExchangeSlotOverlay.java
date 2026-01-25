package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
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
	private static final Color COLOR_UNKNOWN = new Color(140, 130, 115);          // Muted tan/gray
	private static final Color COLOR_TIMER_TEXT = new Color(255, 255, 255);       // White
	private static final Color COLOR_TIMER_SHADOW = new Color(0, 0, 0, 160);      // Subtle shadow
	private static final Color COLOR_BORDER_COMPETITIVE = new Color(76, 187, 23, 180);
	private static final Color COLOR_BORDER_UNCOMPETITIVE = new Color(215, 75, 75, 180);
	private static final Color COLOR_FLIP_ASSIST_GLOW = new Color(255, 185, 50);  // Orange glow for flip assist

	private static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance();

	private final Client client;
	private final FlipSmartConfig config;
	private final FlipSmartPlugin plugin;
	private final ItemManager itemManager;

	@Inject
	private GrandExchangeSlotOverlay(Client client, FlipSmartConfig config, FlipSmartPlugin plugin,
									 ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;
		this.itemManager = itemManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
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
			// Timer: top-left, Indicator: top-right corner before X
			int topY = bounds.y + 18;
			int indicatorSize = 10;

			// Draw timer (top-left, offset for long time formats like 10:35:45)
			if (config.showOfferTimers() && trackedOffer != null && trackedOffer.createdAtMillis > 0)
			{
				drawTimer(graphics, bounds.x + 8, topY, trackedOffer.createdAtMillis);
			}

			// Draw competitiveness indicator (top-right corner, before X button)
			if (config.showCompetitivenessIndicators())
			{
				int indicatorX = bounds.x + bounds.width - 12;
				int indicatorY = topY - 4;

				drawCompetitivenessIndicator(graphics, indicatorX, indicatorY, competitiveness);

				// Check if mouse is hovering over indicator for tooltip
				net.runelite.api.Point mousePos = client.getMouseCanvasPosition();
				if (mousePos != null)
				{
					Rectangle indicatorBounds = new Rectangle(
						indicatorX - indicatorSize / 2,
						indicatorY - indicatorSize / 2,
						indicatorSize,
						indicatorSize
					);

					if (indicatorBounds.contains(mousePos.getX(), mousePos.getY()))
					{
						// Get Wiki price for tooltip
						int wikiPrice = itemManager.getItemPrice(offer.getItemId());
						int offerPrice = offer.getPrice();

						// Draw custom tooltip with background
						drawPriceTooltip(graphics, mousePos.getX() + 15, mousePos.getY(), offer, wikiPrice, offerPrice);
					}
				}
			}
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
	 * Draw competitiveness indicator as a clean circular badge
	 */
	private void drawCompetitivenessIndicator(Graphics2D graphics, int x, int y, FlipSmartPlugin.OfferCompetitiveness competitiveness)
	{
		int size = 10;
		int centerX = x - size / 2;
		int centerY = y - size / 2;

		Color bgColor;

		switch (competitiveness)
		{
			case COMPETITIVE:
				bgColor = COLOR_COMPETITIVE;
				break;
			case UNCOMPETITIVE:
				bgColor = COLOR_UNCOMPETITIVE;
				break;
			default:
				bgColor = COLOR_UNKNOWN;
				break;
		}

		// Draw outer shadow for depth
		graphics.setColor(new Color(0, 0, 0, 80));
		graphics.fillOval(centerX + 1, centerY + 1, size, size);

		// Draw filled circle
		graphics.setColor(bgColor);
		graphics.fillOval(centerX, centerY, size, size);

		// Draw inner symbol using simple graphics instead of unicode fonts
		graphics.setColor(Color.WHITE);
		graphics.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		int padding = 3;
		int left = centerX + padding;
		int right = centerX + size - padding;
		int top = centerY + padding;
		int bottom = centerY + size - padding;
		int midY = centerY + size / 2;

		switch (competitiveness)
		{
			case COMPETITIVE:
				// Draw checkmark
				int checkMidX = centerX + size / 3;
				graphics.drawLine(left, midY, checkMidX, bottom - 1);
				graphics.drawLine(checkMidX, bottom - 1, right, top);
				break;
			case UNCOMPETITIVE:
				// Draw X
				graphics.drawLine(left, top, right, bottom);
				graphics.drawLine(right, top, left, bottom);
				break;
			default:
				// Draw question mark using simple lines
				Font originalFont = graphics.getFont();
				graphics.setFont(new Font("Arial", Font.BOLD, 8));
				FontMetrics fm = graphics.getFontMetrics();
				String qMark = "?";
				int qX = centerX + (size - fm.stringWidth(qMark)) / 2;
				int qY = centerY + (size + fm.getAscent() - fm.getDescent()) / 2;
				graphics.drawString(qMark, qX, qY);
				graphics.setFont(originalFont);
				break;
		}

		// Reset stroke
		graphics.setStroke(new BasicStroke(1));
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
	 * Draw a custom tooltip with background showing price info
	 */
	private void drawPriceTooltip(Graphics2D graphics, int x, int y, GrandExchangeOffer offer, int wikiPrice, int offerPrice)
	{
		boolean isBuy = offer.getState() == GrandExchangeOfferState.BUYING ||
						offer.getState() == GrandExchangeOfferState.BOUGHT ||
						offer.getState() == GrandExchangeOfferState.CANCELLED_BUY;

		Font originalFont = graphics.getFont();
		Font tooltipFont = new Font("Arial", Font.PLAIN, 11);
		graphics.setFont(tooltipFont);
		FontMetrics fm = graphics.getFontMetrics();

		String[] lines;
		Color marginColor;

		if (wikiPrice > 0)
		{
			int margin;
			if (isBuy)
			{
				margin = wikiPrice - offerPrice;
			}
			else
			{
				margin = offerPrice - wikiPrice;
			}

			marginColor = margin >= 0 ? COLOR_COMPETITIVE : COLOR_UNCOMPETITIVE;
			String marginText = (margin >= 0 ? "+" : "") + NUMBER_FORMAT.format(margin) + " gp";

			lines = new String[] {
				"Wiki Price: " + NUMBER_FORMAT.format(wikiPrice) + " gp",
				"Your Price: " + NUMBER_FORMAT.format(offerPrice) + " gp",
				"Margin: " + marginText
			};
		}
		else
		{
			marginColor = COLOR_UNKNOWN;
			lines = new String[] { "Price data unavailable" };
		}

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
			if (i == lines.length - 1 && wikiPrice > 0)
			{
				// Draw "Margin: " in white, value in color
				String prefix = "Margin: ";
				graphics.setColor(Color.WHITE);
				graphics.drawString(prefix, x + padding, textY);

				graphics.setColor(marginColor);
				String marginValue = lines[i].substring(prefix.length());
				graphics.drawString(marginValue, x + padding + fm.stringWidth(prefix), textY);
			}
			else
			{
				graphics.setColor(Color.WHITE);
				graphics.drawString(lines[i], x + padding, textY);
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
			graphics.setStroke(new BasicStroke(i * 2));
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
