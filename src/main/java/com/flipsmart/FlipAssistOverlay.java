package com.flipsmart;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
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
	
	// Color theme
	private static final Color COLOR_BG_DARK = new Color(25, 22, 18, 200);
	private static final Color COLOR_BORDER = new Color(10, 10, 10);
	private static final Color COLOR_ACCENT = new Color(255, 185, 50);
	private static final Color COLOR_ACCENT_GLOW = new Color(255, 185, 50, 60);
	private static final Color COLOR_TEXT = new Color(245, 240, 230);
	private static final Color COLOR_TEXT_DIM = new Color(160, 150, 140);
	private static final Color COLOR_BUY = new Color(100, 220, 130);
	private static final Color COLOR_SELL = new Color(255, 140, 80);
	private static final Color COLOR_PROFIT = new Color(80, 255, 120);
	private static final Color COLOR_STEP_COMPLETE = new Color(80, 200, 100);
	private static final Color COLOR_STEP_CURRENT = COLOR_ACCENT;
	private static final Color COLOR_STEP_PENDING = new Color(80, 75, 70);
	private static final Color COLOR_ACTION_BOX = new Color(45, 42, 38);
	
	// Layout constants
	private static final int PANEL_WIDTH = 220;
	private static final int PANEL_HEIGHT = 170;
	private static final int HINT_PANEL_WIDTH = 200;
	private static final int HINT_PANEL_HEIGHT = 50;
	private static final int PADDING = 6;
	private static final int SECTION_PADDING = 10;
	private static final int ICON_SIZE = 28;
	private static final int STEP_INDICATOR_SIZE = 10;
	
	// Hint message
	private static final String HINT_TITLE = "Flip Assist";
	private static final String HINT_MESSAGE = "Click on a flip suggestion to start";
	
	// GE Interface IDs
	private static final int GE_INTERFACE_GROUP = 465;
	private static final int GE_OFFER_PANEL_CHILD = 26;
	private static final int GE_QTY_CHILD_START = 31;
	private static final int GE_QTY_CHILD_END = 36;
	private static final int GE_PRICE_CHILD_START = 38;
	private static final int GE_PRICE_CHILD_END = 44;
	// VarClientInt.INPUT_TYPE value (raw ID to avoid deprecated API)
	private static final int VARCLIENT_INPUT_TYPE = 5;
	private static final int INPUT_TYPE_NUMERIC = 7;
	private static final int INPUT_TYPE_GE_SEARCH = 14;
	private static final String COINS_TEXT = "coins";
	private static final int[] CHATBOX_WIDGET_GROUPS = {162, 163, 164, 217, 219, 229, 548, 161};
	
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
		setPriority(PRIORITY_HIGH);
		setMovable(true);
	}
	
	public void updateStep()
	{
		if (focusedFlip == null)
		{
			currentStep = FlipAssistStep.SELECT_ITEM;
			return;
		}
		
		if (!isGrandExchangeOpen())
		{
			currentStep = focusedFlip.isBuying() ? FlipAssistStep.WAITING_BUY : FlipAssistStep.WAITING_SELL;
			return;
		}
		
		int inputType = getInputType();
		if (inputType == INPUT_TYPE_GE_SEARCH)
		{
			currentStep = FlipAssistStep.SEARCH_ITEM;
			return;
		}
		
		if (inputType == INPUT_TYPE_NUMERIC)
		{
			currentStep = determineNumericInputStep();
			return;
		}
		
		if (isOfferSetupOpen())
		{
			currentStep = determineOfferSetupStep();
			return;
		}
		
		currentStep = focusedFlip.isBuying() ? FlipAssistStep.SELECT_ITEM : FlipAssistStep.SELL_ITEMS;
	}
	
	private FlipAssistStep determineNumericInputStep()
	{
		if (isLikelyPriceInput())
		{
			return focusedFlip.isBuying() ? FlipAssistStep.SET_PRICE : FlipAssistStep.SET_SELL_PRICE;
		}
		return FlipAssistStep.SET_QUANTITY;
	}
	
	private FlipAssistStep determineOfferSetupStep()
	{
		boolean qtyCorrect = isValueWithinTolerance(getCurrentQuantityFromGE(), focusedFlip.getCurrentStepQuantity());
		boolean priceCorrect = isValueWithinTolerance(getCurrentPriceFromGE(), focusedFlip.getCurrentStepPrice());
		
		if (qtyCorrect && priceCorrect)
		{
			return focusedFlip.isBuying() ? FlipAssistStep.CONFIRM_OFFER : FlipAssistStep.CONFIRM_SELL;
		}
		if (!qtyCorrect)
		{
			return FlipAssistStep.SET_QUANTITY;
		}
		return focusedFlip.isBuying() ? FlipAssistStep.SET_PRICE : FlipAssistStep.SET_SELL_PRICE;
	}
	
	/**
	 * Check if a GE value matches the target within ±1 tolerance.
	 * Tolerance is needed because GE can have slight rounding differences
	 * when displaying prices/quantities (e.g., 1gp variance in price).
	 */
	private boolean isValueWithinTolerance(int current, int target)
	{
		return current > 0 && target > 0 && Math.abs(current - target) <= 1;
	}
	
	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.enableFlipAssistant())
		{
			return null;
		}
		
		// If no flip is focused, show hint box when GE is open
		if (focusedFlip == null)
		{
			if (isGrandExchangeOpen())
			{
				return renderHintBox(graphics);
			}
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
	 * Render a small hint box prompting user to click on a flip suggestion.
	 */
	private Dimension renderHintBox(Graphics2D graphics)
	{
		// Enable anti-aliasing
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		
		// Calculate pulse animation
		long elapsed = System.currentTimeMillis() - animationStartTime;
		double pulsePhase = (elapsed % PULSE_DURATION) / (double) PULSE_DURATION;
		float pulseAlpha = (float) (0.5 + 0.5 * Math.sin(pulsePhase * 2 * Math.PI));
		
		// Draw subtle outer glow (animated)
		Color glowColor = new Color(
			COLOR_ACCENT_GLOW.getRed(),
			COLOR_ACCENT_GLOW.getGreen(),
			COLOR_ACCENT_GLOW.getBlue(),
			(int)(COLOR_ACCENT_GLOW.getAlpha() * pulseAlpha * 0.5)
		);
		graphics.setColor(glowColor);
		graphics.fillRoundRect(-3, -3, HINT_PANEL_WIDTH + 6, HINT_PANEL_HEIGHT + 6, 10, 10);
		
		// Draw background
		graphics.setColor(COLOR_BG_DARK);
		graphics.fillRoundRect(0, 0, HINT_PANEL_WIDTH, HINT_PANEL_HEIGHT, 8, 8);
		
		// Draw accent border (animated)
		Color borderColor = new Color(
			COLOR_ACCENT.getRed(),
			COLOR_ACCENT.getGreen(),
			COLOR_ACCENT.getBlue(),
			(int)(150 + 100 * pulseAlpha)
		);
		graphics.setColor(borderColor);
		graphics.setStroke(new BasicStroke(1.5f));
		graphics.drawRoundRect(0, 0, HINT_PANEL_WIDTH, HINT_PANEL_HEIGHT, 8, 8);
		
		// Draw title
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(COLOR_ACCENT);
		FontMetrics boldMetrics = graphics.getFontMetrics();
		int titleWidth = boldMetrics.stringWidth(HINT_TITLE);
		graphics.drawString(HINT_TITLE, (HINT_PANEL_WIDTH - titleWidth) / 2, 20);
		
		// Draw hint message
		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.setColor(COLOR_TEXT);
		FontMetrics smallMetrics = graphics.getFontMetrics();
		int msgWidth = smallMetrics.stringWidth(HINT_MESSAGE);
		graphics.drawString(HINT_MESSAGE, (HINT_PANEL_WIDTH - msgWidth) / 2, 38);
		
		return new Dimension(HINT_PANEL_WIDTH, HINT_PANEL_HEIGHT);
	}
	
	private int renderHeader(Graphics2D graphics, int y)
	{
		y += 4;
		
		AsyncBufferedImage itemImage = itemManager.getImage(focusedFlip.getItemId());
		if (itemImage != null)
		{
			graphics.drawImage(itemImage, SECTION_PADDING, y, ICON_SIZE, ICON_SIZE, null);
		}
		
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(COLOR_TEXT);
		String itemName = truncateString(focusedFlip.getItemName(), PANEL_WIDTH - ICON_SIZE - SECTION_PADDING * 3, graphics.getFontMetrics());
		graphics.drawString(itemName, SECTION_PADDING + ICON_SIZE + 6, y + 10);
		
		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.setColor(focusedFlip.isBuying() ? COLOR_BUY : COLOR_SELL);
		graphics.drawString(focusedFlip.isBuying() ? "BUYING" : "SELLING", SECTION_PADDING + ICON_SIZE + 6, y + 24);
		
		return y + ICON_SIZE + 4;
	}
	
	private int renderStepProgress(Graphics2D graphics, int y, float pulseAlpha)
	{
		y += 4;
		FlipAssistStep[] stepsToShow = getStepsForCurrentPhase();
		int currentStepIndex = getStepIndex(currentStep, stepsToShow);
		int stepX = 20;
		int stepWidth = (PANEL_WIDTH - 40) / (stepsToShow.length - 1);
		
		for (int i = 0; i < stepsToShow.length; i++)
		{
			if (i > 0)
			{
				drawStepConnector(graphics, stepX, y, stepWidth, i <= currentStepIndex);
			}
			drawStepCircle(graphics, stepX, y, i, currentStepIndex, pulseAlpha);
			drawStepLabel(graphics, stepX, y, stepsToShow[i], i == currentStepIndex);
			stepX += stepWidth;
		}
		return y + 34;
	}
	
	private FlipAssistStep[] getStepsForCurrentPhase()
	{
		if (focusedFlip.isBuying())
		{
			return new FlipAssistStep[]{
				FlipAssistStep.SELECT_ITEM, FlipAssistStep.SET_QUANTITY,
				FlipAssistStep.SET_PRICE, FlipAssistStep.CONFIRM_OFFER
			};
		}
		return new FlipAssistStep[]{
			FlipAssistStep.SELL_ITEMS, FlipAssistStep.SET_QUANTITY,
			FlipAssistStep.SET_SELL_PRICE, FlipAssistStep.CONFIRM_SELL
		};
	}
	
	private void drawStepConnector(Graphics2D graphics, int stepX, int y, int stepWidth, boolean completed)
	{
		graphics.setColor(completed ? COLOR_STEP_COMPLETE : COLOR_STEP_PENDING);
		graphics.setStroke(new BasicStroke(2));
		int lineGap = 6;
		int yCenter = y + STEP_INDICATOR_SIZE / 2;
		graphics.drawLine(stepX - stepWidth + STEP_INDICATOR_SIZE / 2 + lineGap, yCenter,
			stepX - STEP_INDICATOR_SIZE / 2 - lineGap, yCenter);
	}
	
	private void drawStepCircle(Graphics2D graphics, int stepX, int y, int index, int currentIndex, float pulseAlpha)
	{
		Color stepColor = getStepColor(index, currentIndex, pulseAlpha);
		graphics.setColor(stepColor);
		graphics.fillOval(stepX - STEP_INDICATOR_SIZE / 2, y, STEP_INDICATOR_SIZE, STEP_INDICATOR_SIZE);
		
		graphics.setColor(COLOR_BG_DARK);
		if (index < currentIndex)
		{
			graphics.setFont(new Font("Arial", Font.BOLD, 7));
			graphics.drawString("✓", stepX - 2, y + 8);
		}
		else if (index == currentIndex)
		{
			graphics.setFont(new Font("Arial", Font.BOLD, 8));
			graphics.drawString(String.valueOf(index + 1), stepX - 2, y + 8);
		}
	}
	
	private Color getStepColor(int index, int currentIndex, float pulseAlpha)
	{
		if (index < currentIndex)
		{
			return COLOR_STEP_COMPLETE;
		}
		if (index == currentIndex)
		{
			return new Color(COLOR_STEP_CURRENT.getRed(), COLOR_STEP_CURRENT.getGreen(),
				COLOR_STEP_CURRENT.getBlue(), (int)(180 + 75 * pulseAlpha));
		}
		return COLOR_STEP_PENDING;
	}
	
	private void drawStepLabel(Graphics2D graphics, int stepX, int y, FlipAssistStep step, boolean isCurrent)
	{
		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.setColor(isCurrent ? COLOR_TEXT : COLOR_TEXT_DIM);
		String label = getShortStepLabel(step);
		int labelWidth = graphics.getFontMetrics().stringWidth(label);
		graphics.drawString(label, stepX - labelWidth / 2, y + STEP_INDICATOR_SIZE + 12);
	}
	
	private int renderCurrentAction(Graphics2D graphics, int y, float pulseAlpha)
	{
		int boxHeight = 42;
		int boxMargin = 10;
		int boxWidth = PANEL_WIDTH - boxMargin * 2;
		
		graphics.setColor(COLOR_ACTION_BOX);
		graphics.fillRoundRect(boxMargin, y, boxWidth, boxHeight, 6, 6);
		
		Color borderColor = new Color(COLOR_ACCENT.getRed(), COLOR_ACCENT.getGreen(),
			COLOR_ACCENT.getBlue(), (int)(150 + 100 * pulseAlpha));
		graphics.setColor(borderColor);
		graphics.setStroke(new BasicStroke(1.5f));
		graphics.drawRoundRect(boxMargin, y, boxWidth, boxHeight, 6, 6);
		
		drawCenteredText(graphics, currentStep.getTitle(), y + 18, FontManager.getRunescapeBoldFont(), COLOR_ACCENT);
		drawCenteredText(graphics, formatStepDescription(), y + 34, FontManager.getRunescapeSmallFont(), COLOR_TEXT);
		
		return y + boxHeight + 4;
	}
	
	private void drawCenteredText(Graphics2D graphics, String text, int y, Font font, Color color)
	{
		graphics.setFont(font);
		graphics.setColor(color);
		int width = graphics.getFontMetrics().stringWidth(text);
		graphics.drawString(text, (PANEL_WIDTH - width) / 2, y);
	}
	
	private void renderFlipSummary(Graphics2D graphics, int y)
	{
		graphics.setFont(FontManager.getRunescapeSmallFont());
		int lineHeight = 12;
		
		String priceLabel = focusedFlip.isBuying() ? "Buy at:" : "Sell at:";
		drawLabelValue(graphics, priceLabel, PRICE_FORMAT.format(focusedFlip.getCurrentStepPrice()) + " gp",
			y + lineHeight, focusedFlip.isBuying() ? COLOR_BUY : COLOR_SELL);
		
		drawLabelValue(graphics, "Qty:", PRICE_FORMAT.format(focusedFlip.getCurrentStepQuantity()),
			y + lineHeight * 2, COLOR_TEXT);
		
		if (focusedFlip.isBuying() && focusedFlip.getSellPrice() > 0)
		{
			int totalProfit = calculateTotalProfit();
			drawLabelValue(graphics, "Profit:", PRICE_FORMAT.format(totalProfit) + " gp",
				y + lineHeight * 3, totalProfit > 0 ? COLOR_PROFIT : new Color(255, 100, 100));
		}
	}
	
	private void drawLabelValue(Graphics2D graphics, String label, String value, int y, Color valueColor)
	{
		graphics.setColor(COLOR_TEXT_DIM);
		graphics.drawString(label, SECTION_PADDING, y);
		graphics.setColor(valueColor);
		graphics.drawString(value, SECTION_PADDING + 42, y);
	}
	
	private int calculateTotalProfit()
	{
		int margin = focusedFlip.getSellPrice() - focusedFlip.getBuyPrice();
		int geTax = Math.min((int)(focusedFlip.getSellPrice() * 0.02), 5_000_000);
		return (margin - geTax) * focusedFlip.getBuyQuantity();
	}
	
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
	
	private boolean isEquivalentStep(FlipAssistStep a, FlipAssistStep b)
	{
		if ((a == FlipAssistStep.SELECT_ITEM || a == FlipAssistStep.SELL_ITEMS) && b == FlipAssistStep.SEARCH_ITEM)
		{
			return true;
		}
		return a == FlipAssistStep.SET_QUANTITY && b == FlipAssistStep.SET_QUANTITY;
	}
	
	private boolean isGrandExchangeOpen()
	{
		// Check the main GE interface (465) - this is the primary indicator
		Widget geWidget = client.getWidget(GE_INTERFACE_GROUP, 0);
		if (geWidget != null && !geWidget.isHidden())
		{
			return true;
		}
		
		// Also check if we're in the GE offer setup dialog specifically
		// Widget 162 is the chatbox which is used for many dialogs, so we need
		// to verify it's actually a GE-related dialog by checking for GE-specific content
		Widget offerPanel = client.getWidget(GE_INTERFACE_GROUP, GE_OFFER_PANEL_CHILD);
		return offerPanel != null && !offerPanel.isHidden();
	}
	
	private boolean isOfferSetupOpen()
	{
		Widget[] children = getOfferPanelChildren();
		if (children.length < 30)
		{
			return false;
		}
		
		boolean hasQuantityLabel = false;
		boolean hasPriceCoins = false;
		
		for (Widget child : children)
		{
			String text = getVisibleWidgetText(child);
			if (text != null)
			{
				hasQuantityLabel = hasQuantityLabel || text.contains("Quantity:");
				hasPriceCoins = hasPriceCoins || (text.contains(COINS_TEXT) && text.matches(".*\\d+.*"));
			}
		}
		return hasQuantityLabel && hasPriceCoins;
	}
	
	private Widget[] getOfferPanelChildren()
	{
		Widget offerPanel = client.getWidget(GE_INTERFACE_GROUP, GE_OFFER_PANEL_CHILD);
		if (offerPanel == null || offerPanel.isHidden())
		{
			return new Widget[0];
		}
		Widget[] children = offerPanel.getDynamicChildren();
		return children != null ? children : new Widget[0];
	}
	
	private String getVisibleWidgetText(Widget widget)
	{
		if (widget == null || widget.isHidden())
		{
			return null;
		}
		return widget.getText();
	}
	
	private int getInputType()
	{
		return client.getVarcIntValue(VARCLIENT_INPUT_TYPE);
	}
	
	private boolean isLikelyPriceInput()
	{
		for (int groupId : CHATBOX_WIDGET_GROUPS)
		{
			Boolean result = checkChatboxGroupForInputType(groupId);
			if (result != null)
			{
				return result;
			}
		}
		return getCurrentQuantityFromGE() > 1;
	}
	
	@SuppressWarnings("java:S2447") // null represents "no determination made" in tri-state logic
	private Boolean checkChatboxGroupForInputType(int groupId)
	{
		for (int childId = 0; childId <= 50; childId++)
		{
			Boolean result = checkWidgetForInputType(client.getWidget(groupId, childId));
			if (result != null)
			{
				return result;
			}
		}
		return null;
	}
	
	@SuppressWarnings("java:S2447") // null represents "no determination made" in tri-state logic
	private Boolean checkWidgetForInputType(Widget widget)
	{
		String text = getVisibleWidgetText(widget);
		if (text == null || text.isEmpty())
		{
			return null;
		}
		String lowerText = text.toLowerCase();
		if (lowerText.contains("price") && !lowerText.contains("price:"))
		{
			return Boolean.TRUE;
		}
		if (lowerText.contains("how many"))
		{
			return Boolean.FALSE;
		}
		return null;
	}
	
	private int getCurrentQuantityFromGE()
	{
		Widget[] children = getOfferPanelChildren();
		return findNumericValueInRange(children, GE_QTY_CHILD_START, GE_QTY_CHILD_END, false);
	}
	
	private int findNumericValueInRange(Widget[] children, int start, int end, boolean requireCoins)
	{
		for (int i = start; i <= end && i < children.length; i++)
		{
			int value = extractValueFromWidget(children[i], requireCoins);
			if (value >= 1)
			{
				return value;
			}
		}
		return 0;
	}
	
	private int extractValueFromWidget(Widget widget, boolean requireCoins)
	{
		String text = getVisibleWidgetText(widget);
		if (text == null || text.isEmpty())
		{
			return 0;
		}
		String lowerText = text.toLowerCase();
		if (requireCoins && !lowerText.contains(COINS_TEXT))
		{
			return 0;
		}
		if (!requireCoins && (lowerText.contains(COINS_TEXT) || lowerText.contains("quantity") || lowerText.contains("price")))
		{
			return 0;
		}
		return parseNumericText(text);
	}
	
	private int getCurrentPriceFromGE()
	{
		Widget[] children = getOfferPanelChildren();
		int value = findNumericValueInRange(children, GE_PRICE_CHILD_START, GE_PRICE_CHILD_END, true);
		return value > 0 ? value : getCurrentPriceFromGEFallback();
	}
	
	private int getCurrentPriceFromGEFallback()
	{
		int[] priceWidgetIds = {25, 27};
		for (int childId : priceWidgetIds)
		{
			int value = findPriceInWidget(client.getWidget(GE_INTERFACE_GROUP, childId));
			if (value > 0)
			{
				return value;
			}
		}
		return 0;
	}
	
	private int findPriceInWidget(Widget widget)
	{
		if (widget == null || widget.isHidden())
		{
			return 0;
		}
		
		int value = extractCoinsValue(widget.getText());
		if (value > 0)
		{
			return value;
		}
		
		Widget[] children = widget.getDynamicChildren();
		if (children == null)
		{
			return 0;
		}
		for (Widget child : children)
		{
			value = extractCoinsValue(getVisibleWidgetText(child));
			if (value > 0)
			{
				return value;
			}
		}
		return 0;
	}
	
	private int extractCoinsValue(String text)
	{
		if (text != null && text.toLowerCase().contains(COINS_TEXT))
		{
			return parseNumericText(text);
		}
		return 0;
	}
	
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
	
	public void setFocusedFlip(FocusedFlip focusedFlip)
	{
		this.focusedFlip = focusedFlip;
		if (focusedFlip != null)
		{
			setGELastSearchedItem(focusedFlip.getItemId());
		}
	}
	
	private void setGELastSearchedItem(int itemId)
	{
		clientThread.invokeLater(() -> {
			try
			{
				int[] varps = client.getVarps();
				varps[VarPlayerID.GE_LAST_SEARCHED] = itemId;
				client.queueChangedVarp(VarPlayerID.GE_LAST_SEARCHED);
			}
			catch (Exception e)
			{
				log.warn("Failed to set GE last searched item: {}", e.getMessage());
			}
		});
	}
	
	public void clearFocus()
	{
		this.focusedFlip = null;
		this.currentStep = FlipAssistStep.SELECT_ITEM;
	}
}


