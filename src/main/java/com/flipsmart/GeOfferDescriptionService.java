package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
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
 * data (issue #665). Covers two surfaces:
 *
 * <ul>
 *   <li><b>Set up offer screen</b> ({@code SETUP_DESC}): handled via the
 *   RuneLite-injected {@code geBuyExamineText} / {@code geSellExamineText}
 *   script callbacks ({@code GeExamineInfoText.rs2asm}, script id 5730).
 *   Writing into the object stack's return slot lets the client own the
 *   widget mutation. No race, no flicker.</li>
 *
 *   <li><b>Offer status screen</b> ({@code DETAILS_DESC}): no script callback
 *   exists. We mutate the widget directly after the OSRS GE-slot redraw
 *   scripts fire (IDs 782 and 804 per Flipping-Utilities prior art) and on
 *   {@code GE_SELECTEDSLOT} varbit changes. Both hooks coalesce into the same
 *   idempotent {@link #refreshDetailsFor} method — re-firing is cheap.</li>
 * </ul>
 *
 * <p>Cold-cache repaint: when the daily-volume cache misses on the first
 * render for an item, the synchronous render writes "N/A" and kicks off the
 * async fetch. When the fetch lands we try to repaint both SETUP_DESC and
 * DETAILS_DESC — the player can only be on one of the two screens, the
 * other path's guards skip silently.</p>
 *
 * <p>AC9 (no paint overlay) is satisfied by construction — all rendering
 * goes through widget text mutation, never an overlay.</p>
 */
@Slf4j
@Singleton
public class GeOfferDescriptionService
{
	static final String EVENT_BUY_EXAMINE = "geBuyExamineText";
	static final String EVENT_SELL_EXAMINE = "geSellExamineText";

	private static final int MAX_GE_SLOTS = 8;

	// Empirically these OSRS scripts reset GE slot / details widgets and so
	// overwrite our setText if we apply it before they fire. Identified by
	// Flipping-Utilities as the GE slot-state-drawer redraw triggers. We
	// re-apply our setText AFTER each fires.
	private static final int SCRIPT_GE_SLOT_REDRAW_A = 782;
	private static final int SCRIPT_GE_SLOT_REDRAW_B = 804;

	private final Client client;
	private final ClientThread clientThread;
	private final FlipSmartApiClient apiClient;
	private final FlipSmartPlugin plugin;
	private final ItemManager itemManager;

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

	/**
	 * Handle a {@link ScriptCallbackEvent} for the setup-screen description.
	 * Returns true when the event was a GE examine callback (handled regardless
	 * of whether we wrote a replacement); false when the event was unrelated.
	 */
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

	/**
	 * Handle VarbitChanged for GE_SELECTEDSLOT — fires immediately when the
	 * player clicks an in-flight slot. Filters internally; harmless to call
	 * with any varbit event.
	 */
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() != VarbitID.GE_SELECTEDSLOT)
		{
			return;
		}
		int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
		if (slot < 0 || slot >= MAX_GE_SLOTS)
		{
			return;
		}
		refreshDetailsFor(slot, "varbit");
	}

	/**
	 * Handle ScriptPostFired for the OSRS GE slot/details redraw scripts.
	 * These are what overwrite our VarbitChanged-driven setText if we apply
	 * it too early. Re-applying after each script fire ensures we win the
	 * race on every render cycle.
	 */
	public void onScriptPostFired(ScriptPostFired event)
	{
		// Any script firing while DETAILS_DESC is alive can overwrite our text.
		// Rather than enumerate the long tail of redraw scripts (per the earlier
		// diag dump: 98, 6388, 811, 1972, 4731/4730, 4672/4671, 5939, 998,
		// 2513/2512, 1004, 3351/3350, 191, ...), repaint after ANY script while
		// DETAILS_DESC exists. Idempotent — setText with the same string is a
		// no-op visually.
		int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
		if (slot < 0 || slot >= MAX_GE_SLOTS)
		{
			return;
		}
		Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS_DESC);
		if (details == null)
		{
			return;
		}
		refreshDetailsFor(slot, "script:" + event.getScriptId());
	}

	/**
	 * Called by the plugin's existing onScriptPostFired handler after a
	 * GE_OFFERS_SETUP_BUILD has fired. Makes the convenience-fee info icon
	 * (SETUP_GRAPHIC4) invisible — our description already shows the tax
	 * explicitly so the icon is redundant, AND the runescript positions
	 * it based on the original (now-replaced) text metrics, which makes
	 * it overlap our multi-line content.
	 *
	 * <p>setHidden() alone did not stick (likely overridden by a later
	 * widget-config script) — combine hidden + fully-transparent opacity
	 * for belt-and-suspenders invisibility.</p>
	 */
	public void onSetupBuildScriptPostFired()
	{
		hideAndTransparent(InterfaceID.GeOffers.SETUP_GRAPHIC4);
		hideAndTransparent(InterfaceID.GeOffers.SETUP_FEE);
	}

	private void handleExamine(boolean isBuy)
	{
		int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId <= 0)
		{
			return;
		}

		String replacement = isBuy ? buildBuyDescription(itemId) : buildSellDescription(itemId);
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

	private String buildBuyDescription(int itemId)
	{
		Integer dailyVolume = getCachedDailyVolume(itemId);
		// Kick off async fetch on cache miss. When the value lands, repaint
		// whichever description widget is currently showing this item — could
		// be SETUP_DESC (player constructing a new offer) or DETAILS_DESC
		// (player viewing an in-flight slot holding this item).
		if (dailyVolume == null)
		{
			log.debug("[GE desc] cold cache for itemId={}, kicking off async fetch", itemId);
			apiClient.getDailyVolumeAsync(itemId).whenComplete((v, ex) ->
			{
				if (ex != null)
				{
					log.debug("[GE desc] async fetch FAILED for itemId={}: {}", itemId, ex.getMessage());
					return;
				}
				log.debug("[GE desc] async fetch completed for itemId={} value={}", itemId, v);
				if (v != null)
				{
					refreshAnyDescriptionFor(itemId);
				}
			});
		}

		Integer buyLimit = lookupBuyLimit(itemId);
		Integer wikiInstaBuy = lookupWikiInstaBuy(itemId);

		return GeOfferDescriptionFormatter.formatBuyDescription(dailyVolume, buyLimit, wikiInstaBuy);
	}

	/**
	 * Mutate the Offer status (in-flight) panel's description with our
	 * contextual data. Same lines as the setup screen; sell-side uses the
	 * offer's locked listed price + total quantity (no dynamic recalc
	 * needed since price is fixed once submitted). Idempotent — safe to
	 * re-call from multiple triggers (varbit, script-post).
	 */
	private void refreshDetailsFor(int slot, String trigger)
	{
		clientThread.invoke(() ->
		{
			GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			if (offers == null || slot >= offers.length)
			{
				log.debug("[GE desc] details SKIP slot={} trigger={} reason=offers-array",
					slot, trigger);
				return;
			}
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				log.debug("[GE desc] details SKIP slot={} trigger={} reason=offer-empty (offer={}, state={})",
					slot, trigger, offer == null ? "null" : "non-null",
					offer == null ? "?" : offer.getState());
				return;
			}
			Widget desc = client.getWidget(InterfaceID.GeOffers.DETAILS_DESC);
			if (desc == null)
			{
				log.debug("[GE desc] details SKIP slot={} trigger={} reason=desc-null", slot, trigger);
				return;
			}

			int itemId = offer.getItemId();
			GrandExchangeOfferState state = offer.getState();
			boolean isBuy = TrackedOffer.isBuyState(state);
			String text = isBuy
				? buildBuyDescription(itemId)
				: buildSellDescriptionStatic(itemId, offer.getPrice(), offer.getTotalQuantity());
			desc.setText(text);
			hideAndTransparent(InterfaceID.GeOffers.DETAILS_GRAPHIC4);
			hideAndTransparent(InterfaceID.GeOffers.DETAILS_FEE);
			log.debug("[GE desc] details WROTE slot={} itemId={} state={} isBuy={} trigger={}",
				slot, itemId, state, isBuy, trigger);
		});
	}

	/**
	 * Belt-and-suspenders widget invisibility — combine setHidden(true) with
	 * fully-transparent opacity. We do both because setHidden() alone did not
	 * stick on SETUP_GRAPHIC4 in user testing (likely overridden by a later
	 * widget-config script). Mutations must happen on the client thread —
	 * all callers in this service are already there.
	 */
	private void hideAndTransparent(int widgetId)
	{
		Widget w = client.getWidget(widgetId);
		if (w == null)
		{
			return;
		}
		w.setHidden(true);
		// 0 = fully opaque, 255 = fully transparent
		w.setOpacity(255);
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

	/**
	 * Post-async-fetch repaint. The player can be on the SETUP screen for
	 * this item OR on the DETAILS panel for a slot holding this item.
	 * We try both — the wrong path silently skips (its identity guard fails).
	 */
	private void refreshAnyDescriptionFor(int itemId)
	{
		log.debug("[GE desc] scheduling repaint for itemId={}", itemId);
		clientThread.invoke(() ->
		{
			boolean setup = tryRepaintSetupDesc(itemId);
			boolean details = tryRepaintDetailsDesc(itemId);
			log.debug("[GE desc] repaint result itemId={} setup={} details={}", itemId, setup, details);
		});
	}

	private boolean tryRepaintSetupDesc(int itemId)
	{
		if (client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH) != itemId)
		{
			return false;
		}
		Widget desc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		if (desc == null)
		{
			return false;
		}
		desc.setText(buildBuyDescription(itemId));
		return true;
	}

	private boolean tryRepaintDetailsDesc(int itemId)
	{
		int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
		if (slot < 0 || slot >= MAX_GE_SLOTS)
		{
			return false;
		}
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null || slot >= offers.length)
		{
			return false;
		}
		GrandExchangeOffer offer = offers[slot];
		if (offer == null || offer.getItemId() != itemId
			|| offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			return false;
		}
		Widget desc = client.getWidget(InterfaceID.GeOffers.DETAILS_DESC);
		if (desc == null)
		{
			return false;
		}
		boolean isBuy = TrackedOffer.isBuyState(offer.getState());
		desc.setText(isBuy
			? buildBuyDescription(itemId)
			: buildSellDescriptionStatic(itemId, offer.getPrice(), offer.getTotalQuantity()));
		hideAndTransparent(InterfaceID.GeOffers.DETAILS_GRAPHIC4);
		hideAndTransparent(InterfaceID.GeOffers.DETAILS_FEE);
		return true;
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
	 * Read-only cache lookup; never blocks. {@link #buildBuyDescription} kicks
	 * off the async fetch when this returns null.
	 */
	private Integer getCachedDailyVolume(int itemId)
	{
		// Trampoline through the API client's getDailyVolumeAsync — when the
		// value is cached it returns a completed future and getNow is safe.
		java.util.concurrent.CompletableFuture<Integer> f = apiClient.peekCachedDailyVolume(itemId);
		return (f != null && f.isDone()) ? f.getNow(null) : null;
	}
}
