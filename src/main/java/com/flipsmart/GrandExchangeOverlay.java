package com.flipsmart;

import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.util.ItemUtils;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.AsyncBufferedImage;

import java.awt.*;

import static net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG;
import static net.runelite.client.ui.overlay.OverlayManager.OPTION_CONFIGURE;

/**
 * In-game overlay that displays all 8 Grand Exchange offer slots with real-time status.
 * Shown regardless of world location; hidden only while a trade or inventory window
 * (Grand Exchange, GE collection box, bank, or bank deposit box) is open.
 */
public class GrandExchangeOverlay extends Overlay
{
	private static final DecimalFormat PERCENTAGE_FORMAT = new DecimalFormat("0");
	
	// String constants
	private static final String OVERLAY_TITLE = "Exchange Viewer";
	private static final String NO_OFFERS_MESSAGE = "No offers";

	private static final Color COLOR_BUY = new Color(0, 128, 0);  // Dark green
	private static final Color COLOR_SELL = new Color(180, 0, 0); // Dark red
	private static final Color COLOR_COMPLETE = new Color(200, 180, 50); // Gold
	private static final Color COLOR_EMPTY = new Color(128, 128, 128); // Gray
	private static final Color COLOR_CANCELLED = new Color(200, 100, 0); // Orange
	private static final Color COLOR_BACKGROUND = new Color(62, 53, 41); // Dark brown (GE style)
	private static final Color COLOR_BORDER = new Color(0, 0, 0); // Black
	private static final Color COLOR_TITLE = new Color(255, 215, 0); // Gold
	

	private static final int PADDING = 8;
	private static final int MAX_ITEM_NAME_LENGTH = 12;
	
	// Compact mode constants
	private static final int COMPACT_LINE_HEIGHT = 14;
	private static final int COMPACT_ICON_SIZE = 18;

	// Layout spacing constants

	// Compact mode spacing constants
	/** Extra Y spacing after title in compact mode */
	private static final int COMPACT_TITLE_SPACING = 2;
	/** X adjustment for compact icon positioning */
	private static final int COMPACT_ICON_X_OFFSET = -2;
	/** Y adjustment for compact icon positioning */
	private static final int COMPACT_ICON_Y_OFFSET = 4;
	
	private final Client client;
	private final FlipSmartConfig config;
	private final ItemManager itemManager;


	@Inject
	private GrandExchangeOverlay(Client client, FlipSmartConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(OverlayPriority.MED);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setResizable(false);
		
		getMenuEntries().add(new OverlayMenuEntry(RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, OVERLAY_TITLE));
	}
	
	/**
	 * Whether the overlay should be suppressed because a trade or inventory window
	 * is open on screen — its contents would be redundant with, or visually conflict
	 * with, the native window. Covers the Grand Exchange, its collection box, the
	 * bank, and the bank deposit box.
	 */
	static boolean shouldSuppressOverlay(Client client)
	{
		return isInterfaceOpen(client, InterfaceID.GE_OFFERS)
			|| isInterfaceOpen(client, InterfaceID.GE_COLLECT)
			|| isInterfaceOpen(client, InterfaceID.BANKMAIN)
			|| isInterfaceOpen(client, InterfaceID.BANK_DEPOSITBOX);
	}

	private static boolean isInterfaceOpen(Client client, int groupId)
	{
		Widget root = client.getWidget(groupId, 0);
		return root != null && !root.isHidden();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showGEOverlay())
		{
			return null;
		}
		
		// Hide while a GE/bank window is open — the native window already shows this info
		if (shouldSuppressOverlay(client))
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
		
		return renderCompact(graphics, offers);
	}
	
	/**
	 * Panel background, border and centred drop-shadowed title, shared by the collapsed
	 * header and both overlay layouts. Compact draws a single border and the small font;
	 * the other two double the border and use bold. Sets the font, so callers needing
	 * metrics afterwards read them from the graphics context.
	 */
	private void drawPanelChrome(Graphics2D graphics, int x, int y, int width, int height, int titleBaselineY,
		boolean compact)
	{
		graphics.setColor(COLOR_BACKGROUND);
		graphics.fillRect(x, y, width, height);
		graphics.setColor(COLOR_BORDER);
		graphics.drawRect(x, y, width, height);
		if (!compact)
		{
			graphics.drawRect(x + 1, y + 1, width - 2, height - 2);
		}
		graphics.setFont(compact ? FontManager.getRunescapeSmallFont() : FontManager.getRunescapeBoldFont());
		FontMetrics metrics = graphics.getFontMetrics();
		int titleX = x + (width - metrics.stringWidth(OVERLAY_TITLE)) / 2;
		graphics.setColor(Color.BLACK);
		graphics.drawString(OVERLAY_TITLE, titleX + 1, titleBaselineY + 1);
		graphics.setColor(COLOR_TITLE);
		graphics.drawString(OVERLAY_TITLE, titleX, titleBaselineY);
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
		drawPanelChrome(graphics, x, y, totalWidth, totalHeight, currentY, true);
		FontMetrics metrics = graphics.getFontMetrics();
		
		currentY += COMPACT_LINE_HEIGHT + COMPACT_TITLE_SPACING;
		
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
			
			boolean isBuy = OfferSignal.isBuyState(state);
			
			double percentage = totalQuantity > 0 ? (quantitySold * 100.0) / totalQuantity : 0;
			String itemName = ItemUtils.getItemName(itemManager, itemId);

			// Truncate item name if too long
			if (itemName.length() > MAX_ITEM_NAME_LENGTH)
			{
				itemName = itemName.substring(0, MAX_ITEM_NAME_LENGTH - 1) + "..";
			}
			
			Color statusColor = getStatusColor(state, isBuy);

			AsyncBufferedImage itemImage = itemManager.getImage(itemId);
			if (itemImage != null && itemImage.getWidth() > 0)
			{
				int iconX = x + PADDING + COMPACT_ICON_X_OFFSET;
				int iconY = currentY - COMPACT_ICON_SIZE + COMPACT_ICON_Y_OFFSET;
				graphics.drawImage(itemImage, iconX, iconY, COMPACT_ICON_SIZE, COMPACT_ICON_SIZE, null);
			}

			// Draw item name with B/S indicator
			int textX = x + PADDING + COMPACT_ICON_SIZE;
			String displayText = (isBuy ? "B: " : "S: ") + itemName;
			graphics.setColor(Color.BLACK);
			graphics.drawString(displayText, textX + 1, currentY + 1);
			graphics.setColor(isBuy ? COLOR_BUY : COLOR_SELL);
			graphics.drawString(displayText, textX, currentY);

			// Draw percentage on the right
			String pctText = PERCENTAGE_FORMAT.format(percentage) + "%";
			int pctWidth = metrics.stringWidth(pctText);
			int pctX = x + totalWidth - PADDING - pctWidth;
			graphics.setColor(Color.BLACK);
			graphics.drawString(pctText, pctX + 1, currentY + 1);
			graphics.setColor(statusColor);
			graphics.drawString(pctText, pctX, currentY);

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

	
	
}
