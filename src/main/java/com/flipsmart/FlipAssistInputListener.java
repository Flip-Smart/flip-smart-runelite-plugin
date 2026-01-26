package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyListener;

import javax.inject.Inject;
import java.awt.Canvas;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Key listener for the Flip Assist feature.
 * Handles the hotkey press to auto-fill price and quantity in the GE.
 */
@Slf4j
public class FlipAssistInputListener implements KeyListener
{
	private final Client client;
	private final ClientThread clientThread;
	private final FlipSmartConfig config;
	private final FlipAssistOverlay flipAssistOverlay;
	
	// GE Interface group IDs
	private static final int GE_INTERFACE_GROUP = 465;
	private static final int GE_OFFER_GROUP = 162;
	
	// GE Interface child IDs
	private static final int GE_QUANTITY_CHILD = 24;
	
	// VarClient IDs (raw values to avoid deprecated API)
	private static final int VARCLIENT_INPUT_TYPE = 5;
	private static final int VARCLIENT_INPUT_TEXT = 359;
	
	// Input type values
	private static final int INPUT_TYPE_NUMERIC = 7;
	private static final int INPUT_TYPE_GE_ITEM_SEARCH = 14;
	
	// Chat message prefix - cyan color for visibility
	private static final String CHAT_MESSAGE_PREFIX = "<col=00e5ff>[FlipSmart]</col> ";
	
	// Widget groups to scan for price/quantity dialog title
	private static final int[] CHATBOX_WIDGET_GROUPS = {162, 163, 164, 217, 219, 229, 548, 161};
	
	// Parent widgets to check for static/dynamic children with price/quantity text
	private static final int[][] CHATBOX_PARENT_WIDGETS = {
		{162, 0}, {162, 1}, {162, 5}, {162, 24},
		{217, 0}, {217, 4}, {217, 5}, {217, 6}
	};
	
	@Inject
	public FlipAssistInputListener(Client client, ClientThread clientThread, FlipSmartConfig config, FlipAssistOverlay flipAssistOverlay)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.flipAssistOverlay = flipAssistOverlay;
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
		// Don't process hotkeys if Flip Assist is disabled
		if (!config.enableFlipAssistant())
		{
			return;
		}
		
		// Check if the hotkey matches
		Keybind hotkey = config.flipAssistHotkey();
		if (!hotkey.matches(e))
		{
			return;
		}
		
		// Don't trigger if no focused flip (this doesn't need client thread)
		FocusedFlip focusedFlip = flipAssistOverlay.getFocusedFlip();
		if (focusedFlip == null)
		{
			return;
		}
		
		// Use synchronous invoke to check conditions BEFORE consuming the event
		clientThread.invoke(() -> {
			// Check if GE is open (must be on client thread)
			if (!isGrandExchangeOpen())
			{
				return;
			}

			int inputType = client.getVarcIntValue(VARCLIENT_INPUT_TYPE);

			// Handle GE item search - press hotkey to select the first result
			// (Item name is auto-populated via GE_LAST_SEARCHED when flip is focused)
			if (inputType == INPUT_TYPE_GE_ITEM_SEARCH)
			{
				// Only auto-select if the search text matches the focused item
				// This prevents the hotkey from interfering when user is manually typing
				if (!isSearchTextMatchingFocusedItem(focusedFlip))
				{
					return;
				}

				if (hasSearchResults())
				{
					handledKeyPressedEvent.set(e);
					e.consume();
					selectFirstSearchResult();
					flipAssistOverlay.updateStep();
				}
				return;
			}
			
			// Handle numeric input (price/quantity)
			if (inputType == INPUT_TYPE_NUMERIC)
			{
				handledKeyPressedEvent.set(e);
				e.consume();
				handleFlipAssistAction(focusedFlip);
				flipAssistOverlay.updateStep();
			}
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
	 * Handle the Flip Assist action - auto-fill price/quantity in GE.
	 * Called only when in a numeric input dialog.
	 * MUST be called on client thread.
	 */
	private void handleFlipAssistAction(FocusedFlip focusedFlip)
	{
		// Determine if it's price or quantity based on context
		if (isLikelyPriceInput())
		{
			setInputValue(focusedFlip.getCurrentStepPrice());
			sendChatMessage(focusedFlip.getItemName() + " price set to " + String.format("%,d", focusedFlip.getCurrentStepPrice()) + " gp");
		}
		else
		{
			setInputValue(focusedFlip.getCurrentStepQuantity());
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
		client.setVarcStrValue(VARCLIENT_INPUT_TEXT, valueStr);
		
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
				Optional<Boolean> result = checkWidgetForPriceKeyword(groupId, childId);
				if (result.isPresent())
				{
					return result.get();
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
				Optional<Boolean> staticResult = checkChildrenForPriceKeyword(parentWidget.getStaticChildren());
				if (staticResult.isPresent())
				{
					return staticResult.get();
				}
				
				// Check dynamic children
				Optional<Boolean> dynamicResult = checkChildrenForPriceKeyword(parentWidget.getDynamicChildren());
				if (dynamicResult.isPresent())
				{
					return dynamicResult.get();
				}
				
				// Check nested children
				Optional<Boolean> nestedResult = checkChildrenForPriceKeyword(parentWidget.getNestedChildren());
				if (nestedResult.isPresent())
				{
					return nestedResult.get();
				}
			}
		}
		
		// Fallback: Check the GE interface state to determine what we're setting
		// If quantity field has a non-default value, we're probably setting price
		return determineFromGEState();
	}
	
	/**
	 * Check an array of widget children for price/quantity keywords.
	 */
	private Optional<Boolean> checkChildrenForPriceKeyword(Widget[] children)
	{
		if (children == null)
		{
			return Optional.empty();
		}
		for (Widget child : children)
		{
			if (child != null && !child.isHidden())
			{
				Optional<Boolean> result = checkTextForPriceKeyword(child.getText());
				if (result.isPresent())
				{
					return result;
				}
			}
		}
		return Optional.empty();
	}
	
	/**
	 * Try to determine price vs quantity input based on GE interface state.
	 * If quantity is already set to a non-default value, we're likely setting price.
	 * @return true if likely price input, false if likely quantity input
	 */
	private boolean determineFromGEState()
	{
		// GE offer setup widget children for quantity and price displays
		Widget quantityWidget = client.getWidget(GE_INTERFACE_GROUP, GE_QUANTITY_CHILD);
		
		String quantityText = (quantityWidget != null && !quantityWidget.isHidden()) ? quantityWidget.getText() : null;
		
		// Parse quantity from text (e.g., "6,000" -> 6000)
		int currentQuantity = parseNumberFromText(quantityText);
		
		// If quantity has been set (> 1, since default is often 1) and we're in an input,
		// user is probably now setting price
		return currentQuantity > 1;
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
	 * Returns Optional containing true if price, false if quantity, empty if no match.
	 */
	private Optional<Boolean> checkWidgetForPriceKeyword(int groupId, int childId)
	{
		Widget widget = client.getWidget(groupId, childId);
		if (widget != null && !widget.isHidden())
		{
			return checkTextForPriceKeyword(widget.getText());
		}
		return Optional.empty();
	}
	
	/**
	 * Check text for price/quantity keywords.
	 * Returns Optional containing true if price, false if quantity, empty if no match.
	 */
	private Optional<Boolean> checkTextForPriceKeyword(String text)
	{
		if (text != null && !text.isEmpty())
		{
			String lowerText = text.toLowerCase();
			// "Set a price for each item" = price input
			if (lowerText.contains("price") && !lowerText.contains("price:"))
			{
				return Optional.of(Boolean.TRUE);
			}
			// "How many do you wish to" = quantity input
			if (lowerText.contains("how many"))
			{
				return Optional.of(Boolean.FALSE);
			}
		}
		return Optional.empty();
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
	 * Check if the current search text matches the focused item name.
	 * This prevents the hotkey from triggering while the user is manually typing.
	 * MUST be called on client thread.
	 */
	private boolean isSearchTextMatchingFocusedItem(FocusedFlip focusedFlip)
	{
		String searchText = client.getVarcStrValue(VARCLIENT_INPUT_TEXT);
		if (searchText == null || searchText.isEmpty())
		{
			// Empty search box - allow hotkey (item will be auto-populated)
			return true;
		}

		String itemName = focusedFlip.getItemName();
		if (itemName == null)
		{
			return false;
		}

		// Normalize both strings for comparison (case-insensitive)
		String normalizedSearch = searchText.toLowerCase().trim();
		String normalizedItem = itemName.toLowerCase().trim();

		// Match if search text equals the item name or is a prefix of it
		// This allows the hotkey to work when GE_LAST_SEARCHED auto-populates the search
		return normalizedItem.equals(normalizedSearch) || normalizedItem.startsWith(normalizedSearch);
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
			return;
		}
		
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

