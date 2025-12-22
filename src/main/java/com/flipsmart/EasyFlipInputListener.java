package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

import javax.inject.Inject;
import java.awt.Canvas;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Key listener for the EasyFlip feature.
 * Handles the hotkey press to auto-fill price and quantity in the GE.
 */
@Slf4j
@SuppressWarnings("deprecation") // VarClientStr and VarClientInt are deprecated but still functional in RuneLite
public class EasyFlipInputListener implements KeyListener
{
	private final Client client;
	private final ClientThread clientThread;
	private final FlipSmartConfig config;
	private final EasyFlipOverlay easyFlipOverlay;
	
	// GE Interface group IDs
	private static final int GE_INTERFACE_GROUP = 465;
	private static final int GE_OFFER_GROUP = 162;
	
	// GE Interface child IDs
	private static final int GE_QUANTITY_CHILD = 24;
	private static final int GE_PRICE_CHILD = 25;
	
	// Input type values (from VarClientInt.INPUT_TYPE)
	private static final int INPUT_TYPE_NUMERIC = 7;
	private static final int INPUT_TYPE_GE_ITEM_SEARCH = 14;
	
	// Chat message prefix
	private static final String CHAT_MESSAGE_PREFIX = "<col=ffaa00>[FlipSmart]</col> ";
	
	// Widget groups to scan for price/quantity dialog title
	private static final int[] CHATBOX_WIDGET_GROUPS = {162, 163, 164, 217, 219, 229, 548, 161};
	
	// Parent widgets to check for static/dynamic children with price/quantity text
	private static final int[][] CHATBOX_PARENT_WIDGETS = {
		{162, 0}, {162, 1}, {162, 5}, {162, 24},
		{217, 0}, {217, 4}, {217, 5}, {217, 6}
	};
	
	@Inject
	public EasyFlipInputListener(Client client, ClientThread clientThread, FlipSmartConfig config, EasyFlipOverlay easyFlipOverlay)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.easyFlipOverlay = easyFlipOverlay;
	}
	
	// Track the keyPressed event we're handling to consume its corresponding keyTyped
	private final AtomicReference<KeyEvent> handledKeyPressedEvent = new AtomicReference<>(null);
	
	@Override
	public void keyTyped(KeyEvent e)
	{
		// Only consume if this keyTyped corresponds to a keyPressed we handled
		// Check by comparing the key character
		KeyEvent handled = handledKeyPressedEvent.get();
		if (handled != null && 
			Character.toLowerCase(e.getKeyChar()) == Character.toLowerCase(handled.getKeyChar()))
		{
			handledKeyPressedEvent.set(null);
			e.consume();
		}
	}
	
	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!config.enableEasyFlip())
		{
			return;
		}
		
		// Check if the hotkey matches
		if (!config.easyFlipHotkey().matches(e))
		{
			return;
		}
		
		// Don't trigger if no focused flip (this doesn't need client thread)
		FocusedFlip focusedFlip = easyFlipOverlay.getFocusedFlip();
		if (focusedFlip == null)
		{
			return;
		}
		
		// Use synchronous invoke to check conditions BEFORE consuming the event
		clientThread.invoke(() -> {
			// Check if GE is open (must be on client thread)
			if (!isGrandExchangeOpen())
			{
				// Don't consume - GE not open, let 'E' pass through
				return;
			}
			
			int inputType = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
			
			// Handle GE item search - press E to select the item
			if (inputType == INPUT_TYPE_GE_ITEM_SEARCH && hasSearchResults())
			{
				handledKeyPressedEvent.set(e);
				e.consume();
				selectFirstSearchResult();
				return;
			}
			
			// Handle numeric input (price/quantity)
			if (inputType == INPUT_TYPE_NUMERIC)
			{
				handledKeyPressedEvent.set(e);
				e.consume();
				handleEasyFlipAction(focusedFlip);
				return;
			}
			
			// Not in a supported input type - let the key pass through
		});
	}
	
	@Override
	public void keyReleased(KeyEvent e)
	{
		// Not used
	}
	
	/**
	 * Check if the Grand Exchange interface is open.
	 * MUST be called on client thread.
	 */
	private boolean isGrandExchangeOpen()
	{
		Widget geWidget = client.getWidget(GE_INTERFACE_GROUP, 0);
		Widget offerWidget = client.getWidget(GE_OFFER_GROUP, 0);
		
		return (geWidget != null && !geWidget.isHidden()) ||
			   (offerWidget != null && !offerWidget.isHidden());
	}
	
	/**
	 * Handle the EasyFlip action - auto-fill price/quantity in GE.
	 * Called only when in a numeric input dialog.
	 * MUST be called on client thread.
	 */
	private void handleEasyFlipAction(FocusedFlip focusedFlip)
	{
		// Determine if it's price or quantity based on context
		if (isLikelyPriceInput())
		{
			setInputValue(focusedFlip.getCurrentStepPrice());
			log.debug("Auto-filled price: {}", focusedFlip.getCurrentStepPrice());
			sendChatMessage(focusedFlip.getItemName() + " price set to " + String.format("%,d", focusedFlip.getCurrentStepPrice()) + " gp");
		}
		else
		{
			setInputValue(focusedFlip.getCurrentStepQuantity());
			log.debug("Auto-filled quantity: {}", focusedFlip.getCurrentStepQuantity());
			sendChatMessage(focusedFlip.getItemName() + " quantity set to " + String.format("%,d", focusedFlip.getCurrentStepQuantity()));
		}
	}
	
	/**
	 * Set the current input field value and refresh the display.
	 * MUST be called on client thread.
	 */
	private void setInputValue(int value)
	{
		String valueStr = String.valueOf(value);
		client.setVarcStrValue(VarClientStr.INPUT_TEXT, valueStr);
		
		// Run the script to rebuild/refresh the chatbox input display
		// This makes the value visible in the input field
		client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, valueStr);
	}
	
	/**
	 * Determine if we're in a price input dialog vs quantity input.
	 * Checks the chatbox dialog title text for keywords.
	 * MUST be called on client thread.
	 */
	private boolean isLikelyPriceInput()
	{
		// Scan ALL visible widgets in chatbox-related groups for the dialog title
		// The OSRS chatbox input dialog title can appear in various locations
		for (int groupId : CHATBOX_WIDGET_GROUPS)
		{
			// Check children 0-100 for each group
			for (int childId = 0; childId <= 100; childId++)
			{
				Boolean result = checkWidgetForPriceKeyword(groupId, childId);
				if (result != null)
				{
					return result;
				}
			}
		}
		
		// Also try checking static and dynamic children of key parent widgets
		for (int[] parent : CHATBOX_PARENT_WIDGETS)
		{
			Widget parentWidget = client.getWidget(parent[0], parent[1]);
			if (parentWidget != null)
			{
				// Check static children
				Widget[] staticChildren = parentWidget.getStaticChildren();
				if (staticChildren != null)
				{
					for (int i = 0; i < staticChildren.length; i++)
					{
						Widget child = staticChildren[i];
						if (child != null && !child.isHidden())
						{
							Boolean result = checkTextForPriceKeyword(child.getText(), "static child " + i + " of " + parent[0] + ":" + parent[1]);
							if (result != null) return result;
						}
					}
				}
				
				// Check dynamic children
				Widget[] dynamicChildren = parentWidget.getDynamicChildren();
				if (dynamicChildren != null)
				{
					for (int i = 0; i < dynamicChildren.length; i++)
					{
						Widget child = dynamicChildren[i];
						if (child != null && !child.isHidden())
						{
							Boolean result = checkTextForPriceKeyword(child.getText(), "dynamic child " + i + " of " + parent[0] + ":" + parent[1]);
							if (result != null) return result;
						}
					}
				}
				
				// Check nested children
				Widget[] nestedChildren = parentWidget.getNestedChildren();
				if (nestedChildren != null)
				{
					for (int i = 0; i < nestedChildren.length; i++)
					{
						Widget child = nestedChildren[i];
						if (child != null && !child.isHidden())
						{
							Boolean result = checkTextForPriceKeyword(child.getText(), "nested child " + i + " of " + parent[0] + ":" + parent[1]);
							if (result != null) return result;
						}
					}
				}
			}
		}
		
		// Fallback: Check the GE interface state to determine what we're setting
		// If quantity field has a non-default value, we're probably setting price
		Boolean geStateResult = determineFromGEState();
		if (geStateResult != null)
		{
			return geStateResult;
		}
		
		log.debug("Could not determine input type from widgets or GE state, defaulting to quantity");
		// Default to quantity - user can press again for price
		return false;
	}
	
	/**
	 * Try to determine price vs quantity input based on GE interface state.
	 * If quantity is already set to a non-default value, we're likely setting price.
	 */
	private Boolean determineFromGEState()
	{
		// GE offer setup widget children for quantity and price displays
		// Widget 465:24 = Quantity display, 465:25 = Price display (may vary)
		Widget quantityWidget = client.getWidget(GE_INTERFACE_GROUP, GE_QUANTITY_CHILD);
		Widget priceWidget = client.getWidget(GE_INTERFACE_GROUP, GE_PRICE_CHILD);
		
		String quantityText = (quantityWidget != null && !quantityWidget.isHidden()) ? quantityWidget.getText() : null;
		String priceText = (priceWidget != null && !priceWidget.isHidden()) ? priceWidget.getText() : null;
		
		log.debug("GE state check - Quantity widget text: '{}', Price widget text: '{}'", quantityText, priceText);
		
		// Parse quantity from text (e.g., "6,000" -> 6000)
		int currentQuantity = parseNumberFromText(quantityText);
		int currentPrice = parseNumberFromText(priceText);
		
		log.debug("GE state check - Parsed quantity: {}, price: {}", currentQuantity, currentPrice);
		
		// If quantity has been set (> 1, since default is often 1) and we're in an input,
		// user is probably now setting price
		if (currentQuantity > 1)
		{
			log.debug("GE quantity already set to {}, likely setting PRICE now", currentQuantity);
			return Boolean.TRUE;
		}
		
		// If quantity is still at default (1 or 0), user is probably setting quantity first
		log.debug("GE quantity appears at default ({}), likely setting QUANTITY", currentQuantity);
		return Boolean.FALSE;
	}
	
	/**
	 * Parse a number from formatted text like "6,000" or "6000 coins".
	 */
	private int parseNumberFromText(String text)
	{
		if (text == null || text.isEmpty())
		{
			return 0;
		}
		try
		{
			// Remove commas, "coins", "gp", and any other non-numeric characters
			String numericOnly = text.replaceAll("\\D", "");
			if (numericOnly.isEmpty())
			{
				return 0;
			}
			return Integer.parseInt(numericOnly);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
	
	/**
	 * Check a specific widget for price/quantity keywords.
	 * Returns Boolean.TRUE if price, Boolean.FALSE if quantity, null if no match.
	 */
	private Boolean checkWidgetForPriceKeyword(int groupId, int childId)
	{
		Widget widget = client.getWidget(groupId, childId);
		if (widget != null && !widget.isHidden())
		{
			String text = widget.getText();
			return checkTextForPriceKeyword(text, groupId + ":" + childId);
		}
		return null;
	}
	
	/**
	 * Check text for price/quantity keywords.
	 * Returns Boolean.TRUE if price, Boolean.FALSE if quantity, null if no match.
	 */
	private Boolean checkTextForPriceKeyword(String text, String source)
	{
		if (text != null && !text.isEmpty())
		{
			String lowerText = text.toLowerCase();
			// "Set a price for each item" = price input
			if (lowerText.contains("price") && !lowerText.contains("price:"))
			{
				log.debug("Detected PRICE input from {}: '{}'", source, text);
				return Boolean.TRUE;
			}
			// "How many do you wish to" = quantity input
			if (lowerText.contains("how many"))
			{
				log.debug("Detected QUANTITY input from {}: '{}'", source, text);
				return Boolean.FALSE;
			}
		}
		return null;
	}
	
	/**
	 * Send a message to the chatbox (game message style).
	 * MUST be called on client thread.
	 */
	private void sendChatMessage(String message)
	{
		client.addChatMessage(
			net.runelite.api.ChatMessageType.GAMEMESSAGE,
			"",
			CHAT_MESSAGE_PREFIX + message,
			null
		);
	}
	
	/**
	 * Check if there are GE search results displayed.
	 * MUST be called on client thread.
	 */
	private boolean hasSearchResults()
	{
		// Check if the search results widget exists and has children
		Widget searchResults = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		if (searchResults == null || searchResults.isHidden())
		{
			return false;
		}
		
		Widget[] children = searchResults.getDynamicChildren();
		// Each search result has 3 children (icon, name, ?)
		return children != null && children.length >= 3;
	}
	
	/**
	 * Select the first item in the GE search results by dispatching Enter key.
	 * This simulates the user pressing Enter to confirm their search selection.
	 * MUST be called on client thread.
	 */
	private void selectFirstSearchResult()
	{
		// Get the game canvas to dispatch the key event to
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			log.debug("Cannot select search result: canvas is null");
			return;
		}
		
		log.debug("Dispatching Enter key to select first search result");
		
		// Create and dispatch Enter key press event
		KeyEvent enterPressed = new KeyEvent(
			canvas,
			KeyEvent.KEY_PRESSED,
			System.currentTimeMillis(),
			0,  // no modifiers
			KeyEvent.VK_ENTER,
			KeyEvent.CHAR_UNDEFINED
		);
		
		// Create and dispatch Enter key typed event
		KeyEvent enterTyped = new KeyEvent(
			canvas,
			KeyEvent.KEY_TYPED,
			System.currentTimeMillis(),
			0,  // no modifiers
			KeyEvent.VK_UNDEFINED,
			'\n'
		);
		
		// Create and dispatch Enter key released event
		KeyEvent enterReleased = new KeyEvent(
			canvas,
			KeyEvent.KEY_RELEASED,
			System.currentTimeMillis(),
			0,  // no modifiers
			KeyEvent.VK_ENTER,
			KeyEvent.CHAR_UNDEFINED
		);
		
		// Dispatch through the keyboard focus manager to properly route to the game
		KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		focusManager.dispatchKeyEvent(enterPressed);
		focusManager.dispatchKeyEvent(enterTyped);
		focusManager.dispatchKeyEvent(enterReleased);
	}
}
