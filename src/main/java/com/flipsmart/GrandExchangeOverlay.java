package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.AsyncBufferedImage;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;

import static net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG;
import static net.runelite.client.ui.overlay.OverlayManager.OPTION_CONFIGURE;

/**
 * In-game overlay that displays all 8 Grand Exchange offer slots with real-time status.
 * Hidden when the player is at the Grand Exchange area, shown everywhere else.
 */
public class GrandExchangeOverlay extends Overlay
{
	private static final DecimalFormat PERCENTAGE_FORMAT = new DecimalFormat("0");
	private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("#,###");
	
	// String constants
	private static final String OVERLAY_TITLE = "Exchange Viewer";
	private static final String NO_OFFERS_MESSAGE = "No offers";
	
	// Grand Exchange region ID
	private static final int GE_REGION_ID = 12598;
	
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

	// Competitiveness indicator colors
	private static final Color COLOR_COMPETITIVE = new Color(50, 200, 80);       // Green
	private static final Color COLOR_UNCOMPETITIVE = new Color(220, 60, 60);     // Red
	private static final Color COLOR_UNKNOWN = new Color(150, 150, 150);         // Gray
	private static final Color COLOR_TIMER = new Color(200, 200, 200);           // Light gray for timer text
	private static final Color COLOR_BORDER_COMPETITIVE = new Color(50, 200, 80, 180);     // Semi-transparent green border
	private static final Color COLOR_BORDER_UNCOMPETITIVE = new Color(220, 60, 60, 180);   // Semi-transparent red border

	private static final int PADDING = 8;
	private static final int LINE_HEIGHT = 17;
	private static final int ICON_SIZE = 32;
	private static final int PROGRESS_BAR_WIDTH = 60;
	private static final int PROGRESS_BAR_HEIGHT = 14;
	private static final int MAX_ITEM_NAME_LENGTH = 12;
	
	// Compact mode constants
	private static final int COMPACT_LINE_HEIGHT = 14;
	private static final int COMPACT_ICON_SIZE = 18;
	
	private final Client client;
	private final FlipSmartConfig config;
	private final ItemManager itemManager;
	private final FlipSmartPlugin plugin;

	private boolean isCollapsed = false;
	private Rectangle collapseButtonBounds = new Rectangle();

	@Inject
	private GrandExchangeOverlay(Client client, FlipSmartConfig config, ItemManager itemManager, FlipSmartPlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		this.plugin = plugin;
		
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(OverlayPriority.MED);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setResizable(false);
		
		getMenuEntries().add(new OverlayMenuEntry(RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, OVERLAY_TITLE));
	}
	
	/**
	 * Check if the player is at the Grand Exchange area.
	 */
	private boolean isAtGrandExchange()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return false;
		}
		
		// Get player's current region ID
		int regionId = localPlayer.getWorldLocation().getRegionID();
		return regionId == GE_REGION_ID;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showGEOverlay())
		{
			return null;
		}
		
		// Hide when player is at the Grand Exchange area
		if (isAtGrandExchange())
		{
			return null;
		}
		
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		
		// GE offers may be null if player hasn't opened GE this session
		// In that case, don't show overlay
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
		
		// Render based on display size mode
		switch (config.exchangeViewerSize())
		{
			case COMPACT:
				return renderCompact(graphics, offers);
			case FULL:
			default:
				return renderFull(graphics, offers);
		}
	}
	
	/**
	 * Render the full-size overlay (original layout).
	 */
	private Dimension renderFull(Graphics2D graphics, GrandExchangeOffer[] offers)
	{
		int textWidth = 200;
		int totalWidth = textWidth + (PADDING * 2);
		
		int x = 0;
		int y = 0;
		int currentY = y + PADDING + LINE_HEIGHT;
		
		// If collapsed, only show the header
		if (isCollapsed)
		{
			int collapsedHeight = LINE_HEIGHT + (PADDING * 2) + 4;
			
			// Draw background
			graphics.setColor(COLOR_BACKGROUND);
			graphics.fillRect(x, y, totalWidth, collapsedHeight);
			
			// Draw border
			graphics.setColor(COLOR_BORDER);
			graphics.drawRect(x, y, totalWidth, collapsedHeight);
			graphics.drawRect(x + 1, y + 1, totalWidth - 2, collapsedHeight - 2);
			
			// Draw centered title
			graphics.setFont(FontManager.getRunescapeBoldFont());
			FontMetrics metrics = graphics.getFontMetrics();
			int titleX = x + (totalWidth - metrics.stringWidth(OVERLAY_TITLE)) / 2;
			graphics.setColor(Color.BLACK);
			graphics.drawString(OVERLAY_TITLE, titleX + 1, currentY + 1);
			graphics.setColor(COLOR_TITLE);
			graphics.drawString(OVERLAY_TITLE, titleX, currentY);
			
			// Set collapse button bounds for the entire header area
			collapseButtonBounds = new Rectangle(x, y, totalWidth, collapsedHeight);
			
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
				continue;
			}
			
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
		
		if (!hasActiveOffers)
		{
			lineCount++; // "No offers" message
		}
		
		int totalHeight = (lineCount * LINE_HEIGHT) + (PADDING * 2);
		totalHeight += dividerCount * 8;
		totalHeight += 4; // Add 4px padding after title
		
		// Draw background with GE-style brown
		graphics.setColor(COLOR_BACKGROUND);
		graphics.fillRect(x, y, totalWidth, totalHeight);
		
		// Draw border
		graphics.setColor(COLOR_BORDER);
		graphics.drawRect(x, y, totalWidth, totalHeight);
		graphics.drawRect(x + 1, y + 1, totalWidth - 2, totalHeight - 2);
		
		// Draw centered title with bold font
		graphics.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics metrics = graphics.getFontMetrics();
		int titleX = x + (totalWidth - metrics.stringWidth(OVERLAY_TITLE)) / 2;
		
		// Draw title shadow
		graphics.setColor(Color.BLACK);
		graphics.drawString(OVERLAY_TITLE, titleX + 1, currentY + 1);
		
		// Draw title text
		graphics.setColor(COLOR_TITLE);
		graphics.drawString(OVERLAY_TITLE, titleX, currentY);
		
		// Set collapse button bounds for the title area
		collapseButtonBounds = new Rectangle(x, y, totalWidth, LINE_HEIGHT + PADDING);
		
		currentY += LINE_HEIGHT;
		currentY += 4; // Add 4px padding after title
		
		// Reset to regular font for content
		graphics.setFont(FontManager.getRunescapeFont());
		
		// Render each slot
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			
			if (offer.getState() == GrandExchangeOfferState.EMPTY)
			{
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
			
			// Determine status color based on offer state
			Color statusColor = getStatusColor(state, isBuy);
			
			// Draw divider BEFORE this item (except for first visible)
			if (slot > 0)
			{
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
					currentY += -11;
					
					graphics.setColor(COLOR_DIVIDER);
					int dividerX1 = x + PADDING;
					int dividerX2 = x + textWidth + PADDING;
					graphics.drawLine(dividerX1, currentY, dividerX2, currentY);
					
					currentY += 19;
				}
			}
			
			// Get tracked offer for timer and competitiveness
			FlipSmartPlugin.TrackedOffer trackedOffer = plugin.getTrackedOffer(slot);
			FlipSmartPlugin.OfferCompetitiveness competitiveness = plugin.calculateCompetitiveness(trackedOffer);

			// Calculate slot bounds for border highlighting
			int slotStartY = currentY - LINE_HEIGHT + 4;
			int slotHeight = LINE_HEIGHT * (config.showGEItemNames() ? 3 : 2) + 2;

			// Draw slot border based on competitiveness (if enabled)
			if (config.highlightSlotBorders())
			{
				drawSlotBorder(graphics, x + PADDING - 2, slotStartY, textWidth + 4, slotHeight, competitiveness);
			}

			// Line 1: Slot label + timer + competitiveness indicator
			String slotLabel = (slot + 1) + ". " + (isBuy ? "Buy" : "Sell");
			graphics.setColor(Color.BLACK);
			graphics.drawString(slotLabel, x + PADDING + 1, currentY + 1);
			graphics.setColor(isBuy ? COLOR_BUY : COLOR_SELL);
			graphics.drawString(slotLabel, x + PADDING, currentY);

			// Draw timer after slot label (if enabled and timestamp exists)
			if (config.showOfferTimers() && trackedOffer != null && trackedOffer.createdAtMillis > 0)
			{
				String timerText = TimeUtils.formatElapsedTime(trackedOffer.createdAtMillis);
				FontMetrics fm = graphics.getFontMetrics();
				int slotLabelWidth = fm.stringWidth(slotLabel);
				int timerX = x + PADDING + slotLabelWidth + 8;

				graphics.setColor(Color.BLACK);
				graphics.drawString(timerText, timerX + 1, currentY + 1);
				graphics.setColor(COLOR_TIMER);
				graphics.drawString(timerText, timerX, currentY);
			}

			// Draw competitiveness indicator on the right side (if enabled)
			if (config.showCompetitivenessIndicators())
			{
				int indicatorX = x + textWidth - 4;
				drawCompetitivenessIndicator(graphics, indicatorX, currentY, competitiveness);
			}

			currentY += LINE_HEIGHT;
			
			// Line 2: Item name
			if (config.showGEItemNames())
			{
				graphics.setColor(Color.BLACK);
				graphics.drawString(itemName, x + PADDING + 1, currentY + 1);
				graphics.setColor(COLOR_TEXT);
				graphics.drawString(itemName, x + PADDING, currentY);
				currentY += LINE_HEIGHT;
			}
			
			// Line 3: Details/Progress bar line
			String detailText = quantitySold + "/" + totalQuantity + " @ " + PRICE_FORMAT.format(price) + " gp";
			graphics.setColor(Color.BLACK);
			graphics.drawString(detailText, x + PADDING + 1, currentY + 1);
			graphics.setColor(COLOR_TEXT);
			graphics.drawString(detailText, x + PADDING, currentY);
			
			// Draw progress bar
			int progressBarX = x + textWidth - PROGRESS_BAR_WIDTH + PADDING + 2;
			int progressBarY = currentY - PROGRESS_BAR_HEIGHT + 2;
			
			drawProgressBar(graphics, progressBarX, progressBarY, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT, percentage, statusColor);
			
			// Draw icon stacked on top of progress bar
			if (config.showGEItemIcons())
			{
				AsyncBufferedImage itemImage = itemManager.getImage(itemId);
				if (itemImage != null)
				{
					BufferedImage icon = itemImage;
					if (icon.getWidth() > 0)
					{
						int iconX = progressBarX + (PROGRESS_BAR_WIDTH - ICON_SIZE) / 2;
						int iconY = progressBarY - ICON_SIZE / 2 + PROGRESS_BAR_HEIGHT / 2 - 24;
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
			drawCenteredString(graphics, NO_OFFERS_MESSAGE, x, currentY, totalWidth);
		}
		
		return new Dimension(totalWidth, totalHeight);
	}
	
	/**
	 * Render the compact (mini) overlay - shows item name and percentage only.
	 */
	private Dimension renderCompact(Graphics2D graphics, GrandExchangeOffer[] offers)
	{
		int totalWidth = 150;
		
		int x = 0;
		int y = 0;
		int currentY = y + PADDING + COMPACT_LINE_HEIGHT;
		
		// Count active offers
		int activeCount = 0;
		for (GrandExchangeOffer offer : offers)
		{
			if (offer.getState() != GrandExchangeOfferState.EMPTY)
			{
				activeCount++;
			}
		}
		
		// Calculate height: title + active offers + padding
		int totalHeight = COMPACT_LINE_HEIGHT + (PADDING * 2);
		if (activeCount == 0)
		{
			totalHeight += COMPACT_LINE_HEIGHT; // "No offers" line
		}
		else
		{
			totalHeight += activeCount * COMPACT_LINE_HEIGHT;
		}
		
		// Draw background
		graphics.setColor(COLOR_BACKGROUND);
		graphics.fillRect(x, y, totalWidth, totalHeight);
		
		// Draw border
		graphics.setColor(COLOR_BORDER);
		graphics.drawRect(x, y, totalWidth, totalHeight);
		
		// Draw centered title
		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics metrics = graphics.getFontMetrics();
		int titleX = x + (totalWidth - metrics.stringWidth(OVERLAY_TITLE)) / 2;
		graphics.setColor(Color.BLACK);
		graphics.drawString(OVERLAY_TITLE, titleX + 1, currentY + 1);
		graphics.setColor(COLOR_TITLE);
		graphics.drawString(OVERLAY_TITLE, titleX, currentY);
		
		// Set collapse button bounds
		collapseButtonBounds = new Rectangle(x, y, totalWidth, totalHeight);
		
		currentY += COMPACT_LINE_HEIGHT + 2;
		
		// Render each slot
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			
			if (offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			
			GrandExchangeOfferState state = offer.getState();
			int itemId = offer.getItemId();
			int quantitySold = offer.getQuantitySold();
			int totalQuantity = offer.getTotalQuantity();
			
			boolean isBuy = state == GrandExchangeOfferState.BUYING || 
							state == GrandExchangeOfferState.BOUGHT ||
							state == GrandExchangeOfferState.CANCELLED_BUY;
			
			double percentage = totalQuantity > 0 ? (quantitySold * 100.0) / totalQuantity : 0;
			String itemName = itemManager.getItemComposition(itemId).getName();
			
			// Truncate item name if too long
			if (itemName.length() > MAX_ITEM_NAME_LENGTH)
			{
				itemName = itemName.substring(0, MAX_ITEM_NAME_LENGTH - 1) + "..";
			}
			
			Color statusColor = getStatusColor(state, isBuy);

			// Get tracked offer for timer and competitiveness
			FlipSmartPlugin.TrackedOffer trackedOffer = plugin.getTrackedOffer(slot);
			FlipSmartPlugin.OfferCompetitiveness competitiveness = plugin.calculateCompetitiveness(trackedOffer);

			// Draw compact icon
			if (config.showGEItemIcons())
			{
				AsyncBufferedImage itemImage = itemManager.getImage(itemId);
				if (itemImage != null && itemImage.getWidth() > 0)
				{
					int iconX = x + PADDING - 2;
					int iconY = currentY - COMPACT_ICON_SIZE + 4;
					graphics.drawImage(itemImage, iconX, iconY, COMPACT_ICON_SIZE, COMPACT_ICON_SIZE, null);
				}
			}

			// Draw item name with B/S indicator
			int textX = x + PADDING + (config.showGEItemIcons() ? COMPACT_ICON_SIZE : 0);
			String displayText = (isBuy ? "B: " : "S: ") + itemName;
			graphics.setColor(Color.BLACK);
			graphics.drawString(displayText, textX + 1, currentY + 1);
			graphics.setColor(isBuy ? COLOR_BUY : COLOR_SELL);
			graphics.drawString(displayText, textX, currentY);

			// Calculate right-side content width for timer, percentage, and indicator
			int rightX = x + totalWidth - PADDING;

			// Draw competitiveness dot on far right (if enabled)
			if (config.showCompetitivenessIndicators())
			{
				drawCompetitivenessDot(graphics, rightX - 6, currentY - 8, competitiveness);
				rightX -= 10;
			}

			// Draw percentage
			String pctText = PERCENTAGE_FORMAT.format(percentage) + "%";
			int pctWidth = metrics.stringWidth(pctText);
			int pctX = rightX - pctWidth;
			graphics.setColor(Color.BLACK);
			graphics.drawString(pctText, pctX + 1, currentY + 1);
			graphics.setColor(statusColor);
			graphics.drawString(pctText, pctX, currentY);
			rightX = pctX - 4;

			// Draw short timer (if enabled and timestamp exists)
			if (config.showOfferTimers() && trackedOffer != null && trackedOffer.createdAtMillis > 0)
			{
				String timerText = TimeUtils.formatElapsedTimeShort(trackedOffer.createdAtMillis);
				int timerWidth = metrics.stringWidth(timerText);
				int timerX = rightX - timerWidth;
				graphics.setColor(Color.BLACK);
				graphics.drawString(timerText, timerX + 1, currentY + 1);
				graphics.setColor(COLOR_TIMER);
				graphics.drawString(timerText, timerX, currentY);
			}

			currentY += COMPACT_LINE_HEIGHT;
		}
		
		// No offers message
		if (activeCount == 0)
		{
			graphics.setColor(COLOR_EMPTY);
			drawCenteredString(graphics, NO_OFFERS_MESSAGE, x, currentY, totalWidth);
		}
		
		return new Dimension(totalWidth, totalHeight);
	}
	
	/**
	 * Draw a progress bar with the given parameters.
	 */
	private void drawProgressBar(Graphics2D graphics, int x, int y, int width, int height, double percentage, Color fillColor)
	{
		// Background
		graphics.setColor(COLOR_PROGRESS_BG);
		graphics.fillRect(x, y, width, height);
		
		// Border
		graphics.setColor(COLOR_PROGRESS_BORDER);
		graphics.drawRect(x, y, width, height);
		
		// Fill
		int fillWidth = (int) (width * (percentage / 100.0));
		if (fillWidth > 0)
		{
			graphics.setColor(fillColor);
			graphics.fillRect(x + 1, y + 1, fillWidth - 1, height - 2);
		}
		
		// Percentage text centered
		String progressText = PERCENTAGE_FORMAT.format(percentage) + "%";
		Font originalFont = graphics.getFont();
		graphics.setFont(FontManager.getRunescapeSmallFont());
		
		FontMetrics fm = graphics.getFontMetrics();
		int progressTextWidth = fm.stringWidth(progressText);
		int textX = x + (width - progressTextWidth) / 2;
		int textY = y + height - 2;
		
		graphics.setColor(Color.BLACK);
		graphics.drawString(progressText, textX + 1, textY + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(progressText, textX, textY);
		
		graphics.setFont(originalFont);
	}
	
	/**
	 * Get the status color for an offer state.
	 */
	private Color getStatusColor(GrandExchangeOfferState state, boolean isBuy)
	{
		switch (state)
		{
			case BOUGHT:
			case SOLD:
				return COLOR_COMPLETE;
			case CANCELLED_BUY:
			case CANCELLED_SELL:
				return COLOR_CANCELLED;
			case BUYING:
			case SELLING:
				return isBuy ? COLOR_BUY : COLOR_SELL;
			default:
				return COLOR_EMPTY;
		}
	}
	
	private void drawCenteredString(Graphics2D g, String text, int x, int y, int width)
	{
		FontMetrics metrics = g.getFontMetrics();
		int textX = x + (width - metrics.stringWidth(text)) / 2;
		g.drawString(text, textX, y);
	}

	/**
	 * Draw competitiveness indicator (checkmark, X, or question mark)
	 */
	private void drawCompetitivenessIndicator(Graphics2D graphics, int x, int y, FlipSmartPlugin.OfferCompetitiveness competitiveness)
	{
		Font originalFont = graphics.getFont();
		graphics.setFont(new Font("Arial", Font.BOLD, 12));

		switch (competitiveness)
		{
			case COMPETITIVE:
				graphics.setColor(COLOR_COMPETITIVE);
				graphics.drawString("\u2713", x, y);  // Unicode checkmark
				break;
			case UNCOMPETITIVE:
				graphics.setColor(COLOR_UNCOMPETITIVE);
				graphics.drawString("\u2717", x, y);  // Unicode X mark
				break;
			case UNKNOWN:
			default:
				graphics.setColor(COLOR_UNKNOWN);
				graphics.drawString("?", x, y);
				break;
		}

		graphics.setFont(originalFont);
	}

	/**
	 * Draw a small competitiveness dot for compact mode
	 */
	private void drawCompetitivenessDot(Graphics2D graphics, int x, int y, FlipSmartPlugin.OfferCompetitiveness competitiveness)
	{
		int dotSize = 6;
		Color dotColor;

		switch (competitiveness)
		{
			case COMPETITIVE:
				dotColor = COLOR_COMPETITIVE;
				break;
			case UNCOMPETITIVE:
				dotColor = COLOR_UNCOMPETITIVE;
				break;
			case UNKNOWN:
			default:
				dotColor = COLOR_UNKNOWN;
				break;
		}

		graphics.setColor(dotColor);
		graphics.fillOval(x, y, dotSize, dotSize);
	}

	/**
	 * Draw a colored border around an offer slot based on competitiveness
	 */
	private void drawSlotBorder(Graphics2D graphics, int x, int y, int width, int height, FlipSmartPlugin.OfferCompetitiveness competitiveness)
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
			case UNKNOWN:
			default:
				return; // No border for unknown
		}

		Stroke originalStroke = graphics.getStroke();
		graphics.setColor(borderColor);
		graphics.setStroke(new BasicStroke(2));
		graphics.drawRoundRect(x, y, width, height, 4, 4);
		graphics.setStroke(originalStroke);
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
