package com.flipsmart;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.AsyncBufferedImage;

import javax.inject.Inject;
import java.awt.*;
import java.text.DecimalFormat;

/**
 * EasyFlip overlay that displays focused flip information in the game.
 * Positioned at the bottom-left of the screen to avoid conflicts with other plugins.
 * Uses a distinct teal/cyan color theme to differentiate from similar plugins.
 */
@Slf4j
public class EasyFlipOverlay extends Overlay
{
	private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("#,###");
	
	// Colors for the overlay - using a distinct teal/cyan theme
	private static final Color COLOR_BACKGROUND = new Color(20, 30, 35, 230);
	private static final Color COLOR_BORDER = new Color(0, 180, 200);
	private static final Color COLOR_BORDER_GLOW = new Color(0, 180, 200, 80);
	private static final Color COLOR_TEXT = new Color(220, 240, 245);
	private static final Color COLOR_BUY = new Color(50, 200, 100);
	private static final Color COLOR_SELL = new Color(255, 180, 50);
	private static final Color COLOR_PRICE = new Color(255, 215, 0);
	private static final Color COLOR_HOTKEY = new Color(100, 255, 200);
	
	// Widget highlight colors (reserved for future use)
	@SuppressWarnings("unused")
	private static final Color COLOR_HIGHLIGHT_BUY = new Color(50, 200, 100, 100);
	@SuppressWarnings("unused")
	private static final Color COLOR_HIGHLIGHT_SELL = new Color(255, 180, 50, 100);
	
	private static final int PADDING = 10;
	private static final int ICON_SIZE = 32;
	
	// GE Interface group ID
	private static final int GE_INTERFACE_GROUP = 465;
	// GE Offer setup group ID  
	private static final int GE_OFFER_GROUP = 162;
	
	private final Client client;
	private final FlipSmartConfig config;
	private final ItemManager itemManager;
	
	@Setter
	private FocusedFlip focusedFlip;
	
	@Inject
	private EasyFlipOverlay(Client client, FlipSmartConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		
		// Position at bottom left - unique position to avoid conflicts
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
	}
	
	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.enableEasyFlip() || focusedFlip == null)
		{
			return null;
		}
		
		// Enable proper anti-aliasing
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		
		// Draw widget highlights if GE is open and setting is enabled
		if (config.highlightGEWidgets() && isGrandExchangeOpen())
		{
			drawGEWidgetHighlights(graphics);
		}
		
		// Draw info panel if enabled
		if (config.showEasyFlipInfo())
		{
			return renderInfoPanel(graphics);
		}
		
		return null;
	}
	
	/**
	 * Render the focused flip info panel
	 */
	private Dimension renderInfoPanel(Graphics2D graphics)
	{
		graphics.setFont(FontManager.getRunescapeFont());
		FontMetrics fm = graphics.getFontMetrics();
		
		// Calculate panel dimensions
		String hotkeyText = String.format("Press [%s] to set price/qty", getHotkeyName());
		String stepText = focusedFlip.isBuying() ? "► BUY" : "► SELL";
		String itemText = focusedFlip.getItemName();
		String priceText = "Price: " + PRICE_FORMAT.format(focusedFlip.getCurrentStepPrice()) + " gp";
		String qtyText = "Qty: " + PRICE_FORMAT.format(focusedFlip.getCurrentStepQuantity());
		
		int maxTextWidth = Math.max(
			Math.max(fm.stringWidth(itemText), fm.stringWidth(hotkeyText)),
			Math.max(fm.stringWidth(priceText) + fm.stringWidth(qtyText) + 20, fm.stringWidth(stepText))
		);
		
		int panelWidth = maxTextWidth + ICON_SIZE + PADDING * 3;
		int panelHeight = PADDING * 2 + 16 * 4 + 8; // 4 lines of text with spacing
		
		// Draw outer glow effect
		graphics.setColor(COLOR_BORDER_GLOW);
		graphics.fillRoundRect(-2, -2, panelWidth + 4, panelHeight + 4, 12, 12);
		
		// Draw background
		graphics.setColor(COLOR_BACKGROUND);
		graphics.fillRoundRect(0, 0, panelWidth, panelHeight, 8, 8);
		
		// Draw border
		graphics.setColor(COLOR_BORDER);
		graphics.setStroke(new BasicStroke(2));
		graphics.drawRoundRect(0, 0, panelWidth, panelHeight, 8, 8);
		
		int y = PADDING + 12;
		int x = PADDING;
		
		// Draw item icon
		AsyncBufferedImage itemImage = itemManager.getImage(focusedFlip.getItemId());
		if (itemImage != null)
		{
			graphics.drawImage(itemImage, x, y - 8, ICON_SIZE, ICON_SIZE, null);
		}
		
		int textX = x + ICON_SIZE + PADDING;
		
		// Draw step indicator with color
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(focusedFlip.isBuying() ? COLOR_BUY : COLOR_SELL);
		graphics.drawString(stepText, textX, y);
		y += 16;
		
		// Draw item name
		graphics.setFont(FontManager.getRunescapeFont());
		graphics.setColor(COLOR_TEXT);
		graphics.drawString(truncateString(itemText, panelWidth - textX - PADDING, fm), textX, y);
		y += 16;
		
		// Draw price and quantity on same line
		graphics.setColor(COLOR_PRICE);
		graphics.drawString(priceText, textX, y);
		graphics.setColor(COLOR_TEXT);
		graphics.drawString(qtyText, textX + fm.stringWidth(priceText) + 15, y);
		y += 18;
		
		// Draw hotkey hint
		graphics.setColor(COLOR_HOTKEY);
		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.drawString(hotkeyText, PADDING, y);
		
		return new Dimension(panelWidth, panelHeight);
	}
	
	/**
	 * Draw highlights on GE widgets
	 */
	private void drawGEWidgetHighlights(Graphics2D graphics)
	{
		// Find GE interface
		Widget geInterface = client.getWidget(GE_INTERFACE_GROUP, 0);
		if (geInterface == null || geInterface.isHidden())
		{
			return;
		}
		
		// Try to find if we're in the offer setup screen
		Widget offerSetup = client.getWidget(GE_OFFER_GROUP, 0);
		if (offerSetup != null && !offerSetup.isHidden())
		{
			// We're in the offer setup - the info panel shows all needed info
			return;
		}
		
		// On main GE screen - try to highlight appropriate slot buttons
		highlightMainGEWidgets(graphics);
	}
	
	/**
	 * Highlight the buy/sell buttons on the main GE screen
	 */
	@SuppressWarnings("unused")
	private void highlightMainGEWidgets(Graphics2D graphics)
	{
		// GE interface uses group 465
		// Future: Could highlight specific buy/sell buttons if widget IDs are stable
		// For now, the info panel provides clear guidance on what action to take
	}
	
	/**
	 * Check if the Grand Exchange interface is open
	 */
	public boolean isGrandExchangeOpen()
	{
		Widget geWidget = client.getWidget(GE_INTERFACE_GROUP, 0);
		return geWidget != null && !geWidget.isHidden();
	}
	
	/**
	 * Check if the GE offer setup screen is visible
	 */
	public boolean isOfferSetupOpen()
	{
		Widget offerSetup = client.getWidget(GE_OFFER_GROUP, 0);
		return offerSetup != null && !offerSetup.isHidden();
	}
	
	/**
	 * Get the name of the configured hotkey
	 */
	private String getHotkeyName()
	{
		return config.easyFlipHotkey().toString();
	}
	
	/**
	 * Truncate a string if it exceeds max width
	 */
	private String truncateString(String str, int maxWidth, FontMetrics fm)
	{
		if (fm.stringWidth(str) <= maxWidth)
		{
			return str;
		}
		
		String ellipsis = "...";
		int ellipsisWidth = fm.stringWidth(ellipsis);
		
		int len = str.length();
		while (len > 0 && fm.stringWidth(str.substring(0, len)) + ellipsisWidth > maxWidth)
		{
			len--;
		}
		
		return str.substring(0, len) + ellipsis;
	}
	
	/**
	 * Clear the focused flip
	 */
	public void clearFocus()
	{
		this.focusedFlip = null;
	}
	
	/**
	 * Check if there's a focused flip
	 */
	public boolean hasFocus()
	{
		return focusedFlip != null;
	}
	
	/**
	 * Get the focused flip
	 */
	public FocusedFlip getFocusedFlip()
	{
		return focusedFlip;
	}
}
