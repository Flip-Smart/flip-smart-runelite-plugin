package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.SpriteID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
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
import java.awt.event.MouseEvent;
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
	private final SpriteManager spriteManager;
	
	private Point preferredLocation = new Point(100, 100);
	private boolean isCollapsed = false;
	private Rectangle collapseButtonBounds = new Rectangle();
	private BufferedImage geIcon;

	@Inject
	private GrandExchangeOverlay(Client client, FlipSmartConfig config, ItemManager itemManager, SpriteManager spriteManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(OverlayPriority.MED);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setResizable(false);
		
		getMenuEntries().add(new OverlayMenuEntry(RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, "GE Tracker"));
		
		// Load large coin stack icon - ItemManager can render with quantity to show stack size
		// Using a high quantity (1M) will display as the large coin pile
		AsyncBufferedImage coinsImage = itemManager.getImage(995, 1000000, false);
		if (coinsImage != null)
		{
			coinsImage.onLoaded(() -> geIcon = coinsImage);
		}
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
	// No extra icon column width since icons now overlay on the progress bars
	int totalWidth = textWidth + (PADDING * 2);
	
	// Draw at (0,0) - the overlay system handles positioning
	int x = 0;
	int y = 0;
	int currentY = y + PADDING + LINE_HEIGHT; // Start below top padding
	
	// If collapsed, only show the header
	if (isCollapsed)
	{
		int collapsedHeight = LINE_HEIGHT + (PADDING * 2) + 4; // Title + padding + extra spacing
		
		// Draw background
		graphics.setColor(COLOR_BACKGROUND);
		graphics.fillRect(x, y, totalWidth, collapsedHeight);
		
		// Draw border
		graphics.setColor(COLOR_BORDER);
		graphics.drawRect(x, y, totalWidth, collapsedHeight);
		graphics.drawRect(x + 1, y + 1, totalWidth - 2, collapsedHeight - 2);
		
		// Draw title
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(Color.BLACK);
		String title = "Grand Exchange Offers";
		FontMetrics metrics = graphics.getFontMetrics();
		int titleX = x + (totalWidth - metrics.stringWidth(title)) / 2;
		graphics.drawString(title, titleX + 1, currentY + 1);
		graphics.setColor(COLOR_TITLE);
		graphics.drawString(title, titleX, currentY);
		
		// Draw large coin stack icon button (collapsed state)
		if (geIcon != null)
		{
			int iconSize = 24; // Larger icon
			int iconX = x + PADDING - 2;
			int iconY = y + PADDING - 4;
			// Bigger click box - add padding around the icon
			int clickPadding = 4;
			collapseButtonBounds = new Rectangle(
				iconX - clickPadding, 
				iconY - clickPadding, 
				iconSize + (clickPadding * 2), 
				iconSize + (clickPadding * 2)
			);
			graphics.drawImage(geIcon, iconX, iconY, iconSize, iconSize, null);
		}
		
		return new Dimension(totalWidth, collapsedHeight);
	}
	
	// Count lines to calculate height (not collapsed)
	int lineCount = 1; // Title
		boolean hasActiveOffers = false;
		int dividerCount = 0;
		
		for (int i = 0; i < offers.length; i++)
		{
		GrandExchangeOffer offer = offers[i];
		if (offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			continue; // Always hide empty slots
		}
		else
		{
			hasActiveOffers = true;
			lineCount++; // Slot line
			if (config.showGEItemNames()) lineCount++; // Item name line
			lineCount++; // Progress bar line (always present now)
			
			// Check if there's another non-empty slot for divider
			boolean needsDivider = false;
			for (int nextSlot = i + 1; nextSlot < offers.length; nextSlot++)
			{
				if (offers[nextSlot].getState() != GrandExchangeOfferState.EMPTY)
				{
					needsDivider = true;
					break;
				}
			}
			if (needsDivider)
			{
				dividerCount++;
			}
		}
		}
	
	if (!hasActiveOffers)
	{
		lineCount++; // "No offers" message
	}
		
	// Add space for dividers (-11px before + 19px after each divider = 8px total)
	int totalHeight = (lineCount * LINE_HEIGHT) + (PADDING * 2);
	totalHeight += dividerCount * 8;
	totalHeight += 4; // Add 4px padding after title
		
		// Draw background with GE-style brown
		graphics.setColor(COLOR_BACKGROUND);
		graphics.fillRect(x, y, totalWidth, totalHeight);
		
		// Draw border
		graphics.setColor(COLOR_BORDER);
		graphics.drawRect(x, y, totalWidth, totalHeight);
		graphics.drawRect(x + 1, y + 1, totalWidth - 2, totalHeight - 2); // Double border for depth
		
		// Draw title with bold font
		graphics.setFont(FontManager.getRunescapeBoldFont());
		
		// Draw title shadow
		graphics.setColor(Color.BLACK);
		String title = "Grand Exchange Offers";
		FontMetrics metrics = graphics.getFontMetrics();
		int titleX = x + (totalWidth - metrics.stringWidth(title)) / 2;
		graphics.drawString(title, titleX + 1, currentY + 1);
		
	// Draw title text
	graphics.setColor(COLOR_TITLE);
	graphics.drawString(title, titleX, currentY);
	
	// Draw large coin stack icon button when expanded
	if (geIcon != null)
	{
		int iconSize = 24; // Larger icon
		int iconX = x + PADDING - 2;
		int iconY = y + PADDING - 4;
		// Bigger click box - add padding around the icon
		int clickPadding = 4;
		collapseButtonBounds = new Rectangle(
			iconX - clickPadding, 
			iconY - clickPadding, 
			iconSize + (clickPadding * 2), 
			iconSize + (clickPadding * 2)
		);
		graphics.drawImage(geIcon, iconX, iconY, iconSize, iconSize, null);
	}
	
	currentY += LINE_HEIGHT;
	
	// Add 4px padding after title
	currentY += 4;
	
	// Reset to regular font for content
		graphics.setFont(FontManager.getRunescapeFont());
		
		// Render each slot
		for (int slot = 0; slot < offers.length; slot++)
		{
		GrandExchangeOffer offer = offers[slot];
		
		if (offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			continue; // Always hide empty slots
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
			
		// Draw divider BEFORE this item (except for first item)
		if (slot > 0)
		{
			// Check if previous slot was also visible
			boolean previousWasVisible = false;
			for (int prevSlot = slot - 1; prevSlot >= 0; prevSlot--)
			{
				if (offers[prevSlot].getState() != GrandExchangeOfferState.EMPTY)
				{
					previousWasVisible = true;
					break;
				}
			}
				
			if (previousWasVisible)
			{
				// Move divider up - 4px higher than before (-7 - 4 = -11)
				currentY += -11;
				
				// Draw divider line
				graphics.setColor(COLOR_DIVIDER);
				int dividerX1 = x + PADDING;
				int dividerX2 = x + textWidth + PADDING;
				graphics.drawLine(dividerX1, currentY, dividerX2, currentY);
				
				// Add space after divider to maintain 12px total spacing
				currentY += 19;
			}
			}
			
			// Line 1: Slot label only
			String slotLabel = (slot + 1) + ". " + (isBuy ? "Buy" : "Sell");
			// Draw shadow
			graphics.setColor(Color.BLACK);
			graphics.drawString(slotLabel, x + PADDING + 1, currentY + 1);
			// Draw main text
			graphics.setColor(isBuy ? COLOR_BUY : COLOR_SELL);
			graphics.drawString(slotLabel, x + PADDING, currentY);
			currentY += LINE_HEIGHT;
			
			// Line 2: Item name with icon on the right
			if (config.showGEItemNames())
			{
				// Draw shadow
				graphics.setColor(Color.BLACK);
				graphics.drawString(itemName, x + PADDING + 1, currentY + 1);
				// Draw main text
				graphics.setColor(COLOR_TEXT);
				graphics.drawString(itemName, x + PADDING, currentY);
				
				currentY += LINE_HEIGHT;
			}
			
			// Line 3: Details/Progress bar line
			// Always show details with price info
			String detailText = quantitySold + "/" + totalQuantity + " @ " + PRICE_FORMAT.format(price) + " gp";
			// Draw shadow
			graphics.setColor(Color.BLACK);
			graphics.drawString(detailText, x + PADDING + 1, currentY + 1);
			// Draw main text
			graphics.setColor(COLOR_TEXT);
			graphics.drawString(detailText, x + PADDING, currentY);
	
	// Always draw progress bar on this line (12px more spacing total: -10 changed to +2)
	int progressBarX = x + textWidth - PROGRESS_BAR_WIDTH + PADDING + 2;
	int progressBarY = currentY - PROGRESS_BAR_HEIGHT + 2;
			
			// Progress bar background
			graphics.setColor(COLOR_PROGRESS_BG);
			graphics.fillRect(progressBarX, progressBarY, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
			
			// Progress bar border
			graphics.setColor(COLOR_PROGRESS_BORDER);
			graphics.drawRect(progressBarX, progressBarY, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
			
			// Progress fill
			int fillWidth = (int) (PROGRESS_BAR_WIDTH * (percentage / 100.0));
			graphics.setColor(statusColor);
			graphics.fillRect(progressBarX + 1, progressBarY + 1, fillWidth - 1, PROGRESS_BAR_HEIGHT - 2);
			
			// Progress text centered with shadow
			String progressText = PERCENTAGE_FORMAT.format(percentage) + "%";
			
			// Use RuneLite's small font for crisp rendering
			Font originalFont = graphics.getFont();
			graphics.setFont(FontManager.getRunescapeSmallFont());
			
			FontMetrics fm = graphics.getFontMetrics();
			int progressTextWidth = fm.stringWidth(progressText);
			int textX = progressBarX + (PROGRESS_BAR_WIDTH - progressTextWidth) / 2;
			int textY = progressBarY + PROGRESS_BAR_HEIGHT - 2; // Adjusted Y position for small font
			
			graphics.setColor(Color.BLACK);
			graphics.drawString(progressText, textX + 1, textY + 1);
			graphics.setColor(Color.WHITE);
			graphics.drawString(progressText, textX, textY);
			
			// Restore original font
			graphics.setFont(originalFont);
			
			// Draw icon stacked on top of progress bar
			if (config.showGEItemIcons())
			{
				AsyncBufferedImage itemImage = itemManager.getImage(itemId);
				if (itemImage != null)
				{
					BufferedImage icon = itemImage;
					if (icon.getWidth() > 0)
					{
					// Center the icon horizontally on the progress bar, shifted 24px up (36 - 12)
					int iconX = progressBarX + (PROGRESS_BAR_WIDTH - ICON_SIZE) / 2;
					int iconY = progressBarY - ICON_SIZE / 2 + PROGRESS_BAR_HEIGHT / 2 - 24; // Shifted 24px up
					graphics.drawImage(icon, iconX, iconY, ICON_SIZE, ICON_SIZE, null);
					}
				}
			}
			
			currentY += LINE_HEIGHT;
		}
	
	// No offers message
	if (!hasActiveOffers)
	{
		graphics.setColor(COLOR_EMPTY);
		drawCenteredString(graphics, "No offers", x, currentY, totalWidth);
	}
		
		// Return dimensions - overlay system uses this for the bounds/hit box
		return new Dimension(totalWidth, totalHeight);
	}
	
	private void drawCenteredString(Graphics2D g, String text, int x, int y, int width)
	{
		FontMetrics metrics = g.getFontMetrics();
		int textX = x + (width - metrics.stringWidth(text)) / 2;
		g.drawString(text, textX, y);
	}
	
	public void toggleCollapse()
	{
		isCollapsed = !isCollapsed;
	}
	
	public boolean isCollapsed()
	{
		return isCollapsed;
	}
	
	public Rectangle getCollapseButtonBounds()
	{
		return collapseButtonBounds;
	}
}
