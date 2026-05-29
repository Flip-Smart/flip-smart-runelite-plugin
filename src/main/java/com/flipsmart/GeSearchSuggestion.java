package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

/**
 * Injects a clickable "FlipSmart item" shortcut row at the top of the Grand
 * Exchange item-search results, pointing at the focused buy recommendation.
 *
 * <p>The plugin attaches the game's own search-select listener to the row and
 * gives it a "Select" action, but never invokes it — <strong>the player clicks
 * the row</strong> to select the item. This mirrors the vanilla "previous
 * search" shortcut and the mechanism used by the approved Flipping Copilot
 * plugin ({@code GePreviousSearch}). The RuneLite Plugin Hub forbids selecting
 * or clicking an item on the player's behalf, so we only set up the shortcut
 * and let the player trigger it.
 */
@Slf4j
class GeSearchSuggestion
{
	// GE search-results container == InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS
	// == ComponentID.CHATBOX_GE_SEARCH_RESULTS.
	private static final int CHATBOX_GE_SEARCH_RESULTS = 10616884;

	// The vanilla GE "select search result" client script and the op/key trigger
	// args the game attaches to each real result row. We attach the same listener
	// to our row so a player click runs the game's own selection logic — we never
	// run it ourselves.
	private static final int GE_SELECT_SCRIPT = 754;
	private static final int GE_SELECT_OP = 84;
	private static final int GE_SELECT_KEY = -2147483640;

	// Orange used by the vanilla "previous search" item name.
	private static final String NAME_COLOR = "ff9040";

	// Result-row child indices in the search-results container.
	private static final int ROW_CHILD = 0;
	private static final int LABEL_CHILD = 1;
	private static final int NAME_CHILD = 2;
	private static final int ICON_CHILD = 3;

	private static final int FONT_ID = 495;

	private final Client client;
	private final FlipSmartConfig config;
	private final FlipAssistOverlay flipAssistOverlay;

	GeSearchSuggestion(Client client, FlipSmartConfig config, FlipAssistOverlay flipAssistOverlay)
	{
		this.client = client;
		this.config = config;
		this.flipAssistOverlay = flipAssistOverlay;
	}

	/**
	 * Inject or refresh the suggested-item shortcut row for the focused buy.
	 * No-op unless Flip Assist is enabled, a buy is focused, and the GE search
	 * results widget is present. MUST be called on the client thread.
	 */
	void showSuggestedItemInSearch()
	{
		if (!config.enableFlipAssistant())
		{
			return;
		}

		FocusedFlip focusedFlip = flipAssistOverlay.getFocusedFlip();
		if (focusedFlip == null || !focusedFlip.isBuying())
		{
			log.debug("GE search opened but no focused buy — skipping row injection (focus={})", focusedFlip);
			return;
		}

		String itemName = focusedFlip.getItemName();
		if (itemName == null || itemName.isEmpty())
		{
			return;
		}

		Widget searchResults = client.getWidget(CHATBOX_GE_SEARCH_RESULTS);
		if (searchResults == null)
		{
			log.debug("GE search opened but results widget not present yet — skipping row injection");
			return;
		}

		int itemId = focusedFlip.getItemId();

		// When the game already shows a "previous search" shortcut row, repurpose
		// its existing children; otherwise build our own. Mirrors Flipping Copilot.
		if (previousSearchRowVisible())
		{
			log.debug("Injecting FlipSmart search row (repurpose previous-search) for {} (id {})", itemName, itemId);
			repurposePreviousSearchRow(searchResults, itemId, itemName);
		}
		else
		{
			log.debug("Injecting FlipSmart search row (create new) for {} (id {})", itemName, itemId);
			createSuggestionRow(searchResults, itemId, itemName);
		}
	}

	private boolean previousSearchRowVisible()
	{
		return client.getVarpValue(VarPlayerID.GE_LAST_SEARCHED) != -1
			&& client.getVarbitValue(VarbitID.DISABLE_LAST_SEARCHED) == 0;
	}

	private void repurposePreviousSearchRow(Widget searchResults, int itemId, String itemName)
	{
		Widget row = searchResults.getChild(ROW_CHILD);
		Widget label = searchResults.getChild(LABEL_CHILD);
		Widget nameWidget = searchResults.getChild(NAME_CHILD);
		Widget icon = searchResults.getChild(ICON_CHILD);
		if (row == null || label == null || nameWidget == null || icon == null)
		{
			return;
		}

		applySelectListener(row, itemId, itemName);

		label.setText("FlipSmart item:");
		label.setOriginalWidth(95);
		label.setXTextAlignment(WidgetTextAlignment.LEFT);
		label.revalidate();

		nameWidget.setText(itemName);
		nameWidget.revalidate();

		icon.setItemId(itemId);
		icon.revalidate();
	}

	private void createSuggestionRow(Widget searchResults, int itemId, String itemName)
	{
		Widget row = searchResults.createChild(ROW_CHILD, WidgetType.RECTANGLE);
		row.setTextColor(0xFFFFFF);
		row.setOpacity(255);
		row.setFilled(true);
		row.setOriginalX(114);
		row.setOriginalY(0);
		row.setOriginalWidth(256);
		row.setOriginalHeight(32);
		applySelectListener(row, itemId, itemName);
		// Subtle hover feedback so it reads as clickable.
		row.setOnMouseOverListener((JavaScriptCallback) ev -> row.setOpacity(200));
		row.setOnMouseLeaveListener((JavaScriptCallback) ev -> row.setOpacity(255));
		row.revalidate();

		Widget label = searchResults.createChild(LABEL_CHILD, WidgetType.TEXT);
		label.setText("FlipSmart item:");
		label.setFontId(FONT_ID);
		label.setOriginalX(114);
		label.setOriginalY(0);
		label.setOriginalWidth(95);
		label.setOriginalHeight(32);
		label.setYTextAlignment(WidgetTextAlignment.CENTER);
		label.revalidate();

		Widget nameWidget = searchResults.createChild(NAME_CHILD, WidgetType.TEXT);
		nameWidget.setText(itemName);
		nameWidget.setFontId(FONT_ID);
		nameWidget.setOriginalX(254);
		nameWidget.setOriginalY(0);
		nameWidget.setOriginalWidth(116);
		nameWidget.setOriginalHeight(32);
		nameWidget.setYTextAlignment(WidgetTextAlignment.CENTER);
		nameWidget.revalidate();

		Widget icon = searchResults.createChild(ICON_CHILD, WidgetType.GRAPHIC);
		icon.setItemId(itemId);
		icon.setItemQuantity(1);
		icon.setItemQuantityMode(0);
		icon.setRotationX(550);
		icon.setModelZoom(1031);
		icon.setBorderType(1);
		icon.setOriginalX(214);
		icon.setOriginalY(0);
		icon.setOriginalWidth(36);
		icon.setOriginalHeight(32);
		icon.revalidate();
	}

	/**
	 * Attach the game's native select listener and a "Select" right-click action.
	 * The plugin never invokes this — only the player's click does.
	 */
	private void applySelectListener(Widget row, int itemId, String itemName)
	{
		row.setHasListener(true);
		row.setName("<col=" + NAME_COLOR + ">" + itemName + "</col>");
		row.setOnOpListener(GE_SELECT_SCRIPT, itemId, GE_SELECT_OP);
		row.setOnKeyListener(GE_SELECT_SCRIPT, itemId, GE_SELECT_KEY);
		row.setAction(0, "Select");
	}
}
