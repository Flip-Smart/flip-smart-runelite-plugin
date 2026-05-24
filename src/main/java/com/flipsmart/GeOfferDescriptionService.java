package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Replaces / augments the GE buy/sell window description text with contextual
 * FlipSmart data (issue #665). Covers two surfaces with two distinct
 * rendering strategies, both chosen so we never fight OSRS scripts.
 *
 * <h3>Setup screen (constructing a new offer)</h3>
 * Intercepts the RuneLite-injected {@code geBuyExamineText} /
 * {@code geSellExamineText} script callbacks and writes our description into
 * the runescript's return slot. The OSRS client then writes that value into
 * its own description widget. No race — we let the client own the widget,
 * we just override what string it renders.
 *
 * <h3>In-flight Offer status panel</h3>
 * Different approach: create our OWN child widget inside the offer-status
 * container via {@link Widget#createChild}. OSRS scripts only mutate widgets
 * they know about (their own); since we created this widget, it's invisible
 * to their redraw cycle. No race, no flicker, no constant fighting. This is
 * the same pattern Flipping Copilot uses for its chatbox suggestion widget.
 *
 * <p>This keeps the original OSRS examine text in place (e.g.,
 * "Small shiny scales.") and adds our breakeven / tax / profit lines as
 * additional rendered text alongside it.</p>
 */
@Slf4j
@Singleton
public class GeOfferDescriptionService
{
	static final String EVENT_BUY_EXAMINE = "geBuyExamineText";
	static final String EVENT_SELL_EXAMINE = "geSellExamineText";

	private static final int MAX_GE_SLOTS = 8;

	// Where to position our custom text widget inside the DETAILS container.
	// X = right of the item icon (~80px). Y = below the original examine
	// text area (~38px). Sized to fit the remaining width of the container.
	private static final int CUSTOM_TEXT_X = 80;
	private static final int CUSTOM_TEXT_Y = 38;
	private static final int CUSTOM_TEXT_HEIGHT = 90;
	private static final int CUSTOM_TEXT_RIGHT_PADDING = 10;
	private static final int CUSTOM_TEXT_COLOR_RGB = 0xFFB83F; // GE description amber

	private final Client client;
	private final ClientThread clientThread;
	private final FlipSmartApiClient apiClient;
	private final FlipSmartPlugin plugin;
	private final ItemManager itemManager;

	// Our owned-by-us text widget inside the offer-status DETAILS container.
	// Persists across frames once created; we re-check validity each frame
	// because the OSRS client recreates the container on certain transitions.
	private Widget detailsCustomText;

	@Inject
	public GeOfferDescriptionService(
		Client client,
		ClientThread clientThread,
		FlipSmartApiClient apiClient,
		FlipSmartPlugin plugin,
		ItemManager itemManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.apiClient = apiClient;
		this.plugin = plugin;
		this.itemManager = itemManager;
	}

	// ---------------------------------------------------------------------
	// Setup screen — script-callback override
	// ---------------------------------------------------------------------

	public boolean onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		String name = event.getEventName();
		if (EVENT_BUY_EXAMINE.equals(name))
		{
			handleExamine(true);
			return true;
		}
		if (EVENT_SELL_EXAMINE.equals(name))
		{
			handleExamine(false);
			return true;
		}
		return false;
	}

	private void handleExamine(boolean callbackIsBuy)
	{
		// Offer-status panel fires the SAME geBuyExamineText callback as the
		// setup screen. Skip the override for offer-status so we don't write
		// junk into the runescript return slot — the createChild path
		// (onBeforeRender) handles offer-status separately.
		int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
		if (slot >= 0 && slot < MAX_GE_SLOTS)
		{
			GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			if (offers != null && slot < offers.length
				&& offers[slot] != null
				&& offers[slot].getState() != GrandExchangeOfferState.EMPTY)
			{
				return;
			}
		}

		int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId <= 0)
		{
			return;
		}

		String replacement = callbackIsBuy ? buildBuyDescription(itemId) : buildSellDescription(itemId);
		if (replacement == null)
		{
			return;
		}

		Object[] stack = client.getObjectStack();
		int sz = client.getObjectStackSize();
		if (sz <= 0 || stack == null || stack.length < sz)
		{
			return;
		}
		stack[sz - 1] = replacement;
	}

	// ---------------------------------------------------------------------
	// Hook the plugin's existing GE_OFFERS_SETUP_BUILD handler for icon hide
	// ---------------------------------------------------------------------

	public void onScriptPostFired(ScriptPostFired event)
	{
		// no-op — reserved
	}

	public void onSetupBuildScriptPostFired()
	{
		hideAndTransparent(InterfaceID.GeOffers.SETUP_GRAPHIC4);
		hideAndTransparent(InterfaceID.GeOffers.SETUP_FEE);
	}

	// ---------------------------------------------------------------------
	// Offer status panel — createChild owned widget
	// ---------------------------------------------------------------------

	/**
	 * Fires once per visual frame. Ensures our owned text widget exists inside
	 * the DETAILS container, then keeps its text in sync with the currently
	 * viewed slot's offer state.
	 */
	public void onBeforeRender(BeforeRender event)
	{
		int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
		if (slot < 0 || slot >= MAX_GE_SLOTS)
		{
			return;
		}

		Widget detailsContainer = client.getWidget(InterfaceID.GeOffers.DETAILS);
		if (detailsContainer == null || detailsContainer.isHidden())
		{
			// Offer-status panel is not visible — leave our widget alone, it
			// will get garbage-collected with the container.
			detailsCustomText = null;
			return;
		}

		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null || slot >= offers.length)
		{
			return;
		}
		GrandExchangeOffer offer = offers[slot];
		if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			return;
		}

		int itemId = offer.getItemId();
		GrandExchangeOfferState state = offer.getState();
		boolean isBuy = TrackedOffer.isBuyState(state);
		String desired = isBuy
			? buildBuyDescription(itemId)
			: buildSellDescriptionStatic(itemId, offer.getPrice(), offer.getTotalQuantity());

		// Ensure our owned widget exists inside the DETAILS container. If the
		// container was recreated since we last cached the widget, our cached
		// reference is stale — re-create.
		if (detailsCustomText == null || !isChildOf(detailsContainer, detailsCustomText))
		{
			detailsCustomText = detailsContainer.createChild(-1, WidgetType.TEXT);
			configureCustomTextWidget(detailsContainer, detailsCustomText);
		}

		if (!desired.equals(detailsCustomText.getText()))
		{
			detailsCustomText.setText(desired);
		}
	}

	private boolean isChildOf(Widget container, Widget candidate)
	{
		Widget[] dyn = container.getDynamicChildren();
		if (dyn == null)
		{
			return false;
		}
		for (Widget w : dyn)
		{
			if (w == candidate)
			{
				return true;
			}
		}
		return false;
	}

	private void configureCustomTextWidget(Widget container, Widget w)
	{
		int containerWidth = container.getOriginalWidth();
		int textWidth = Math.max(160, containerWidth - CUSTOM_TEXT_X - CUSTOM_TEXT_RIGHT_PADDING);

		w.setFontId(FontID.PLAIN_11);
		w.setTextColor(CUSTOM_TEXT_COLOR_RGB);
		w.setTextShadowed(true);
		w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		w.setOriginalX(CUSTOM_TEXT_X);
		w.setOriginalY(CUSTOM_TEXT_Y);
		w.setOriginalWidth(textWidth);
		w.setOriginalHeight(CUSTOM_TEXT_HEIGHT);
		w.setWidthMode(WidgetSizeMode.ABSOLUTE);
		w.setHeightMode(WidgetSizeMode.ABSOLUTE);
		w.setXTextAlignment(WidgetTextAlignment.LEFT);
		w.setYTextAlignment(WidgetTextAlignment.TOP);
		w.setHasListener(false);
		w.revalidate();
	}

	// ---------------------------------------------------------------------
	// Description builders
	// ---------------------------------------------------------------------

	private String buildBuyDescription(int itemId)
	{
		Integer dailyVolume = getCachedDailyVolume(itemId);
		if (dailyVolume == null)
		{
			apiClient.getDailyVolumeAsync(itemId).whenComplete((v, ex) ->
			{
				if (ex != null)
				{
					log.debug("Failed to fetch daily volume for item {}: {}", itemId, ex.getMessage());
					return;
				}
				if (v != null)
				{
					refreshSetupBuyDescription(itemId);
				}
			});
		}

		Integer buyLimit = lookupBuyLimit(itemId);
		Integer wikiInstaBuy = lookupWikiInstaBuy(itemId);

		return GeOfferDescriptionFormatter.formatBuyDescription(dailyVolume, buyLimit, wikiInstaBuy);
	}

	/** Post-fetch repaint for SETUP_DESC when daily volume cache lands. */
	private void refreshSetupBuyDescription(int itemId)
	{
		clientThread.invoke(() ->
		{
			if (client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH) != itemId)
			{
				return;
			}
			Widget desc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
			if (desc == null)
			{
				return;
			}
			desc.setText(buildBuyDescription(itemId));
		});
	}

	private String buildSellDescription(int itemId)
	{
		Integer recordedBuyPrice = BuyPriceLookup.findAverageBuyPrice(plugin.getCurrentActiveFlips(), itemId);
		int sellPrice = client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE);
		int quantity = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
		if (sellPrice < 0) sellPrice = 0;
		if (quantity < 0) quantity = 0;
		return GeOfferDescriptionFormatter.formatSellDescription(recordedBuyPrice, sellPrice, quantity);
	}

	/**
	 * Sell description for an already-submitted offer — listed price + total
	 * quantity, both locked. Cannot reuse {@link #buildSellDescription} which
	 * reads live varbits that are only meaningful during setup.
	 */
	private String buildSellDescriptionStatic(int itemId, int listedPrice, int totalQuantity)
	{
		Integer recordedBuyPrice = BuyPriceLookup.findAverageBuyPrice(plugin.getCurrentActiveFlips(), itemId);
		int price = Math.max(listedPrice, 0);
		int qty = Math.max(totalQuantity, 0);
		return GeOfferDescriptionFormatter.formatSellDescription(recordedBuyPrice, price, qty);
	}

	private Integer lookupBuyLimit(int itemId)
	{
		try
		{
			ItemStats stats = itemManager.getItemStats(itemId);
			return (stats != null && stats.getGeLimit() > 0) ? stats.getGeLimit() : null;
		}
		catch (Exception e)
		{
			log.debug("Failed to read ItemStats for item {}: {}", itemId, e.getMessage());
			return null;
		}
	}

	private Integer lookupWikiInstaBuy(int itemId)
	{
		FlipSmartApiClient.WikiPrice price = apiClient.getWikiPrice(itemId);
		return (price != null && price.instaBuy > 0) ? price.instaBuy : null;
	}

	private Integer getCachedDailyVolume(int itemId)
	{
		java.util.concurrent.CompletableFuture<Integer> f = apiClient.peekCachedDailyVolume(itemId);
		return (f != null && f.isDone()) ? f.getNow(null) : null;
	}

	private void hideAndTransparent(int widgetId)
	{
		Widget w = client.getWidget(widgetId);
		if (w == null)
		{
			return;
		}
		w.setHidden(true);
		w.setOpacity(255);
	}
}
