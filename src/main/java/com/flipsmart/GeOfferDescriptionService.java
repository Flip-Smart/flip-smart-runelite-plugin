package com.flipsmart;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.util.BuyPriceLookup;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashSet;
import java.util.Set;

/**
 * Replaces the GE buy/sell window description text with contextual FlipSmart
 * data (issue #665). Operates on two surfaces:
 *
 * <ul>
 *   <li><b>Setup screen</b> (placing a new offer) — intercepts the
 *       {@code geBuyExamineText} / {@code geSellExamineText} script callbacks
 *       and writes our description into the runescript return slot.</li>
 *   <li><b>Offer status panel</b> (in-flight offer) — on each BeforeRender
 *       frame writes our description to both SETUP_DESC and DETAILS_DESC,
 *       short-circuiting when the text already matches.</li>
 * </ul>
 *
 * Direction (buy vs sell) is read from the slot tile widget ("Buy"/"Sell"
 * text) — what the player actually sees on screen — because offer.getState()
 * can lag the UI during transitions.
 */
@Slf4j
@Singleton
public class GeOfferDescriptionService
{
	static final String EVENT_BUY_EXAMINE = "geBuyExamineText";
	static final String EVENT_SELL_EXAMINE = "geSellExamineText";

	private static final int MAX_GE_SLOTS = 8;

	// GeOffers.INDEX_0 = group 465 child 7. INDEX_N is child 7+N.
	private static final int GE_OFFERS_GROUP = 465;
	private static final int INDEX_0_CHILD = 7;

	// DETAILS_DESC sits 10px further left than SETUP_DESC in Jagex's
	// hand-tuned layout, exposing a parent-background strip between the icon
	// column and our text. Shift left to close the gap. Tracked per-widget so
	// the mutation only fires once per panel build.
	private static final int DETAILS_DESC_LEFT_SHIFT_PX = 10;
	private final Set<Integer> shiftedDescIds = new HashSet<>();

	// Slot the user explicitly clicked to open the offer-status panel.
	// GE_SELECTEDSLOT tracks the *hovered* slot tile rather than the open
	// panel, so we capture clicks via onMenuOptionClicked instead.
	// -1 = no click recorded yet (cold start).
	private int lastClickedSlot = -1;

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
	// Setup screen — script-callback driven
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
		// Skip while the offer-status (in-flight) panel is visible — that
		// surface is owned by onBeforeRender. Without this gate, the script
		// callback fires with a stale TRADINGPOST_SEARCH itemId during slot
		// transitions and bleeds the previous item's text onto the new panel.
		if (isDetailsContainerVisible())
		{
			return;
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

		writeToObjectStack(replacement);
	}

	private boolean isDetailsContainerVisible()
	{
		Widget detailsContainer = client.getWidget(InterfaceID.GeOffers.DETAILS);
		return detailsContainer != null && !detailsContainer.isHidden();
	}

	private void writeToObjectStack(String text)
	{
		Object[] stack = client.getObjectStack();
		int sz = client.getObjectStackSize();
		if (sz <= 0 || stack == null || stack.length < sz)
		{
			return;
		}
		stack[sz - 1] = text;
	}

	public void onSetupBuildScriptPostFired()
	{
		hideAndTransparent(InterfaceID.GeOffers.SETUP_GRAPHIC4);
		hideAndTransparent(InterfaceID.GeOffers.SETUP_FEE);
	}

	// ---------------------------------------------------------------------
	// Click capture — authoritative source for the open-panel slot
	// ---------------------------------------------------------------------

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		int widgetId = event.getParam1();
		if ((widgetId >>> 16) != GE_OFFERS_GROUP)
		{
			return;
		}
		int slot = (widgetId & 0xFFFF) - INDEX_0_CHILD;
		if (slot >= 0 && slot < MAX_GE_SLOTS)
		{
			lastClickedSlot = slot;
		}
	}

	// ---------------------------------------------------------------------
	// Offer status panel — per-frame
	// ---------------------------------------------------------------------

	public void onBeforeRender(BeforeRender event)
	{
		Widget setupDesc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		Widget detailsDesc = client.getWidget(InterfaceID.GeOffers.DETAILS_DESC);
		if (setupDesc == null && detailsDesc == null)
		{
			return;
		}

		int[] ctx = resolveOfferContext();
		if (ctx == null)
		{
			return;
		}

		// ctx: [itemId, isBuy (0/1), price, qty]
		int itemId = ctx[0];
		boolean isBuy = ctx[1] == 1;
		int price = ctx[2];
		int qty = ctx[3];

		String desired = isBuy
			? buildBuyDescription(itemId)
			: buildSellDescriptionStatic(itemId, price, qty);

		writeIfNeeded(InterfaceID.GeOffers.DETAILS_DESC, desired, DETAILS_DESC_LEFT_SHIFT_PX);
		writeIfNeeded(InterfaceID.GeOffers.SETUP_DESC, desired, 0);
		hideAndTransparent(InterfaceID.GeOffers.DETAILS_GRAPHIC4);
		hideAndTransparent(InterfaceID.GeOffers.DETAILS_FEE);
	}

	/**
	 * Resolves the offer context (itemId, direction, price, quantity) from one of
	 * three sources, in priority order: Flip Assist focus, the GE slot the player
	 * clicked, or the live "Set up offer" screen state.
	 *
	 * @return int[]{itemId, isBuy (1=buy/0=sell), price, qty}, or {@code null} if
	 *         no actionable context can be determined.
	 */
	int[] resolveOfferContext()
	{
		// Source #1: Flip Assist focus. When the player has picked a recommendation
		// this carries the richest context (recommended price/qty), but focus tracks
		// the plugin's *next recommended action* — which is routinely a different item
		// than the panel currently open. Only trust it when it agrees with the item
		// actually on screen; otherwise the focused item's wiki price/volume bleeds
		// onto an unrelated offer panel while 2+ flips are active. When no
		// on-screen item can be determined yet (e.g. a fresh buy before an item is
		// picked), focus is still the best available signal.
		FocusedFlip focus = flipAssistOverlay == null ? null : flipAssistOverlay.getFocusedFlip();
		if (focus != null)
		{
			Integer onScreenItemId = resolveOnScreenItemId();
			if (onScreenItemId == null || onScreenItemId == focus.getItemId())
			{
				boolean isBuy = focus.getStep() == FocusedFlip.FlipStep.BUY;
				int price = isBuy ? focus.getBuyPrice() : focus.getSellPrice();
				int qty = isBuy ? focus.getBuyQuantity() : focus.getSellQuantity();
				return new int[]{focus.getItemId(), isBuy ? 1 : 0, price, qty};
			}
		}

		// Source #2: the slot the player clicked. GE_SELECTEDSLOT is
		// only used as a cold-start fallback (haven't seen a click yet).
		int[] slotCtx = resolveSlotContext();
		if (slotCtx != null)
		{
			return slotCtx;
		}

		// Source #3: live "Set up offer" screen state (issue #684). Covers ad-hoc
		// buy/sell offers being constructed without a Flip Assist focus — the
		// slot is still EMPTY pre-confirm so #2 returns null, and the
		// geBuyExamineText / geSellExamineText script callbacks don't fire on
		// the current Jagex setup window, leaving SETUP_DESC un-replaced.
		return resolveSetupWindowContext();
	}

	/**
	 * The item id the open GE panel is actually showing, independent of Flip Assist
	 * focus: the committed offer on the active slot (in-flight status panel) if any,
	 * else the item selected in the "Set up offer" window. Returns {@code null} when
	 * neither surface exposes an item yet — the caller then treats focus as
	 * authoritative.
	 */
	private Integer resolveOnScreenItemId()
	{
		int slot = resolveActiveSlot();
		if (slot >= 0)
		{
			GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			if (offers != null && slot < offers.length)
			{
				GrandExchangeOffer offer = offers[slot];
				if (offer != null && offer.getState() != GrandExchangeOfferState.EMPTY)
				{
					return offer.getItemId();
				}
			}
		}

		int[] setupCtx = resolveSetupWindowContext();
		return setupCtx == null ? null : setupCtx[0];
	}

	/**
	 * Reads the current "Set up offer" screen state directly from varbits/varps.
	 * Returns {@code null} when the setup window isn't open or no offer is being
	 * built. Direction encoding: {@code GE_NEWOFFER_TYPE == 1} → sell, anything
	 * else non-zero → buy.
	 */
	private int[] resolveSetupWindowContext()
	{
		Widget setupDesc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		if (setupDesc == null || setupDesc.isHidden())
		{
			return null;
		}

		int offerType = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE);
		if (offerType == 0)
		{
			return null;
		}

		int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId <= 0)
		{
			return null;
		}

		boolean isBuy = offerType != 1;
		int price = Math.max(client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE), 0);
		int qty = Math.max(client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY), 0);
		return new int[]{itemId, isBuy ? 1 : 0, price, qty};
	}

	private int[] resolveSlotContext()
	{
		int slot = resolveActiveSlot();
		if (slot < 0)
		{
			return null;
		}
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null || slot >= offers.length)
		{
			return null;
		}
		GrandExchangeOffer offer = offers[slot];
		if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			return null;
		}
		Boolean uiIsBuy = readSlotDirectionFromUi(slot);
		boolean isBuy = uiIsBuy != null ? uiIsBuy : OfferSignal.isBuyState(offer.getState());
		return new int[]{offer.getItemId(), isBuy ? 1 : 0, offer.getPrice(), offer.getTotalQuantity()};
	}

	/**
	 * Returns the GE slot index to use for the offer-status panel. Prefers the
	 * explicitly clicked slot (\`lastClickedSlot\`); falls back to the
	 * \`GE_SELECTEDSLOT\` varbit on cold start. Returns -1 when no valid slot is
	 * determinable.
	 */
	private int resolveActiveSlot()
	{
		if (lastClickedSlot >= 0)
		{
			return lastClickedSlot;
		}
		int varbitSlot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
		return (varbitSlot >= 0 && varbitSlot < MAX_GE_SLOTS) ? varbitSlot : -1;
	}

	/**
	 * Returns {@code true} if the slot tile widget shows "Buy", {@code false}
	 * for "Sell", {@code null} if the widget isn't loaded or has neither label.
	 */
	private Boolean readSlotDirectionFromUi(int slot)
	{
		Widget tile = client.getWidget(InterfaceID.GeOffers.INDEX_0 + slot);
		if (tile == null) return null;
		Widget[] dyn = tile.getDynamicChildren();
		if (dyn == null) return null;
		for (Widget child : dyn)
		{
			if (child == null) continue;
			String text = child.getText();
			if ("Buy".equals(text)) return Boolean.TRUE;
			if ("Sell".equals(text)) return Boolean.FALSE;
		}
		return null;
	}

	private void writeIfNeeded(int widgetId, String desired, int shiftLeftPx)
	{
		Widget w = client.getWidget(widgetId);
		if (w == null)
		{
			return;
		}
		if (w.getXTextAlignment() != WidgetTextAlignment.LEFT)
		{
			w.setXTextAlignment(WidgetTextAlignment.LEFT);
		}
		if (shiftLeftPx > 0 && shiftedDescIds.add(widgetId))
		{
			w.setOriginalX(w.getOriginalX() - shiftLeftPx);
			w.setOriginalWidth(w.getOriginalWidth() + shiftLeftPx);
			w.revalidate();
		}
		if (!desired.equals(w.getText()))
		{
			w.setText(desired);
		}
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
				if (ex == null && v != null)
				{
					refreshSetupBuyDescription(itemId);
				}
			});
		}

		return GeOfferDescriptionFormatter.formatBuyDescription(
			dailyVolume, lookupBuyLimit(itemId), lookupWikiInstaBuy(itemId));
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
			if (desc != null)
			{
				desc.setText(buildBuyDescription(itemId));
			}
		});
	}

	private String buildSellDescription(int itemId)
	{
		Integer recordedBuyPrice = BuyPriceLookup.findAverageBuyPrice(plugin.getCurrentActiveFlips(), itemId);
		int sellPrice = Math.max(client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE), 0);
		int quantity = Math.max(client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY), 0);
		return GeOfferDescriptionFormatter.formatSellDescription(itemId, recordedBuyPrice, sellPrice, quantity);
	}

	private String buildSellDescriptionStatic(int itemId, int listedPrice, int totalQuantity)
	{
		Integer recordedBuyPrice = BuyPriceLookup.findAverageBuyPrice(plugin.getCurrentActiveFlips(), itemId);
		return GeOfferDescriptionFormatter.formatSellDescription(
			itemId, recordedBuyPrice, Math.max(listedPrice, 0), Math.max(totalQuantity, 0));
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
			return null;
		}
	}

	private Integer lookupWikiInstaBuy(int itemId)
	{
		WikiPrice price = apiClient.getWikiPrice(itemId);
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
		if (w == null) return;
		w.setHidden(true);
		w.setOpacity(255);
	}
}
