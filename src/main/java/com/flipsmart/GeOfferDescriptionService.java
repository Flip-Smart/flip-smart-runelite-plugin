package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Replaces the GE buy/sell window description text with contextual FlipSmart
 * data (issue #665). Two surfaces:
 *
 * <h3>Setup screen</h3>
 * Intercepts the {@code geBuyExamineText} / {@code geSellExamineText} script
 * callbacks and writes our description into the runescript's return slot.
 *
 * <h3>In-flight Offer status panel</h3>
 * On each BeforeRender frame: write our description to both SETUP_DESC and
 * DETAILS_DESC (one of the two is the visible widget per earlier diagnosis,
 * depending on slot state). Short-circuits when widget text already matches
 * so the steady-state cost is one string-compare per frame.
 *
 * <h3>Direction (buy vs sell) is read from the UI, not the offer state</h3>
 * The OSRS slot tile widget (INDEX_0..INDEX_7) displays "Buy" or "Sell" text
 * — that's what the player actually sees on screen. Using offer.getState()
 * led to wrong-window mismatches because the script callback fires with the
 * same name (geBuyExamineText) for both panels regardless of direction, and
 * offer state can lag the visible UI. The UI text is the absolute source of
 * truth for what the player is looking at.
 */
@Slf4j
@Singleton
public class GeOfferDescriptionService
{
	static final String EVENT_BUY_EXAMINE = "geBuyExamineText";
	static final String EVENT_SELL_EXAMINE = "geSellExamineText";

	private static final int MAX_GE_SLOTS = 8;

	private final Client client;
	private final ClientThread clientThread;
	private final FlipSmartApiClient apiClient;
	private final FlipSmartPlugin plugin;
	private final ItemManager itemManager;
	private final FlipAssistOverlay flipAssistOverlay;

	@Inject
	public GeOfferDescriptionService(
		Client client,
		ClientThread clientThread,
		FlipSmartApiClient apiClient,
		FlipSmartPlugin plugin,
		ItemManager itemManager,
		FlipAssistOverlay flipAssistOverlay)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.apiClient = apiClient;
		this.plugin = plugin;
		this.itemManager = itemManager;
		this.flipAssistOverlay = flipAssistOverlay;
	}

	// ---------------------------------------------------------------------
	// Setup screen
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
		// Offer-status panel fires the same callback as setup. Skip when an
		// in-flight slot is selected — BeforeRender handles that screen.
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
	// Offer status panel — multi-write per frame, UI-based direction
	// ---------------------------------------------------------------------

	public void onBeforeRender(BeforeRender event)
	{
		// Only operate when the offer-status / setup panel is open.
		Widget setupDesc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		Widget detailsDesc = client.getWidget(InterfaceID.GeOffers.DETAILS_DESC);
		if (setupDesc == null && detailsDesc == null)
		{
			return;
		}

		// Source of truth #1: Flip Assist focus. When the player has picked a
		// recommendation in Flip Finder, this is the canonical itemId/direction
		// — no slot-state guessing required.
		FocusedFlip focus = flipAssistOverlay == null ? null : flipAssistOverlay.getFocusedFlip();

		int itemId;
		boolean isBuy;
		int price;
		int qty;
		String source;

		if (focus != null)
		{
			itemId = focus.getItemId();
			isBuy = focus.getStep() == FocusedFlip.FlipStep.BUY;
			price = isBuy ? focus.getBuyPrice() : focus.getSellPrice();
			qty = isBuy ? focus.getBuyQuantity() : focus.getSellQuantity();
			source = "focus";
		}
		else
		{
			// Source of truth #2: slot state. Lower confidence (data lookups
			// have sometimes returned stale values for certain itemIds), but
			// covers the common case where the player opens GE without an
			// active Flip Assist focus.
			int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
			if (slot < 0 || slot >= MAX_GE_SLOTS)
			{
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
			Boolean uiIsBuy = readSlotDirectionFromUi(slot);
			isBuy = uiIsBuy != null ? uiIsBuy : TrackedOffer.isBuyState(offer.getState());
			itemId = offer.getItemId();
			price = offer.getPrice();
			qty = offer.getTotalQuantity();
			source = "slot:" + slot;
		}

		String desired = isBuy
			? buildBuyDescription(itemId)
			: buildSellDescriptionStatic(itemId, price, qty);

		boolean wroteDetails = writeIfNeeded(InterfaceID.GeOffers.DETAILS_DESC, desired);
		boolean wroteSetup = writeIfNeeded(InterfaceID.GeOffers.SETUP_DESC, desired);
		if (wroteDetails || wroteSetup)
		{
			Integer rawBuyLimit = lookupBuyLimit(itemId);
			Integer rawWikiInstaBuy = lookupWikiInstaBuy(itemId);
			Integer rawDailyVolume = getCachedDailyVolume(itemId);
			log.debug("[GE desc] source={} itemId={} isBuy={} lookups: dailyVol={} buyLimit={} wikiInstaBuy={}",
				source, itemId, isBuy, rawDailyVolume, rawBuyLimit, rawWikiInstaBuy);
		}
		hideAndTransparent(InterfaceID.GeOffers.DETAILS_GRAPHIC4);
		hideAndTransparent(InterfaceID.GeOffers.DETAILS_FEE);
	}

	/** Reads the item-name text from the slot tile. Useful to cross-check slot indexing. */
	private String readSlotItemNameFromUi(int slot)
	{
		int indexWidgetId = InterfaceID.GeOffers.INDEX_0 + slot;
		Widget tile = client.getWidget(indexWidgetId);
		if (tile == null) return null;
		Widget[] dyn = tile.getDynamicChildren();
		if (dyn == null) return null;
		// Per earlier diagnostic dumps, dyn[19] is the item-name text. Find the
		// longest "name-shaped" string (not "Buy"/"Sell"/contains "coins") as
		// a robust fallback.
		String best = null;
		for (Widget child : dyn)
		{
			if (child == null) continue;
			String text = child.getText();
			if (text == null || text.isEmpty()) continue;
			if ("Buy".equals(text) || "Sell".equals(text)) continue;
			if (text.contains("coin")) continue;
			if (best == null || text.length() > best.length()) best = text;
		}
		return best;
	}

	// One-shot per-slot scan: find the visible widget that displays the
	// item-name text on the offer-status panel. That's the description
	// widget we actually need to target.
	private int findVisibleScanLastSlot = -999;
	private int findVisibleScanRemaining = 0;

	private void findVisibleDescriptionWidget(int slot, int itemId, String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return;
		}
		log.debug("[GE find] === searching for widget displaying \"{}\" on slot={} ===", itemName, slot);
		int hits = 0;
		// Scan groups 162 (main GE interface) and 465 (GeOffers). Walk both
		// top-level and one level of static + dynamic children.
		for (int group : new int[]{162, 465})
		{
			for (int child = 0; child < 100; child++)
			{
				int packedId = (group << 16) | child;
				Widget w = client.getWidget(packedId);
				if (w == null) continue;
				hits += scanWidgetForName(w, group, child, itemName);
			}
		}
		log.debug("[GE find] === scan done, hits={} ===", hits);
	}

	private int scanWidgetForName(Widget w, int group, int child, String itemName)
	{
		int hits = 0;
		String text = w.getText();
		if (text != null && text.contains(itemName))
		{
			log.debug("[GE find] HIT group={} child={} id={} hidden={} text=\"{}\"",
				group, child, w.getId(), w.isSelfHidden(),
				text.replace("\n", "\\n"));
			hits++;
		}
		Widget[] statics = w.getStaticChildren();
		if (statics != null)
		{
			for (int i = 0; i < statics.length && i < 50; i++)
			{
				Widget c = statics[i];
				if (c == null) continue;
				String ctext = c.getText();
				if (ctext != null && ctext.contains(itemName))
				{
					log.debug("[GE find] HIT group={} child={}.s{} id={} hidden={} text=\"{}\"",
						group, child, i, c.getId(), c.isSelfHidden(),
						ctext.replace("\n", "\\n"));
					hits++;
				}
			}
		}
		Widget[] dyn = w.getDynamicChildren();
		if (dyn != null)
		{
			for (int i = 0; i < dyn.length && i < 50; i++)
			{
				Widget c = dyn[i];
				if (c == null) continue;
				String ctext = c.getText();
				if (ctext != null && ctext.contains(itemName))
				{
					log.debug("[GE find] HIT group={} child={}.d{} id={} hidden={} text=\"{}\"",
						group, child, i, c.getId(), c.isSelfHidden(),
						ctext.replace("\n", "\\n"));
					hits++;
				}
			}
		}
		return hits;
	}

	/**
	 * Reads the buy/sell direction from the OSRS slot tile widget for the given
	 * slot. The tile displays "Buy" or "Sell" text in its first text child;
	 * returns {@code true} for buy, {@code false} for sell, {@code null} if
	 * the widget isn't loaded or doesn't have a matching label.
	 */
	private Boolean readSlotDirectionFromUi(int slot)
	{
		int indexWidgetId = InterfaceID.GeOffers.INDEX_0 + slot;
		Widget tile = client.getWidget(indexWidgetId);
		if (tile == null)
		{
			return null;
		}
		Widget[] dyn = tile.getDynamicChildren();
		if (dyn == null)
		{
			return null;
		}
		for (Widget child : dyn)
		{
			if (child == null) continue;
			String text = child.getText();
			if ("Buy".equals(text)) return Boolean.TRUE;
			if ("Sell".equals(text)) return Boolean.FALSE;
		}
		return null;
	}

	private boolean writeIfNeeded(int widgetId, String desired)
	{
		Widget w = client.getWidget(widgetId);
		if (w == null)
		{
			return false;
		}
		if (desired.equals(w.getText()))
		{
			return false;
		}
		w.setText(desired);
		return true;
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
