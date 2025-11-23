package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.BackgroundComponent;
import net.runelite.client.util.AsyncBufferedImage;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;

import static net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG;
import static net.runelite.client.ui.overlay.OverlayManager.OPTION_CONFIGURE;

/**
 * In-game overlay that displays all 8 Grand Exchange offer slots with real-time status
 */
public class GrandExchangeOverlay extends Overlay
{
	private static final DecimalFormat PERCENTAGE_FORMAT = new DecimalFormat("0");
	private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("#,###");
	
	private static final Color COLOR_BUY = new Color(0, 128, 0);  // Dark green
	private static final Color COLOR_SELL = new Color(180, 0, 0); // Dark red
	private static final Color COLOR_COMPLETE = new Color(200, 180, 50); // Gold
	private static final Color COLOR_EMPTY = new Color(128, 128, 128); // Gray
	private static final Color COLOR_CANCELLED = new Color(200, 100, 0); // Orange
	private static final Color COLOR_BACKGROUND = new Color(62, 53, 41); // Dark brown (GE style)
	private static final Color COLOR_BORDER = new Color(0, 0, 0); // Black
	private static final Color COLOR_TEXT = new Color(255, 255, 255); // White
	private static final Color COLOR_TITLE = new Color(255, 215, 0); // Gold
	
	private static final Color COLOR_DIVIDER = new Color(90, 80, 65); // Brown divider
	private static final Color COLOR_PROGRESS_BG = new Color(40, 35, 28); // Dark progress bar background
	private static final Color COLOR_PROGRESS_BORDER = new Color(20, 18, 15); // Progress bar border
	
	private static final int PADDING = 8;
	private static final int LINE_HEIGHT = 17;
	private static final int ICON_SIZE = 32;
	private static final int ICON_OFFSET_X = 8;
	private static final int DIVIDER_PADDING = 6;
	private static final int PROGRESS_BAR_WIDTH = 60;
	private static final int PROGRESS_BAR_HEIGHT = 14;
	
	private final Client client;
	private final FlipSmartConfig config;
	private final ItemManager itemManager;

	@Inject
	private GrandExchangeOverlay(Client client, FlipSmartConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		
		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(OverlayPriority.MED);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		
		getMenuEntries().add(new OverlayMenuEntry(RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, "GE Tracker"));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showGEOverlay())
		{
			return null;
		}
		
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}
		
		// Use RuneLite's font for crisp rendering
		graphics.setFont(FontManager.getRunescapeFont());
		
		// Enable proper anti-aliasing
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		
		// Calculate dimensions - more width for progress bars
		int textWidth = 200;
		int iconColumnWidth = config.showGEItemIcons() ? (ICON_SIZE + ICON_OFFSET_X) : 0;
		int totalWidth = textWidth + iconColumnWidth + (PADDING * 2);
		
		// Get preferred position (will be set by user dragging)
		Point position = new Point(100, 100); // Default position
		int x = position.x;
		int y = position.y;
		int currentY = y + PADDING + LINE_HEIGHT; // Start below top padding
		
		// Count lines to calculate height
		int lineCount = 1; // Title
		boolean hasActiveOffers = false;
		int dividerCount = 0;
		
		for (int i = 0; i < offers.length; i++)
		{
			GrandExchangeOffer offer = offers[i];
			if (offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				if (config.hideEmptyGESlots()) continue;
				lineCount++;
			}
			else
			{
				hasActiveOffers = true;
				lineCount++; // Slot line
				if (config.showGEItemNames()) lineCount++; // Item name line
				if (config.showGEDetailedInfo()) lineCount++; // Details line
				
				// Count dividers (between non-empty slots)
				if (i < offers.length - 1)
				{
					dividerCount++;
				}
			}
		}
		
		if (!hasActiveOffers && config.hideEmptyGESlots())
		{
			lineCount++; // "No offers" message
		}
		
		// Add space for dividers
		int totalHeight = (lineCount * LINE_HEIGHT) + (PADDING * 2);
		if (!config.compactGEOverlay())
		{
			totalHeight += dividerCount * (DIVIDER_PADDING * 2);
		}
		else
		{
			totalHeight += dividerCount * DIVIDER_PADDING;
		}
		
		// Draw background with GE-style brown
		graphics.setColor(COLOR_BACKGROUND);
		graphics.fillRect(x, y, totalWidth, totalHeight);
		
		// Draw border
		graphics.setColor(COLOR_BORDER);
		graphics.drawRect(x, y, totalWidth, totalHeight);
		graphics.drawRect(x + 1, y + 1, totalWidth - 2, totalHeight - 2); // Double border for depth
		
		// Draw title with bold font
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(COLOR_TITLE);
		drawCenteredString(graphics, "GE", x, currentY, totalWidth);
		currentY += LINE_HEIGHT;
		
		// Reset to regular font for content
		graphics.setFont(FontManager.getRunescapeFont());
		
		// Render each slot
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			
			if (offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				if (config.hideEmptyGESlots()) continue;
				
				graphics.setColor(COLOR_EMPTY);
				graphics.drawString("Slot " + (slot + 1), x + PADDING, currentY);
				graphics.drawString("Empty", x + totalWidth - PADDING - iconColumnWidth - 50, currentY);
				currentY += LINE_HEIGHT;
				continue;
			}
			
			// Get offer details
			GrandExchangeOfferState state = offer.getState();
			int itemId = offer.getItemId();
			int quantitySold = offer.getQuantitySold();
			int totalQuantity = offer.getTotalQuantity();
			int price = offer.getPrice();
			
			boolean isBuy = state == GrandExchangeOfferState.BUYING || 
							state == GrandExchangeOfferState.BOUGHT ||
							state == GrandExchangeOfferState.CANCELLED_BUY;
			
			double percentage = totalQuantity > 0 ? (quantitySold * 100.0) / totalQuantity : 0;
			String itemName = itemManager.getItemComposition(itemId).getName();
			
			// Determine status
			Color statusColor;
			String statusText;
			
			switch (state)
			{
				case BOUGHT:
				case SOLD:
					statusColor = COLOR_COMPLETE;
					statusText = "100%";
					break;
				case CANCELLED_BUY:
				case CANCELLED_SELL:
					statusColor = COLOR_CANCELLED;
					statusText = "Cancelled";
					break;
				case BUYING:
				case SELLING:
					statusColor = isBuy ? COLOR_BUY : COLOR_SELL;
					statusText = PERCENTAGE_FORMAT.format(percentage) + "%";
					break;
				default:
					statusColor = COLOR_EMPTY;
					statusText = "0%";
			}
			
			// Line 1: Slot label with progress bar
			String slotLabel = (slot + 1) + ". " + (isBuy ? "Buy" : "Sell");
			graphics.setColor(isBuy ? COLOR_BUY : COLOR_SELL);
			graphics.drawString(slotLabel, x + PADDING, currentY);
			
			// Draw progress bar on the right
			int progressBarX = x + textWidth - PROGRESS_BAR_WIDTH + PADDING - 10;
			int progressBarY = currentY - PROGRESS_BAR_HEIGHT + 2;
			
			// Progress bar background
			graphics.setColor(COLOR_PROGRESS_BG);
			graphics.fillRect(progressBarX, progressBarY, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
			
			// Progress bar border
			graphics.setColor(COLOR_PROGRESS_BORDER);
			graphics.drawRect(progressBarX, progressBarY, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
			
			// Progress fill
			int fillWidth = (int) (PROGRESS_BAR_WIDTH * (percentage / 100.0));
			Color progressColor = statusColor;
			graphics.setColor(progressColor);
			graphics.fillRect(progressBarX + 1, progressBarY + 1, fillWidth - 1, PROGRESS_BAR_HEIGHT - 2);
			
			// Progress text centered
			graphics.setColor(Color.WHITE);
			String progressText = PERCENTAGE_FORMAT.format(percentage) + "%";
			FontMetrics fm = graphics.getFontMetrics();
			int progressTextWidth = fm.stringWidth(progressText);
			int textX = progressBarX + (PROGRESS_BAR_WIDTH - progressTextWidth) / 2;
			int textY = progressBarY + PROGRESS_BAR_HEIGHT - 3;
			
			// Draw text shadow
			graphics.setColor(Color.BLACK);
			graphics.drawString(progressText, textX + 1, textY + 1);
			// Draw text
			graphics.setColor(Color.WHITE);
			graphics.drawString(progressText, textX, textY);
			
			currentY += LINE_HEIGHT;
			
			// Line 2: Item name with icon on the right
			if (config.showGEItemNames())
			{
				graphics.setColor(COLOR_TEXT);
				graphics.drawString(itemName, x + PADDING, currentY);
				
				// Draw icon aligned with item name on the right
				if (config.showGEItemIcons())
				{
					AsyncBufferedImage itemImage = itemManager.getImage(itemId);
					if (itemImage != null)
					{
						BufferedImage icon = itemImage;
						if (icon.getWidth() > 0)
						{
							int iconX = x + textWidth + ICON_OFFSET_X + PADDING;
							int iconY = currentY - LINE_HEIGHT + 2; // Align with text line
							graphics.drawImage(icon, iconX, iconY, ICON_SIZE, ICON_SIZE, null);
						}
					}
				}
				
				currentY += LINE_HEIGHT;
			}
			
			// Line 3: Details
			if (config.showGEDetailedInfo())
			{
				String detailText = quantitySold + "/" + totalQuantity + " @ " + PRICE_FORMAT.format(price) + " gp";
				graphics.setColor(COLOR_TEXT);
				graphics.drawString(detailText, x + PADDING, currentY);
				currentY += LINE_HEIGHT;
			}
			
			// Spacing and divider between slots
			if (slot < offers.length - 1)
			{
				if (!config.compactGEOverlay())
				{
					currentY += DIVIDER_PADDING;
				}
				
				// Draw divider line across the entire width
				graphics.setColor(COLOR_DIVIDER);
				int dividerX1 = x + PADDING;
				int dividerX2 = x + textWidth + PADDING;
				graphics.drawLine(dividerX1, currentY, dividerX2, currentY);
				
				currentY += DIVIDER_PADDING;
			}
		}
		
		// No offers message
		if (!hasActiveOffers && config.hideEmptyGESlots())
		{
			graphics.setColor(COLOR_EMPTY);
			drawCenteredString(graphics, "No offers", x, currentY, totalWidth);
		}
		
		// Set bounds for dragging
		setBounds(new Rectangle(x, y, totalWidth, totalHeight));
		
		return new Dimension(totalWidth, totalHeight);
	}
	
	private void drawCenteredString(Graphics2D g, String text, int x, int y, int width)
	{
		FontMetrics metrics = g.getFontMetrics();
		int textX = x + (width - metrics.stringWidth(text)) / 2;
		g.drawString(text, textX, y);
	}
}
