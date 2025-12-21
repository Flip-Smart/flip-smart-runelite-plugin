package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

import javax.inject.Inject;
import java.awt.event.KeyEvent;

/**
 * Key listener for the EasyFlip feature.
 * Handles the hotkey press to auto-fill price and quantity in the GE.
 */
@Slf4j
@SuppressWarnings("deprecation") // VarClientStr and VarClientInt are deprecated but still functional
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
	
	// VarClientInt.INPUT_TYPE values
	private static final int INPUT_TYPE_NONE = 0;
	private static final int INPUT_TYPE_TEXT = 6;
	private static final int INPUT_TYPE_NUMERIC = 7;
	private static final int INPUT_TYPE_CHATBOX_SEARCH = 14;
	
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
	
	// Track if we should consume the next keyTyped event
	private volatile boolean consumeNextKeyTyped = false;
	
	@Override
	public void keyTyped(KeyEvent e)
	{
		// Consume keyTyped event if we handled the corresponding keyPressed
		if (consumeNextKeyTyped)
		{
			consumeNextKeyTyped = false;
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
		
		// Mark that we should consume the next keyTyped event to prevent 'e' being typed
		consumeNextKeyTyped = true;
		
		// Execute everything on client thread since we need to access client APIs
		clientThread.invokeLater(() -> {
			// Check if typing in chat (must be on client thread)
			if (isTypingInChat())
			{
				return;
			}
			
			// Check if GE is open (must be on client thread)
			if (!isGrandExchangeOpen())
			{
				return;
			}
			
			handleEasyFlipAction(focusedFlip);
		});
		
		// Consume the event to prevent it from being passed to the game
		e.consume();
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
	 * MUST be called on client thread.
	 */
	private void handleEasyFlipAction(FocusedFlip focusedFlip)
	{
		// Check if we're in a GE interface
		if (!isGrandExchangeOpen())
		{
			log.debug("GE not open, showing info message");
			sendChatMessage("Open a GE slot and search for " + focusedFlip.getItemName() + " first!");
			return;
		}
		
		// Check if we're in an input mode (chatbox input dialog)
		int inputType = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
		log.debug("EasyFlip action - inputType: {}", inputType);
		
		if (inputType != INPUT_TYPE_NONE)
		{
			// Input dialog is open - fill the value
			if (inputType == INPUT_TYPE_NUMERIC)
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
			else
			{
				// Text/search input - we don't auto-fill search to avoid macro detection
				// Just show a helpful message
				sendChatMessage("Search for: " + focusedFlip.getItemName());
				sendChatMessage("Press [E] in price/quantity input to auto-fill");
			}
		}
		else
		{
			// Not in an input field - show info message
			log.debug("No input active, showing info message");
			showInfoMessage(focusedFlip);
		}
	}
	
	/**
	 * Show info message about the focused flip.
	 * MUST be called on client thread.
	 */
	private void showInfoMessage(FocusedFlip focusedFlip)
	{
		// Send as two messages to avoid truncation
		String msg1 = String.format("%s: %,d gp @ %,d",
			focusedFlip.getItemName(),
			focusedFlip.getCurrentStepPrice(),
			focusedFlip.getCurrentStepQuantity());
		sendChatMessage(msg1);
		sendChatMessage("Use hot key [E] to auto fill price/quantity");
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
	 * Check if the user is typing in the regular chat.
	 * MUST be called on client thread.
	 */
	private boolean isTypingInChat()
	{
		String chatText = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		
		if (chatText == null || chatText.isEmpty())
		{
			return false;
		}
		
		// Allow if we're in GE input (numeric, text, or chatbox search)
		int inputType = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
		if (inputType == INPUT_TYPE_NUMERIC || inputType == INPUT_TYPE_TEXT || inputType == INPUT_TYPE_CHATBOX_SEARCH)
		{
			return false; // Allow hotkey in GE inputs
		}
		
		// Also allow if GE is open (might be in search)
		return !isGrandExchangeOpen();
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
}
