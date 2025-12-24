package com.flipsmart;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.VarClientInt;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.AsyncBufferedImage;

import javax.inject.Inject;
import java.awt.*;
import java.text.DecimalFormat;

/**
 * Flip Assist overlay - a unique step-by-step workflow guide.
 * 
 * Unlike other flipping tools that use widget highlights, this shows a
 * floating panel with:
 * - Horizontal progress indicator showing the complete flip journey
 * - Current step prominently displayed with animated effects
 * - All relevant info (item, price, qty, profit) in one place
 * - Clear hotkey prompts at each step
 * 
 * Design philosophy: Guide the player through the ENTIRE flip journey
 * from initial purchase to final sale, not just one transaction.
 */
@Slf4j
public class FlipAssistOverlay extends Overlay
{
	private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("#,###");
	
	// Unique color theme - warm amber/gold gradient theme (distinct from other plugins)
	private static final Color COLOR_BG_DARK = new Color(25, 22, 18, 245);
	private static final Color COLOR_BG_PANEL = new Color(55, 52, 48, 240);  // Lighter grey for inner sections
	private static final Color COLOR_BORDER = new Color(10, 10, 10);  // Black border
	private static final Color COLOR_ACCENT = new Color(255, 185, 50);  // Warm amber
	private static final Color COLOR_ACCENT_GLOW = new Color(255, 185, 50, 60);
	private static final Color COLOR_TEXT = new Color(245, 240, 230);
	private static final Color COLOR_TEXT_DIM = new Color(160, 150, 140);
	private static final Color COLOR_BUY = new Color(100, 220, 130);
	private static final Color COLOR_SELL = new Color(255, 140, 80);
	private static final Color COLOR_PROFIT = new Color(80, 255, 120);
	private static final Color COLOR_STEP_COMPLETE = new Color(80, 200, 100);
	private static final Color COLOR_STEP_CURRENT = COLOR_ACCENT;
	private static final Color COLOR_STEP_PENDING = new Color(80, 75, 70);
	
	// Layout constants (compact for smaller screens)
	private static final int PANEL_WIDTH = 220;
	private static final int PANEL_HEIGHT = 170;
	private static final int PADDING = 6;
	private static final int SECTION_PADDING = 10;  // Inner padding for sections
	private static final int ICON_SIZE = 28;
	private static final int STEP_INDICATOR_SIZE = 10;
	
	// GE Interface IDs
	private static final int GE_INTERFACE_GROUP = 465;
	private static final int GE_OFFER_GROUP = 162;
	private static final int GE_OFFER_PANEL_CHILD = 26;
	
	// GE Offer Panel child indices (within widget 465:26)
	private static final int GE_QTY_CHILD_START = 31;
	private static final int GE_QTY_CHILD_END = 36;
	private static final int GE_PRICE_CHILD_START = 38;
	private static final int GE_PRICE_CHILD_END = 44;
	
	// Input types from VarClientInt.INPUT_TYPE
	private static final int INPUT_TYPE_NUMERIC = 7;
	private static final int INPUT_TYPE_GE_SEARCH = 14;
	
	// Coins text constant
	private static final String COINS_TEXT = "coins";
	
	private final Client client;
	private final ClientThread clientThread;
	private final FlipSmartConfig config;
	private final ItemManager itemManager;
	
	@Getter
	private FocusedFlip focusedFlip;
	
	// Animation state
	private long animationStartTime = System.currentTimeMillis();
	private static final long PULSE_DURATION = 1500; // ms for one pulse cycle
	
	/**
	 * The current step in the flip assist workflow
	 */
	public enum FlipAssistStep
	{
		SELECT_ITEM("Select Item", "Click BUY on an empty slot"),
		SEARCH_ITEM("Search Item", "Item auto-selected, press Enter"),
		SET_QUANTITY("Set Quantity", "Press [%s] to set qty: %s"),
		SET_PRICE("Set Price", "Press [%s] to set price: %s"),
		CONFIRM_OFFER("Confirm", "Click the confirm button"),
		WAITING_BUY("Buying...", "Offer placed, waiting to fill"),
		COLLECT_ITEMS("Collect", "Items bought! Collect from slot"),
		SELL_ITEMS("Sell Items", "Click SELL on collected items"),
		SET_SELL_PRICE("Set Sell Price", "Press [%s] to set: %s"),
		CONFIRM_SELL("Confirm Sale", "Click confirm to list"),
		WAITING_SELL("Selling...", "Listed! Waiting to sell"),
		COMPLETE("Complete!", "Flip finished - collect GP");
		
		private final String title;
		private final String description;
		
		FlipAssistStep(String title, String description)
		{
			this.title = title;
			this.description = description;
		}
		
		public String getTitle()
		{
			return title;
		}
		
		public String getDescription()
		{
			return description;
		}
	}
	
	@Getter
	private FlipAssistStep currentStep = FlipAssistStep.SELECT_ITEM;
	
	@Inject
	private FlipAssistOverlay(Client client, ClientThread clientThread, FlipSmartConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.itemManager = itemManager;
		
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
		setMovable(true);
	}
	
	/**
	 * Update the current step based on game state
	 */
	public void updateStep()
	{
		if (focusedFlip == null)
		{
			currentStep = FlipAssistStep.SELECT_ITEM;
			return;
		}
		
		// Determine step based on GE state and focus
		if (!isGrandExchangeOpen())
		{
			// GE not open - show appropriate waiting state
			currentStep = focusedFlip.isBuying() ? FlipAssistStep.WAITING_BUY : FlipAssistStep.WAITING_SELL;
			return;
		}
		
		int inputType = getInputType();
		
		// Check if we're in item search mode
		if (inputType == INPUT_TYPE_GE_SEARCH)
		{
			currentStep = FlipAssistStep.SEARCH_ITEM;
			return;
		}
		
		// Check if we're in numeric input mode (price or quantity)
		if (inputType == INPUT_TYPE_NUMERIC)
		{
			if (isLikelyPriceInput())
			{
				currentStep = focusedFlip.isBuying() ? FlipAssistStep.SET_PRICE : FlipAssistStep.SET_SELL_PRICE;
			}
			else
			{
				currentStep = FlipAssistStep.SET_QUANTITY;
			}
			return;
		}
		
		// Check if offer setup screen is open
		if (isOfferSetupOpen())
		{
			// On offer setup, but not in input mode
			// Check BOTH quantity AND price before showing confirm
			int currentQty = getCurrentQuantityFromGE();
			int targetQty = focusedFlip.getCurrentStepQuantity();
			int currentPrice = getCurrentPriceFromGE();
			int targetPrice = focusedFlip.getCurrentStepPrice();
			
			// Check if quantity is correct (within ±1 tolerance)
			boolean qtyCorrect = false;
			if (currentQty > 0 && targetQty > 0)
			{
				qtyCorrect = Math.abs(currentQty - targetQty) <= 1;
			}
			
			// Check if price is correct (must match exactly or within 1gp for rounding)
			boolean priceCorrect = false;
			if (currentPrice > 0 && targetPrice > 0)
			{
				// Strict matching - allow only 1gp tolerance for rounding
				priceCorrect = Math.abs(currentPrice - targetPrice) <= 1;
			}
			
			// Only show confirm if BOTH are correct
			if (qtyCorrect && priceCorrect)
			{
				currentStep = focusedFlip.isBuying() ? FlipAssistStep.CONFIRM_OFFER : FlipAssistStep.CONFIRM_SELL;
				return;
			}
			
			// If quantity is wrong, show set quantity step
			if (!qtyCorrect)
			{
				currentStep = FlipAssistStep.SET_QUANTITY;
				return;
			}
			
			// Quantity is correct but price is wrong - show set price step
			currentStep = focusedFlip.isBuying() ? FlipAssistStep.SET_PRICE : FlipAssistStep.SET_SELL_PRICE;
			return;
		}
		
		// Main GE screen - need to select buy/sell
		currentStep = focusedFlip.isBuying() ? FlipAssistStep.SELECT_ITEM : FlipAssistStep.SELL_ITEMS;
	}
	
	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.enableFlipAssistant() || focusedFlip == null)
		{
			return null;
		}
		
		// Only show when GE is open or when we have an active flip
		if (!isGrandExchangeOpen() && !config.showAssistantAlways())
		{
			return null;
		}
		
		// Update step based on current game state
		updateStep();
		
		// Enable anti-aliasing
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		
		// Calculate animation phase for pulse effect
		long elapsed = System.currentTimeMillis() - animationStartTime;
		double pulsePhase = (elapsed % PULSE_DURATION) / (double) PULSE_DURATION;
		float pulseAlpha = (float) (0.5 + 0.5 * Math.sin(pulsePhase * 2 * Math.PI));
		
		int panelHeight = PANEL_HEIGHT;
		
		// Draw outer glow (animated)
		Color glowColor = new Color(
			COLOR_ACCENT_GLOW.getRed(),
			COLOR_ACCENT_GLOW.getGreen(),
			COLOR_ACCENT_GLOW.getBlue(),
			(int)(COLOR_ACCENT_GLOW.getAlpha() * pulseAlpha)
		);
		graphics.setColor(glowColor);
		graphics.fillRoundRect(-4, -4, PANEL_WIDTH + 8, panelHeight + 8, 16, 16);
		
		// Draw background
		graphics.setColor(COLOR_BG_DARK);
		graphics.fillRoundRect(0, 0, PANEL_WIDTH, panelHeight, 12, 12);
		
		// Draw thin black border
		graphics.setColor(COLOR_BORDER);
		graphics.setStroke(new BasicStroke(1));
		graphics.drawRoundRect(0, 0, PANEL_WIDTH, panelHeight, 12, 12);
		
		int y = PADDING;
		
		// Draw header section
		y = renderHeader(graphics, y);
		
		// Draw step progress indicator
		y = renderStepProgress(graphics, y, pulseAlpha);
		
		// Draw current action panel
		y = renderCurrentAction(graphics, y, pulseAlpha);
		
		// Draw flip summary
		renderFlipSummary(graphics, y);
		
		return new Dimension(PANEL_WIDTH, panelHeight);
	}
	
	/**
	 * Render the header with item icon and name
	 */
	private int renderHeader(Graphics2D graphics, int y)
	{
		// Add top padding so header isn't squished
		y += 4;
		
		// Item icon
		AsyncBufferedImage itemImage = itemManager.getImage(focusedFlip.getItemId());
		if (itemImage != null)
		{
			graphics.drawImage(itemImage, SECTION_PADDING, y, ICON_SIZE, ICON_SIZE, null);
		}
		
		// Item name
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(COLOR_TEXT);
		String itemName = truncateString(focusedFlip.getItemName(), PANEL_WIDTH - ICON_SIZE - SECTION_PADDING * 3, graphics.getFontMetrics());
		graphics.drawString(itemName, SECTION_PADDING + ICON_SIZE + 6, y + 10);
		
		// Action label (BUY or SELL) - more space from item name
		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.setColor(focusedFlip.isBuying() ? COLOR_BUY : COLOR_SELL);
		String actionLabel = focusedFlip.isBuying() ? "BUYING" : "SELLING";
		graphics.drawString(actionLabel, SECTION_PADDING + ICON_SIZE + 6, y + 24);
		
		return y + ICON_SIZE + 4;
	}
	
	/**
	 * Render the horizontal step progress indicator
	 */
	private int renderStepProgress(Graphics2D graphics, int y, float pulseAlpha)
	{
		// No background - cleaner look
		y += 4;
		int startX = 20;
		
		// Determine which steps to show based on buy/sell phase
		FlipAssistStep[] stepsToShow;
		if (focusedFlip.isBuying())
		{
			stepsToShow = new FlipAssistStep[]{
				FlipAssistStep.SELECT_ITEM,
				FlipAssistStep.SET_QUANTITY,
				FlipAssistStep.SET_PRICE,
				FlipAssistStep.CONFIRM_OFFER
			};
		}
		else
		{
			// Sell flow also has quantity step
			stepsToShow = new FlipAssistStep[]{
				FlipAssistStep.SELL_ITEMS,
				FlipAssistStep.SET_QUANTITY,
				FlipAssistStep.SET_SELL_PRICE,
				FlipAssistStep.CONFIRM_SELL
			};
		}
		
		int currentStepIndex = getStepIndex(currentStep, stepsToShow);
		
		// Draw horizontal step indicators
		int stepX = startX;
		int stepWidth = (PANEL_WIDTH - 40) / (stepsToShow.length - 1);
		
		for (int i = 0; i < stepsToShow.length; i++)
		{
			// Draw connecting line (except for first) - shorter lines with gaps
			if (i > 0)
			{
				graphics.setColor(i <= currentStepIndex ? COLOR_STEP_COMPLETE : COLOR_STEP_PENDING);
				graphics.setStroke(new BasicStroke(2));
				int lineGap = 6;  // Gap from each circle
				graphics.drawLine(stepX - stepWidth + STEP_INDICATOR_SIZE / 2 + lineGap, y + STEP_INDICATOR_SIZE / 2,
					stepX - STEP_INDICATOR_SIZE / 2 - lineGap, y + STEP_INDICATOR_SIZE / 2);
			}
			
			// Draw step circle
			Color stepColor;
			if (i < currentStepIndex)
			{
				stepColor = COLOR_STEP_COMPLETE;
			}
			else if (i == currentStepIndex)
			{
				// Animate current step
				stepColor = new Color(
					COLOR_STEP_CURRENT.getRed(),
					COLOR_STEP_CURRENT.getGreen(),
					COLOR_STEP_CURRENT.getBlue(),
					(int)(180 + 75 * pulseAlpha)
				);
			}
			else
			{
				stepColor = COLOR_STEP_PENDING;
			}
			
			graphics.setColor(stepColor);
			graphics.fillOval(stepX - STEP_INDICATOR_SIZE / 2, y, STEP_INDICATOR_SIZE, STEP_INDICATOR_SIZE);
			
			// Draw checkmark for completed steps
			if (i < currentStepIndex)
			{
				graphics.setColor(COLOR_BG_DARK);
				graphics.setFont(new Font("Arial", Font.BOLD, 7));
				graphics.drawString("✓", stepX - 2, y + 8);
			}
			
			// Draw step number for current/future steps
			if (i >= currentStepIndex && i == currentStepIndex)
			{
				graphics.setColor(COLOR_BG_DARK);
				graphics.setFont(new Font("Arial", Font.BOLD, 8));
				graphics.drawString(String.valueOf(i + 1), stepX - 2, y + 8);
			}
			
			// Draw step label below
			graphics.setFont(FontManager.getRunescapeSmallFont());
			graphics.setColor(i == currentStepIndex ? COLOR_TEXT : COLOR_TEXT_DIM);
			String shortLabel = getShortStepLabel(stepsToShow[i]);
			int labelWidth = graphics.getFontMetrics().stringWidth(shortLabel);
			graphics.drawString(shortLabel, stepX - labelWidth / 2, y + STEP_INDICATOR_SIZE + 12);
			
			stepX += stepWidth;
		}
		
		return y + 34;
	}
	
	/**
	 * Render the current action panel with instructions
	 */
	private int renderCurrentAction(Graphics2D graphics, int y, float pulseAlpha)
	{
		// Draw action box with pulsing border
		int boxHeight = 42;
		int boxMargin = 10;
		int boxWidth = PANEL_WIDTH - boxMargin * 2;
		
		graphics.setColor(new Color(45, 42, 38));
		graphics.fillRoundRect(boxMargin, y, boxWidth, boxHeight, 6, 6);
		
		// Pulsing border for emphasis
		Color borderColor = new Color(
			COLOR_ACCENT.getRed(),
			COLOR_ACCENT.getGreen(),
			COLOR_ACCENT.getBlue(),
			(int)(150 + 100 * pulseAlpha)
		);
		graphics.setColor(borderColor);
		graphics.setStroke(new BasicStroke(1.5f));
		graphics.drawRoundRect(boxMargin, y, boxWidth, boxHeight, 6, 6);
		
		// Action title - centered horizontally and vertically positioned
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(COLOR_ACCENT);
		String title = currentStep.getTitle();
		int titleWidth = graphics.getFontMetrics().stringWidth(title);
		graphics.drawString(title, (PANEL_WIDTH - titleWidth) / 2, y + 18);
		
		// Action description - centered horizontally and vertically positioned
		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.setColor(COLOR_TEXT);
		String description = formatStepDescription();
		int descWidth = graphics.getFontMetrics().stringWidth(description);
		graphics.drawString(description, (PANEL_WIDTH - descWidth) / 2, y + 34);
		
		return y + boxHeight + 4;
	}
	
	/**
	 * Render the flip summary with prices and profit
	 */
	private void renderFlipSummary(Graphics2D graphics, int y)
	{
		graphics.setFont(FontManager.getRunescapeSmallFont());
		int lineHeight = 12;
		
		// Price info
		String priceLabel = focusedFlip.isBuying() ? "Buy at:" : "Sell at:";
		graphics.setColor(COLOR_TEXT_DIM);
		graphics.drawString(priceLabel, SECTION_PADDING, y + lineHeight);
		graphics.setColor(focusedFlip.isBuying() ? COLOR_BUY : COLOR_SELL);
		String priceText = PRICE_FORMAT.format(focusedFlip.getCurrentStepPrice()) + " gp";
		graphics.drawString(priceText, SECTION_PADDING + 42, y + lineHeight);
		
		// Quantity info
		graphics.setColor(COLOR_TEXT_DIM);
		graphics.drawString("Qty:", SECTION_PADDING, y + lineHeight * 2);
		graphics.setColor(COLOR_TEXT);
		graphics.drawString(PRICE_FORMAT.format(focusedFlip.getCurrentStepQuantity()), SECTION_PADDING + 42, y + lineHeight * 2);
		
		// Potential profit (only for buy phase)
		if (focusedFlip.isBuying() && focusedFlip.getSellPrice() > 0)
		{
			int margin = focusedFlip.getSellPrice() - focusedFlip.getBuyPrice();
			int geTax = Math.min((int)(focusedFlip.getSellPrice() * 0.02), 5_000_000);
			int profitPerItem = margin - geTax;
			int totalProfit = profitPerItem * focusedFlip.getBuyQuantity();
			
			graphics.setColor(COLOR_TEXT_DIM);
			graphics.drawString("Profit:", SECTION_PADDING, y + lineHeight * 3);
			graphics.setColor(totalProfit > 0 ? COLOR_PROFIT : new Color(255, 100, 100));
			graphics.drawString(PRICE_FORMAT.format(totalProfit) + " gp", SECTION_PADDING + 42, y + lineHeight * 3);
		}
	}
	
	/**
	 * Format the step description with dynamic values
	 */
	private String formatStepDescription()
	{
		String hotkeyName = config.flipAssistHotkey().toString();
		
		switch (currentStep)
		{
			case SET_QUANTITY:
				int targetQty = focusedFlip.getCurrentStepQuantity();
				// Just show the target quantity with hotkey
				return String.format(currentStep.getDescription(), hotkeyName, 
					PRICE_FORMAT.format(targetQty));
			case SET_PRICE:
			case SET_SELL_PRICE:
				int targetPrice = focusedFlip.getCurrentStepPrice();
				// Just show the target price with hotkey
				return String.format(currentStep.getDescription(), hotkeyName,
					PRICE_FORMAT.format(targetPrice));
			default:
				return currentStep.getDescription();
		}
	}
	
	/**
	 * Get short label for step indicator
	 */
	private String getShortStepLabel(FlipAssistStep step)
	{
		switch (step)
		{
			case SELECT_ITEM:
			case SELL_ITEMS:
				return "Select";
			case SET_QUANTITY:
				return "Qty";
			case SET_PRICE:
			case SET_SELL_PRICE:
				return "Price";
			case CONFIRM_OFFER:
			case CONFIRM_SELL:
				return "Confirm";
			case COMPLETE:
				return "Done";
			default:
				return step.getTitle().substring(0, Math.min(5, step.getTitle().length()));
		}
	}
	
	/**
	 * Get the index of a step in the given array
	 */
	private int getStepIndex(FlipAssistStep step, FlipAssistStep[] steps)
	{
		for (int i = 0; i < steps.length; i++)
		{
			if (steps[i] == step || isEquivalentStep(steps[i], step))
			{
				return i;
			}
		}
		return 0;
	}
	
	/**
	 * Check if two steps are equivalent (same position in flow)
	 */
	private boolean isEquivalentStep(FlipAssistStep a, FlipAssistStep b)
	{
		// Handle search step as part of select
		if ((a == FlipAssistStep.SELECT_ITEM || a == FlipAssistStep.SELL_ITEMS) && b == FlipAssistStep.SEARCH_ITEM)
		{
			return true;
		}
		// SET_QUANTITY is the same step for both buy and sell flows
		return a == FlipAssistStep.SET_QUANTITY && b == FlipAssistStep.SET_QUANTITY;
	}
	
	/**
	 * Check if GE interface is open
	 */
	private boolean isGrandExchangeOpen()
	{
		Widget geWidget = client.getWidget(GE_INTERFACE_GROUP, 0);
		Widget offerWidget = client.getWidget(GE_OFFER_GROUP, 0);
		return (geWidget != null && !geWidget.isHidden()) ||
			   (offerWidget != null && !offerWidget.isHidden());
	}
	
	/**
	 * Check if offer setup screen is open.
	 * This is when the player has clicked a Buy/Sell slot and can set quantity/price.
	 * We detect this by checking for the offer panel (465:26) with BOTH "Quantity:" 
	 * AND a price value containing "coins".
	 */
	private boolean isOfferSetupOpen()
	{
		// Check for the offer panel widget which contains quantity/price setup
		Widget offerPanel = client.getWidget(GE_INTERFACE_GROUP, GE_OFFER_PANEL_CHILD);
		if (offerPanel == null || offerPanel.isHidden())
		{
			return false;
		}
		
		Widget[] children = offerPanel.getDynamicChildren();
		if (children == null || children.length < 30)
		{
			return false;
		}
		
		// Must find BOTH indicators to confirm we're in offer setup
		boolean hasQuantityLabel = false;
		boolean hasPriceCoins = false;
		
		for (Widget child : children)
		{
			if (child != null && !child.isHidden())
			{
				String text = child.getText();
				if (text != null)
				{
					if (text.contains("Quantity:"))
					{
						hasQuantityLabel = true;
					}
					// Price shown as "X coins" only appears in offer setup
					if (text.contains(COINS_TEXT) && text.matches(".*\\d+.*"))
					{
						hasPriceCoins = true;
					}
				}
			}
		}
		
		// Only return true if we find both indicators
		return hasQuantityLabel && hasPriceCoins;
	}
	
	/**
	 * Get current input type from VarClientInt
	 */
	@SuppressWarnings("deprecation")
	private int getInputType()
	{
		return client.getVarcIntValue(VarClientInt.INPUT_TYPE);
	}
	
	// Chatbox widget groups to check for price/quantity dialog text
	private static final int[] CHATBOX_WIDGET_GROUPS = {162, 163, 164, 217, 219, 229, 548, 161};
	
	/**
	 * Determine if we're in price input (vs quantity)
	 * Checks the chatbox dialog text for keywords like "price" or "how many"
	 */
	private boolean isLikelyPriceInput()
	{
		// First check the chatbox dialog text for keywords
		for (int groupId : CHATBOX_WIDGET_GROUPS)
		{
			for (int childId = 0; childId <= 50; childId++)
			{
				Widget widget = client.getWidget(groupId, childId);
				if (widget != null && !widget.isHidden())
				{
					String text = widget.getText();
					if (text != null && !text.isEmpty())
					{
						String lowerText = text.toLowerCase();
						// "Set a price for each item" = price input
						if (lowerText.contains("price") && !lowerText.contains("price:"))
						{
							return true;
						}
						// "How many do you wish to" = quantity input
						if (lowerText.contains("how many"))
						{
							return false;
						}
					}
				}
			}
		}
		
		// Fallback: check GE quantity widget - if qty > 1, probably setting price
		int currentQty = getCurrentQuantityFromGE();
		return currentQty > 1;
	}
	
	/**
	 * Get the current quantity value from the GE offer setup widget.
	 * Returns 0 if not available.
	 * 
	 * GE Offer Setup structure (Widget 465:26):
	 * - Child 30: "Quantity:" label
	 * - Child 34: Quantity value (e.g., "7,000")
	 * - Child 37: "Price per item:" label
	 * - Child 41: Price value (e.g., "17 coins")
	 */
	private int getCurrentQuantityFromGE()
	{
		Widget offerPanel = client.getWidget(GE_INTERFACE_GROUP, GE_OFFER_PANEL_CHILD);
		if (offerPanel == null || offerPanel.isHidden())
		{
			return 0;
		}
		
		Widget[] children = offerPanel.getDynamicChildren();
		if (children == null)
		{
			return 0;
		}
		
		// Look for quantity value - it's the numeric child after "Quantity:" label
		for (int i = GE_QTY_CHILD_START; i <= GE_QTY_CHILD_END; i++)
		{
			if (i >= children.length)
			{
				break;
			}
			Widget child = children[i];
			if (child != null && !child.isHidden())
			{
				String text = child.getText();
				if (text != null && !text.isEmpty() 
					&& !text.toLowerCase().contains(COINS_TEXT)
					&& !text.toLowerCase().contains("quantity")
					&& !text.toLowerCase().contains("price"))
				{
					int value = parseNumericText(text);
					if (value >= 1)
					{
						return value;
					}
				}
			}
		}
		
		return 0;
	}
	
	/**
	 * Get the current price value from the GE offer setup widget.
	 * Returns 0 if not available.
	 * 
	 * GE Offer Setup structure (Widget 465:26):
	 * - Child 37: "Price per item:" label
	 * - Child 41: Price value (e.g., "17 coins")
	 */
	private int getCurrentPriceFromGE()
	{
		Widget offerPanel = client.getWidget(GE_INTERFACE_GROUP, GE_OFFER_PANEL_CHILD);
		if (offerPanel == null || offerPanel.isHidden())
		{
			return getCurrentPriceFromGEFallback();
		}
		
		Widget[] children = offerPanel.getDynamicChildren();
		if (children == null)
		{
			return getCurrentPriceFromGEFallback();
		}
		
		// Look for price value - it contains "coins"
		for (int i = GE_PRICE_CHILD_START; i <= GE_PRICE_CHILD_END; i++)
		{
			if (i >= children.length)
			{
				break;
			}
			Widget child = children[i];
			if (child != null && !child.isHidden())
			{
				String text = child.getText();
				if (text != null && text.toLowerCase().contains(COINS_TEXT))
				{
					int value = parseNumericText(text);
					if (value > 0)
					{
						return value;
					}
				}
			}
		}
		
		return getCurrentPriceFromGEFallback();
	}
	
	/**
	 * Fallback method for price detection using older widget locations.
	 */
	private int getCurrentPriceFromGEFallback()
	{
		int[] priceWidgetIds = {25, 27};
		
		for (int childId : priceWidgetIds)
		{
			Widget widget = client.getWidget(GE_INTERFACE_GROUP, childId);
			if (widget == null || widget.isHidden())
			{
				continue;
			}
			
			// Check direct text for "coins"
			String text = widget.getText();
			if (text != null && text.toLowerCase().contains(COINS_TEXT))
			{
				int value = parseNumericText(text);
				if (value > 0)
				{
					return value;
				}
			}
			
			// Check children for "coins" text
			Widget[] children = widget.getDynamicChildren();
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child != null && !child.isHidden())
					{
						String childText = child.getText();
						if (childText != null && childText.toLowerCase().contains(COINS_TEXT))
						{
							int value = parseNumericText(childText);
							if (value > 0)
							{
								return value;
							}
						}
					}
				}
			}
		}
		return 0;
	}
	
	/**
	 * Parse numeric value from text, handling commas and other formatting.
	 * Returns 0 if parsing fails or text is empty/null.
	 */
	private int parseNumericText(String text)
	{
		if (text == null || text.isEmpty())
		{
			return 0;
		}
		
		try
		{
			// Remove all non-digit characters (commas, spaces, "coins", etc.)
			String numericOnly = text.replaceAll("\\D", "");
			if (!numericOnly.isEmpty())
			{
				return Integer.parseInt(numericOnly);
			}
		}
		catch (NumberFormatException e)
		{
			// Ignore
		}
		
		return 0;
	}
	
	/**
	 * Truncate string to fit width
	 */
	private String truncateString(String str, int maxWidth, FontMetrics fm)
	{
		if (fm.stringWidth(str) <= maxWidth)
		{
			return str;
		}
		
		String ellipsis = "...";
		int len = str.length();
		while (len > 0 && fm.stringWidth(str.substring(0, len)) + fm.stringWidth(ellipsis) > maxWidth)
		{
			len--;
		}
		return str.substring(0, len) + ellipsis;
	}
	
	/**
	 * Set the focused flip and pre-fill the GE "last searched" item.
	 * When user clicks Buy/Sell on an empty GE slot, the search will auto-populate with this item.
	 */
	public void setFocusedFlip(FocusedFlip focusedFlip)
	{
		this.focusedFlip = focusedFlip;
		
		if (focusedFlip != null)
		{
			// Pre-fill the GE "last searched" item when focusing on a flip
			setGELastSearchedItem(focusedFlip.getItemId());
		}
	}
	
	/**
	 * Set the GE "last searched" item ID.
	 * This makes the item auto-populate in the GE search box when user clicks Buy/Sell.
	 * 
	 * This is compliant with Jagex rules because:
	 * 1. It only sets a preference value (like remembering your last search)
	 * 2. User must still manually click Buy/Sell to open the search
	 * 3. User must still manually select the item and confirm the offer
	 * 4. No automation of actual trading actions
	 * 
	 * @param itemId The item ID to set as last searched
	 */
	private void setGELastSearchedItem(int itemId)
	{
		clientThread.invokeLater(() -> {
			try
			{
				int[] varps = client.getVarps();
				varps[VarPlayerID.GE_LAST_SEARCHED] = itemId;
				client.queueChangedVarp(VarPlayerID.GE_LAST_SEARCHED);
				// Successfully set last searched item
			}
			catch (Exception e)
			{
				log.warn("Failed to set GE last searched item: {}", e.getMessage());
			}
		});
	}
	
	/**
	 * Clear the focused flip
	 */
	public void clearFocus()
	{
		this.focusedFlip = null;
		this.currentStep = FlipAssistStep.SELECT_ITEM;
	}
}

