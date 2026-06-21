package com.flipsmart;
import com.flipsmart.util.GeTax;
import com.flipsmart.util.TimeUtils;

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
import java.util.function.Consumer;

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
	private static final ThreadLocal<DecimalFormat> PRICE_FORMAT =
		ThreadLocal.withInitial(() -> new DecimalFormat("#,###"));
	
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
	private static final Color COLOR_LOSS = new Color(255, 100, 100);
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
	private static final String UPGRADE_MESSAGE = "Upgrade to Premium for more flip slots";
	private static final String LOGIN_MESSAGE = "Log in to use Flip Assist";
	private static final String HISTORY_PROMPT_MESSAGE = "Open GE History tab to backfill recent trades";
	
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
	
	private final FlipSmartPlugin flipSmartPlugin;
	private final Client client;
	private final ClientThread clientThread;
	private final FlipSmartConfig config;
	private final ItemManager itemManager;
	private final TradeActivityLog tradeActivityLog;
	private final GEHistoryService geHistoryService;

	@Getter
	private FocusedFlip focusedFlip;

	// Auto-recommend overlay message (shown in hint box when no flip is focused)
	private volatile String autoStatusMessage;
	private volatile int autoStatusItemId;

	// Animation state
	private long animationStartTime = System.currentTimeMillis();
	private static final long PULSE_DURATION = 1500; // ms for one pulse cycle
	
	/**
	 * The current step in the flip assist workflow
	 */
	public enum FlipAssistStep
	{
		SELECT_ITEM("Select Item", "Click BUY on an empty slot"),
		SEARCH_ITEM("Search Item", "Press Enter or click the item"),
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
	private FlipAssistStep previousStep = FlipAssistStep.SELECT_ITEM;
	private Consumer<FlipAssistStep> onStepChanged;

	public void setOnStepChanged(Consumer<FlipAssistStep> callback)
	{
		this.onStepChanged = callback;
	}

	@Inject
	private FlipAssistOverlay(FlipSmartPlugin flipSmartPlugin, Client client, ClientThread clientThread, FlipSmartConfig config, ItemManager itemManager, TradeActivityLog tradeActivityLog, GEHistoryService geHistoryService)
	{
		this.flipSmartPlugin = flipSmartPlugin;
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.itemManager = itemManager;
		this.tradeActivityLog = tradeActivityLog;
		this.geHistoryService = geHistoryService;
		
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
		setMovable(true);
	}
	
	public void updateStep()
	{
		FlipAssistStep newStep;
		final FocusedFlip flip = focusedFlip;
		if (flip == null)
		{
			newStep = FlipAssistStep.SELECT_ITEM;
		}
		else if (!isGrandExchangeOpen())
		{
			newStep = flip.isBuying() ? FlipAssistStep.WAITING_BUY : FlipAssistStep.WAITING_SELL;
		}
		else
		{
			newStep = determineGEOpenStep();
		}

		currentStep = newStep;
		if (currentStep != previousStep)
		{
			previousStep = currentStep;
			if (onStepChanged != null)
			{
				onStepChanged.accept(currentStep);
			}
		}
	}
	
	private FlipAssistStep determineNumericInputStep()
	{
		final FocusedFlip flip = focusedFlip;
		if (flip != null && isLikelyPriceInput())
		{
			return flip.isBuying() ? FlipAssistStep.SET_PRICE : FlipAssistStep.SET_SELL_PRICE;
		}
		return FlipAssistStep.SET_QUANTITY;
	}
	
	private FlipAssistStep determineGEOpenStep()
	{
		int inputType = getInputType();
		if (inputType == INPUT_TYPE_GE_SEARCH)
		{
			return FlipAssistStep.SEARCH_ITEM;
		}
		if (inputType == INPUT_TYPE_NUMERIC)
		{
			return determineNumericInputStep();
		}
		if (isOfferSetupOpen())
		{
			return determineOfferSetupStep();
		}
		final FocusedFlip flip = focusedFlip;
		if (flip == null)
		{
			return FlipAssistStep.SELECT_ITEM;
		}
		return flip.isBuying() ? FlipAssistStep.SELECT_ITEM : FlipAssistStep.SELL_ITEMS;
	}

	private FlipAssistStep determineOfferSetupStep()
	{
		final FocusedFlip flip = focusedFlip;
		if (flip == null)
		{
			return FlipAssistStep.SELECT_ITEM;
		}
		return offerSetupStep(getCurrentQuantityFromGE(), flip.getCurrentStepQuantity(),
			getCurrentPriceFromGE(), flip.getCurrentStepPrice(), flip.isBuying());
	}

	/**
	 * Pure step decision for the offer-setup screen. Quantity must match EXACTLY — it is an
	 * integer count with no rounding, so the ±1 price tolerance would wrongly accept the
	 * default qty 1 when the target is 2. Price keeps the ±1 tolerance. Package-private for tests.
	 */
	static FlipAssistStep offerSetupStep(int currentQty, int targetQty, int currentPrice, int targetPrice,
		boolean buying)
	{
		boolean qtyCorrect = targetQty > 0 && currentQty == targetQty;
		boolean priceCorrect = isValueWithinTolerance(currentPrice, targetPrice);

		if (qtyCorrect && priceCorrect)
		{
			return buying ? FlipAssistStep.CONFIRM_OFFER : FlipAssistStep.CONFIRM_SELL;
		}
		if (!qtyCorrect)
		{
			return FlipAssistStep.SET_QUANTITY;
		}
		return buying ? FlipAssistStep.SET_PRICE : FlipAssistStep.SET_SELL_PRICE;
	}
	
	/**
	 * Check if a GE value matches the target within ±1 tolerance.
	 * Tolerance is needed because GE can have slight rounding differences
	 * when displaying prices/quantities (e.g., 1gp variance in price).
	 */
	private static boolean isValueWithinTolerance(int current, int target)
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

		// If no flip is focused, show hint box only when GE is open (or showAssistantAlways)
		if (focusedFlip == null)
		{
			if (!isGrandExchangeOpen() && !config.showAssistantAlways())
			{
				return null;
			}
			// While the player is in any buy/sell offer interface (item search,
			// numeric qty/price input, or the offer-setup screen) the offer takes
			// priority — every status/hint prompt, including the History-backfill
			// prompt, is suppressed so nothing overlays the buy/sell screens. The
			// prompts re-surface at the main GE view. Off the offer interface, the
			// History prompt still takes priority over the auto-recommend status.
			String fallbackMessage = flipSmartPlugin.isAutoRecommendActive()
				? flipSmartPlugin.getAutoRecommendOverlayMessage()
				: null;
			String message = selectNoFocusMessage(
				isGrandExchangeOpen() && geHistoryService.hasUnverifiedOfflineFills(),
				isInOfferInterface(),
				autoStatusMessage,
				fallbackMessage,
				flipSmartPlugin.getApiClient().isAuthenticated(),
				HISTORY_PROMPT_MESSAGE,
				LOGIN_MESSAGE,
				HINT_MESSAGE);
			if (message == null)
			{
				return null;
			}
			return renderHintBox(graphics, message);
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
	 * Render a small hint box with a title and message.
	 * When an item icon is present, shows a two-line layout:
	 *   "Flip Assist" title
	 *   "Collect items from GE" subtitle
	 *   [icon] item name
	 */
	private Dimension renderHintBox(Graphics2D graphics, String message)
	{
		int itemId = autoStatusItemId;
		boolean hasIcon = itemId > 0;
		int hintIconSize = 20;

		// Enable anti-aliasing
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		// Pre-compute wrapped lines for text-only messages to determine panel height
		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics smallMetrics = graphics.getFontMetrics();
		int textPadding = 10;
		int maxTextWidth = HINT_PANEL_WIDTH - textPadding * 2;
		java.util.List<String> wrappedLines = null;

		// Activity log: shown in idle assist states (waiting/monitoring) (AC4)
		java.util.List<TradeActivityLog.Entry> activityEntries =
			isIdleAssistMessage(message) ? tradeActivityLog.snapshot() : java.util.Collections.emptyList();
		int activityLogHeight = computeActivityLogHeight(activityEntries, smallMetrics);

		int panelHeight;
		if (hasIcon)
		{
			int iconSpace = 20 + 6; // hintIconSize + gap
			java.util.List<String> iconLines = wrapText(message, smallMetrics, maxTextWidth - iconSpace);
			int lineHeight = smallMetrics.getHeight();
			int textHeight = lineHeight * iconLines.size();
			// Title (20px) + gap (12px) + max(icon height, text height) + bottom padding (8px)
			panelHeight = 20 + 12 + Math.max(20, textHeight) + 8;
			panelHeight = Math.max(panelHeight, HINT_PANEL_HEIGHT);
		}
		else
		{
			wrappedLines = wrapText(message, smallMetrics, maxTextWidth);
			int lineHeight = smallMetrics.getHeight();
			// Title (20px) + gap (8px) + wrapped lines + bottom padding (8px)
			panelHeight = 20 + 8 + lineHeight * wrappedLines.size() + 8;
			panelHeight = Math.max(panelHeight, HINT_PANEL_HEIGHT);
		}
		panelHeight += activityLogHeight;

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
		graphics.fillRoundRect(-3, -3, HINT_PANEL_WIDTH + 6, panelHeight + 6, 10, 10);

		// Draw background
		graphics.setColor(COLOR_BG_DARK);
		graphics.fillRoundRect(0, 0, HINT_PANEL_WIDTH, panelHeight, 8, 8);

		// Draw accent border (animated)
		Color borderColor = new Color(
			COLOR_ACCENT.getRed(),
			COLOR_ACCENT.getGreen(),
			COLOR_ACCENT.getBlue(),
			(int)(150 + 100 * pulseAlpha)
		);
		graphics.setColor(borderColor);
		graphics.setStroke(new BasicStroke(1.5f));
		graphics.drawRoundRect(0, 0, HINT_PANEL_WIDTH, panelHeight, 8, 8);

		// Draw title
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(COLOR_ACCENT);
		FontMetrics boldMetrics = graphics.getFontMetrics();
		int titleWidth = boldMetrics.stringWidth(HINT_TITLE);
		graphics.drawString(HINT_TITLE, (HINT_PANEL_WIDTH - titleWidth) / 2, 20);

		if (hasIcon)
		{
			// Layout: [icon] + message text (centered together)
			graphics.setFont(FontManager.getRunescapeSmallFont());
			graphics.setColor(COLOR_TEXT);

			AsyncBufferedImage itemImage = itemManager.getImage(itemId);

			// Wrap message to fit alongside icon
			int iconSpace = hintIconSize + 6;
			java.util.List<String> iconLines = wrapText(message, smallMetrics, maxTextWidth - iconSpace);

			int lineHeight = smallMetrics.getHeight();
			int textBlockHeight = lineHeight * iconLines.size();
			int iconY = 32 + (textBlockHeight - hintIconSize) / 2;
			int textStartX = textPadding + iconSpace;

			if (itemImage != null)
			{
				graphics.drawImage(itemImage, textPadding, iconY, hintIconSize, hintIconSize, null);
			}

			int y = 32 + lineHeight;
			for (String line : iconLines)
			{
				drawHintLine(graphics, line, textStartX, y, smallMetrics);
				y += lineHeight;
			}
		}
		else
		{
			// Wrapped text layout
			graphics.setFont(FontManager.getRunescapeSmallFont());
			int lineHeight = smallMetrics.getHeight();
			int y = 28 + lineHeight;
			for (String line : wrappedLines)
			{
				int lineWidth = smallMetrics.stringWidth(line);
				drawHintLine(graphics, line, (HINT_PANEL_WIDTH - lineWidth) / 2, y, smallMetrics);
				y += lineHeight;
			}
		}

		if (!activityEntries.isEmpty())
		{
			int activityTop = panelHeight - activityLogHeight;
			renderActivityLog(graphics, activityEntries, activityTop, smallMetrics);
		}

		return new Dimension(HINT_PANEL_WIDTH, panelHeight);
	}

	private boolean isIdleAssistMessage(String message)
	{
		if (message == null)
		{
			return false;
		}
		return message.startsWith("Waiting for flips")
			|| message.startsWith("Monitoring active offers");
	}

	private int computeActivityLogHeight(java.util.List<TradeActivityLog.Entry> entries, FontMetrics fm)
	{
		if (entries.isEmpty())
		{
			return 0;
		}
		// gap above divider (4) + divider line (1) + gap below (4) + entries + bottom padding (4)
		return 4 + 1 + 4 + fm.getHeight() * entries.size() + 4;
	}

	private void renderActivityLog(Graphics2D graphics, java.util.List<TradeActivityLog.Entry> entries, int top, FontMetrics fm)
	{
		int dividerY = top + 4;
		graphics.setColor(COLOR_STEP_PENDING);
		graphics.setStroke(new BasicStroke(1f));
		graphics.drawLine(10, dividerY, HINT_PANEL_WIDTH - 10, dividerY);

		graphics.setFont(FontManager.getRunescapeSmallFont());
		int lineHeight = fm.getHeight();
		int textY = dividerY + 4 + fm.getAscent();
		int leftX = 10;
		int rightEdge = HINT_PANEL_WIDTH - 10;
		for (TradeActivityLog.Entry entry : entries)
		{
			String ago = TimeUtils.formatRelativeAgo(entry.getTimestampMs());
			int agoWidth = fm.stringWidth(ago);
			int agoX = rightEdge - agoWidth;

			String prefix = entry.isBuy() ? "Buy" : "Sell";
			String head = String.format("%s %dx ", prefix, entry.getQuantity());
			int nameMaxWidth = agoX - leftX - fm.stringWidth(head) - 4;
			String name = truncateString(entry.getItemName(), Math.max(0, nameMaxWidth), fm);

			graphics.setColor(entry.isBuy() ? COLOR_BUY : COLOR_SELL);
			graphics.drawString(head + name, leftX, textY);

			graphics.setColor(COLOR_TEXT_DIM);
			graphics.drawString(ago, agoX, textY);

			textY += lineHeight;
		}
	}

	private void drawHintLine(Graphics2D graphics, String line, int x, int y, FontMetrics fm)
	{
		int parenIdx = line.lastIndexOf(" (");
		boolean isNetParen = parenIdx > 0 && line.endsWith(")")
			&& (line.startsWith("(-", parenIdx + 1) || line.startsWith("(+", parenIdx + 1));
		if (!isNetParen)
		{
			graphics.setColor(getHintLineColor(line));
			graphics.drawString(line, x, y);
			return;
		}
		String base = line.substring(0, parenIdx);
		String paren = line.substring(parenIdx);
		graphics.setColor(getHintLineColor(base));
		graphics.drawString(base, x, y);
		graphics.setColor(paren.trim().startsWith("(-") ? COLOR_LOSS : COLOR_PROFIT);
		graphics.drawString(paren, x + fm.stringWidth(base), y);
	}

	private Color getHintLineColor(String line)
	{
		String trimmed = line.trim();
		if (trimmed.startsWith("Loss"))
		{
			return COLOR_LOSS;
		}
		if (trimmed.startsWith("Profit") || trimmed.startsWith("Breakeven"))
		{
			return COLOR_PROFIT;
		}
		if (trimmed.startsWith("Open GE History"))
		{
			return COLOR_BUY;
		}
		if (trimmed.endsWith("gp"))
		{
			return COLOR_BUY;
		}
		if (!trimmed.contains(":") && !trimmed.isEmpty()
			&& !trimmed.startsWith("Consider") && !trimmed.startsWith("Re-sell")
			&& !trimmed.startsWith("Adjust") && !trimmed.startsWith("Margin")
			&& !trimmed.startsWith("Click") && !trimmed.startsWith("Monitoring")
			&& !trimmed.startsWith("Waiting") && !trimmed.startsWith("Checking")
			&& !trimmed.startsWith("Auto"))
		{
			return COLOR_BUY;
		}
		return COLOR_TEXT;
	}

	private java.util.List<String> wrapText(String text, FontMetrics fm, int maxWidth)
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		for (String segment : text.split("\n"))
		{
			if (fm.stringWidth(segment) <= maxWidth)
			{
				lines.add(segment);
			}
			else
			{
				wrapSegment(segment, fm, maxWidth, lines);
			}
		}
		return lines;
	}

	private void wrapSegment(String segment, FontMetrics fm, int maxWidth, java.util.List<String> lines)
	{
		StringBuilder currentLine = new StringBuilder();
		for (String word : segment.split(" "))
		{
			String candidate = currentLine.length() == 0 ? word : currentLine + " " + word;
			if (fm.stringWidth(candidate) <= maxWidth)
			{
				currentLine = new StringBuilder(candidate);
			}
			else
			{
				if (currentLine.length() > 0)
				{
					lines.add(currentLine.toString());
				}
				currentLine = new StringBuilder(word);
			}
		}
		if (currentLine.length() > 0)
		{
			lines.add(currentLine.toString());
		}
	}
	
	private int renderHeader(Graphics2D graphics, int y)
	{
		final FocusedFlip flip = focusedFlip;
		if (flip == null)
		{
			return y;
		}
		y += 4;

		AsyncBufferedImage itemImage = itemManager.getImage(flip.getItemId());
		if (itemImage != null)
		{
			graphics.drawImage(itemImage, SECTION_PADDING, y, ICON_SIZE, ICON_SIZE, null);
		}

		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(COLOR_TEXT);
		String itemName = truncateString(flip.getItemName(), PANEL_WIDTH - ICON_SIZE - SECTION_PADDING * 3, graphics.getFontMetrics());
		graphics.drawString(itemName, SECTION_PADDING + ICON_SIZE + 6, y + 10);

		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.setColor(flip.isBuying() ? COLOR_BUY : COLOR_SELL);
		String stepLabel;
		if (flip.isBuying())
		{
			stepLabel = "BUYING";
		}
		else
		{
			// Show "MODIFY" if there's already an active sell order for this item
			com.flipsmart.trading.OfferStore store = flipSmartPlugin.getOfferStore();
			boolean hasActiveSell = store != null && store.hasActiveSellOfferForItem(flip.getItemId());
			stepLabel = hasActiveSell ? "MODIFY" : "SELLING";
		}
		graphics.drawString(stepLabel, SECTION_PADDING + ICON_SIZE + 6, y + 24);

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
		final FocusedFlip flip = focusedFlip;
		if (flip == null)
		{
			return new FlipAssistStep[0];
		}
		if (flip.isBuying())
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
		final FocusedFlip flip = focusedFlip;
		if (flip == null)
		{
			return;
		}
		graphics.setFont(FontManager.getRunescapeSmallFont());
		int lineHeight = 12;

		String priceLabel = flip.isBuying() ? "Buy at:" : "Sell at:";
		drawLabelValue(graphics, priceLabel, PRICE_FORMAT.get().format(flip.getCurrentStepPrice()) + " gp",
			y + lineHeight, flip.isBuying() ? COLOR_BUY : COLOR_SELL);

		drawLabelValue(graphics, "Qty:", PRICE_FORMAT.get().format(flip.getCurrentStepQuantity()),
			y + lineHeight * 2, COLOR_TEXT);

		if (flip.isBuying() && flip.getSellPrice() > 0)
		{
			int totalProfit = calculateTotalProfit();
			drawLabelValue(graphics, "Profit:", PRICE_FORMAT.get().format(totalProfit) + " gp",
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
		final FocusedFlip flip = focusedFlip;
		if (flip == null)
		{
			return 0;
		}
		int margin = flip.getSellPrice() - flip.getBuyPrice();
		int geTax = GeTax.taxFor(flip.getItemId(), flip.getSellPrice());
		return (margin - geTax) * flip.getBuyQuantity();
	}
	
	private String formatStepDescription()
	{
		String hotkeyName = config.flipAssistHotkey().toString();
		final FocusedFlip flip = focusedFlip;
		if (flip == null)
		{
			return currentStep.getDescription();
		}

		switch (currentStep)
		{
			case SET_QUANTITY:
				int targetQty = flip.getCurrentStepQuantity();
				// Just show the target quantity with hotkey
				return String.format(currentStep.getDescription(), hotkeyName,
					PRICE_FORMAT.get().format(targetQty));
			case SET_PRICE:
			case SET_SELL_PRICE:
				int targetPrice = flip.getCurrentStepPrice();
				// Just show the target price with hotkey
				return String.format(currentStep.getDescription(), hotkeyName,
					PRICE_FORMAT.get().format(targetPrice));
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

	/**
	 * True while the player is in any GE buy/sell offer interface: the item-search
	 * screen, the numeric quantity/price input, or the offer-setup screen. While one
	 * is open the offer takes priority and transient status prompts are hidden.
	 */
	private boolean isInOfferInterface()
	{
		if (!isGrandExchangeOpen())
		{
			return false;
		}
		int inputType = getInputType();
		return inputType == INPUT_TYPE_GE_SEARCH
			|| inputType == INPUT_TYPE_NUMERIC
			|| isOfferSetupOpen();
	}

	/**
	 * Pick the hint/status message to draw when no flip is focused, or null to draw
	 * nothing. While any buy/sell offer interface is open the offer takes priority and
	 * ALL prompts — including the one-shot history-backfill prompt — are suppressed so
	 * nothing overlays the buy/sell screens; the prompts re-surface at the main GE view
	 * (none of them are cleared). Off the offer interface the history prompt takes
	 * priority over the auto-recommend status, then login, then the generic hint.
	 */
	static String selectNoFocusMessage(
		boolean showHistoryPrompt,
		boolean offerInterfaceOpen,
		String autoStatusMessage,
		String autoRecommendFallback,
		boolean authenticated,
		String historyMessage,
		String loginMessage,
		String hintMessage)
	{
		if (offerInterfaceOpen)
		{
			return null;
		}
		if (showHistoryPrompt)
		{
			return historyMessage;
		}
		if (autoStatusMessage != null)
		{
			return autoStatusMessage;
		}
		if (autoRecommendFallback != null)
		{
			return autoRecommendFallback;
		}
		if (!authenticated)
		{
			return loginMessage;
		}
		return hintMessage;
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
			this.autoStatusMessage = null;
			setGELastSearchedItem(focusedFlip.getItemId());
		}
	}

	public void setAutoStatusMessage(String message, int itemId)
	{
		this.autoStatusMessage = message;
		this.autoStatusItemId = itemId;
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
		this.autoStatusMessage = null;
		this.autoStatusItemId = 0;
		this.currentStep = FlipAssistStep.SELECT_ITEM;
	}
}


