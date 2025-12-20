package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

import javax.inject.Inject;
import java.awt.Canvas;
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
		Widget geInterface = client.getWidget(GE_INTERFACE_GROUP, 0);
		Widget offerSetup = client.getWidget(GE_OFFER_GROUP, 0);
		
		boolean inGE = (geInterface != null && !geInterface.isHidden()) || 
					   (offerSetup != null && !offerSetup.isHidden());
		
		if (!inGE)
		{
			log.debug("GE not open, showing info message");
			sendChatMessage("Open a GE slot and search for " + focusedFlip.getItemName() + " first!");
			return;
		}
		
		// Check if we're in an input mode (chatbox input dialog)
		int inputType = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
		log.info("EasyFlip action - inputType: {}", inputType);
		
		if (inputType != 0)
		{
			// Input dialog is open - fill the value
			// inputType 7 = numeric input (price/quantity)
			// inputType 6 = text input (search)
			// inputType 14 = chatbox search (GE item search)
			if (inputType == 7)
			{
				// Determine if it's price or quantity based on context
				if (isLikelyPriceInput())
				{
					setInputValue(focusedFlip.getCurrentStepPrice());
					log.info("Auto-filled price: {}", focusedFlip.getCurrentStepPrice());
					sendChatMessage(focusedFlip.getItemName() + " price set to " + String.format("%,d", focusedFlip.getCurrentStepPrice()) + " gp");
				}
				else
				{
					setInputValue(focusedFlip.getCurrentStepQuantity());
					log.info("Auto-filled quantity: {}", focusedFlip.getCurrentStepQuantity());
					sendChatMessage(focusedFlip.getItemName() + " quantity set to " + String.format("%,d", focusedFlip.getCurrentStepQuantity()));
				}
			}
			else
			{
				// Other input type - assume it's a text/search input (could be GE search)
				fillSearchInput(focusedFlip.getItemName(), inputType);
			}
		}
		else
		{
			// Not in an input field - check if GE search widget is visible
			// Try various known GE search widget IDs
			log.info("No input type detected (inputType=0), checking for GE search widgets...");
			
			// Check multiple possible search widget locations
			int[][] searchWidgets = {
				{162, 53}, // Offer container search
				{162, 24}, // Possible search location
				{465, 57}, // GE search bar
				{465, 7},  // GE search button
				{162, 1},  // Offer search
			};
			
			boolean foundSearch = false;
			for (int[] widgetId : searchWidgets)
			{
				Widget searchWidget = client.getWidget(widgetId[0], widgetId[1]);
				if (searchWidget != null)
				{
					log.info("Widget {}:{} - hidden: {}, text: '{}'", 
						widgetId[0], widgetId[1], searchWidget.isHidden(), searchWidget.getText());
				}
			}
			
			// Check if there's any active chatbox search by checking VarClientStr values
			String currentInput = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
			String chatboxTyped = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
			log.info("Current INPUT_TEXT: '{}', CHATBOX_TYPED_TEXT: '{}'", currentInput, chatboxTyped);
			
			// Try setting the search text anyway - it might work even if widget isn't visible
			Widget geSearchWidget = client.getWidget(162, 53);
			Widget geSearchBar = client.getWidget(465, 7);
			
			if ((geSearchWidget != null && !geSearchWidget.isHidden()) || 
				(geSearchBar != null && !geSearchBar.isHidden()) ||
				(chatboxTyped != null && !chatboxTyped.isEmpty()))
			{
				// GE search is open - try to fill it
				fillSearchInput(focusedFlip.getItemName(), 0);
				foundSearch = true;
			}
			
			if (!foundSearch)
			{
				// Not in an input field - show info message
				log.info("No search widget found, showing info message");
				showInfoMessage(focusedFlip);
			}
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
	 * Set the current input field value.
	 * MUST be called on client thread.
	 */
	private void setInputValue(int value)
	{
		client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(value));
	}
	
	/**
	 * Fill search input with item name by simulating keyboard input.
	 * MUST be called on client thread.
	 */
	private void fillSearchInput(String itemName, int inputType)
	{
		log.info("Filling search input for inputType {}: {}", inputType, itemName);
		
		// Simulate typing each character into the search box
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			log.warn("Canvas is null, cannot simulate typing");
			return;
		}
		
		// Type each character
		for (char c : itemName.toCharArray())
		{
			// Create and dispatch keyTyped event for each character
			KeyEvent keyTyped = new KeyEvent(
				canvas,
				KeyEvent.KEY_TYPED,
				System.currentTimeMillis(),
				0,  // no modifiers
				KeyEvent.VK_UNDEFINED,
				c
			);
			canvas.dispatchEvent(keyTyped);
		}
		
		log.info("Simulated typing: {}", itemName);
		sendChatMessage("Search filled: " + itemName);
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
		int[] widgetGroups = {162, 163, 164, 217, 219, 229, 548, 161};
		
		for (int groupId : widgetGroups)
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
		int[][] parentWidgets = {{162, 0}, {162, 1}, {162, 5}, {162, 24}, {217, 0}, {217, 4}, {217, 5}, {217, 6}};
		for (int[] parent : parentWidgets)
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
		
		log.info("Could not determine input type from widgets or GE state, defaulting to quantity. " +
			"Enable debug logging to see widget scan results.");
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
		Widget quantityWidget = client.getWidget(465, 24);
		Widget priceWidget = client.getWidget(465, 25);
		
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
			log.info("GE quantity already set to {}, likely setting PRICE now", currentQuantity);
			return Boolean.TRUE;
		}
		
		// If quantity is still at default (1 or 0), user is probably setting quantity first
		log.info("GE quantity appears at default ({}), likely setting QUANTITY", currentQuantity);
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
			String numericOnly = text.replaceAll("[^0-9]", "");
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
				log.info("Detected PRICE input from {}: '{}'", source, text);
				return Boolean.TRUE;
			}
			// "How many do you wish to" = quantity input
			if (lowerText.contains("how many"))
			{
				log.info("Detected QUANTITY input from {}: '{}'", source, text);
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
		
		if (chatText != null && !chatText.isEmpty())
		{
			// Allow if we're in GE input (numeric, text, or chatbox search)
			int inputType = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
			if (inputType == 7 || inputType == 6 || inputType == 14)
			{
				return false; // Allow hotkey in GE inputs
			}
			
			// Also allow if GE is open (might be in search)
			if (isGrandExchangeOpen())
			{
				return false;
			}
			
			return true;
		}
		return false;
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
			"<col=ffaa00>[FlipSmart]</col> " + message,
			null
		);
	}
}
