package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Replaces the GE buy/sell SETUP window description text with contextual
 * FlipSmart data (issue #665) by intercepting the {@code geBuyExamineText} /
 * {@code geSellExamineText} script callbacks injected by the RuneLite client.
 *
 * <p>The RuneLite-injected runescript ({@code GeExamineInfoText.rs2asm}, script
 * id 5730) fires this callback every time the offer-setup description is
 * rebuilt, including after the player adjusts the entered price or quantity.
 * Writing our replacement string into the object stack's return slot (index
 * {@code sz-1}) handles every render after the daily-volume cache is warm.
 * AC9 (no paint overlay) is satisfied by construction.</p>
 *
 * <p>One narrow path mutates the {@code SETUP_DESC} widget directly: when the
 * first render for a fresh item lands with a cold daily-volume cache, the
 * synchronous render writes "N/A" and kicks off the async fetch. When the
 * fetch completes we repaint the widget on the client thread so the player
 * sees the real volume without having to reopen — the script-callback alone
 * cannot recover from this because it won't fire again until the player
 * edits the price or reopens the window.</p>
 *
 * <p><b>Scope note:</b> the in-flight Offer status panel ({@code DETAILS_DESC})
 * is NOT covered. RuneLite does not inject a script callback for that widget,
 * and the OSRS client rebuilds {@code DETAILS_DESC} on every game tick from
 * ~30 different scripts — direct widget mutation gets immediately overwritten,
 * creating an unwinnable fight. Surfacing FlipSmart context on that screen
 * would need a different rendering primitive (likely a paint overlay, which
 * would violate AC9) and is deferred to a follow-up ticket.</p>
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
	 * No-op hook retained so the existing FlipSmartPlugin wiring still compiles.
	 * The setup-screen flow does not need ScriptPostFired — the script-callback
	 * path is sufficient. Reserved for future extension.
	 */
	public void onScriptPostFired(ScriptPostFired event)
	{
		// intentionally empty
	}

	/**
	 * Render the in-flight Offer status (DETAILS_DESC) panel once per visual
	 * frame. This is the right hook because:
	 *
	 * <ul>
	 *   <li>OSRS rebuilds DETAILS_DESC from ~30 different scripts per game tick
	 *       — reacting after each (via ScriptPostFired) is an unwinnable race.</li>
	 *   <li>BeforeRender fires once per visual frame AFTER all per-tick scripts
	 *       have settled but BEFORE the paint. We get the final say each frame,
	 *       so the user never sees the original examine.</li>
	 *   <li>Already on the client thread — no clientThread.invoke needed.</li>
	 *   <li>String-equality short-circuit makes the common case (text already
	 *       ours) free.</li>
	 * </ul>
	 */
	// One-shot widget enumeration counter — dump GeOffers widget tree the
	// first N times BeforeRender fires with a slot selected, so we can
	// identify which widget is actually visible on the offer-status panel.
	// The DETAILS_DESC widget we've been writing to doesn't appear to be
	// the visible one — user reports original examine text remains.
	private int diagWidgetDumpRemaining = 5;

	public void onBeforeRender(BeforeRender event)
	{
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

		int itemId = offer.getItemId();
		GrandExchangeOfferState state = offer.getState();
		boolean isBuy = TrackedOffer.isBuyState(state);
		String desired = isBuy
			? buildBuyDescription(itemId)
			: buildSellDescriptionStatic(itemId, offer.getPrice(), offer.getTotalQuantity());

		// Belt-and-suspenders: write to BOTH candidate widget IDs. If DETAILS_DESC
		// isn't the visible one, SETUP_DESC (reused for both screens by some
		// interfaces) might be.
		int writeCount = 0;
		writeCount += writeIfNeeded(InterfaceID.GeOffers.DETAILS_DESC, desired, "DETAILS_DESC");
		writeCount += writeIfNeeded(InterfaceID.GeOffers.SETUP_DESC, desired, "SETUP_DESC");

		if (writeCount > 0)
		{
			hideAndTransparent(InterfaceID.GeOffers.DETAILS_GRAPHIC4);
			hideAndTransparent(InterfaceID.GeOffers.DETAILS_FEE);
			log.debug("[GE desc] details WROTE slot={} itemId={} state={} isBuy={} qty={} price={} spent={} writes={}",
				slot, itemId, state, isBuy, offer.getTotalQuantity(), offer.getPrice(), offer.getSpent(), writeCount);
		}

		// One-shot widget tree dump — find which widget actually shows the
		// visible description text on the offer-status panel.
		if (diagWidgetDumpRemaining > 0)
		{
			diagWidgetDumpRemaining--;
			dumpGeOffersWidgetTree(slot);
		}
	}

	private int writeIfNeeded(int widgetId, String desired, String label)
	{
		Widget w = client.getWidget(widgetId);
		if (w == null)
		{
			return 0;
		}
		String current = w.getText();
		if (desired.equals(current))
		{
			return 0;
		}
		w.setText(desired);
		log.debug("[GE desc] wrote {} (id={}) was-len={} now-len={}",
			label, widgetId, current == null ? -1 : current.length(), desired.length());
		return 1;
	}

	/**
	 * Brute-force walk: try child IDs 0..MAX of the GE_OFFERS group (465).
	 * For each widget that exists and has text, log it. Then recurse into
	 * its children. The visible description widget on the offer-status panel
	 * will appear here with text "Small shiny scales." or similar examine.
	 */
	private void dumpGeOffersWidgetTree(int slot)
	{
		log.debug("[GE diag] === widget dump for slot={} ===", slot);
		final int GE_OFFERS_GROUP = 465;
		int found = 0;
		for (int child = 0; child < 60; child++)
		{
			Widget w = client.getWidget(GE_OFFERS_GROUP, child);
			if (w == null)
			{
				continue;
			}
			String text = w.getText();
			if (text != null && !text.isEmpty() && text.length() < 200)
			{
				log.debug("[GE diag]   child={} hidden={} text=\"{}\"",
					child, w.isSelfHidden(), text.replace("\n", "\\n").replace("\r", ""));
				found++;
			}
			// Recurse one level into children
			Widget[] grandchildren = w.getStaticChildren();
			if (grandchildren != null)
			{
				for (int gi = 0; gi < grandchildren.length; gi++)
				{
					Widget gc = grandchildren[gi];
					if (gc == null) continue;
					String gtext = gc.getText();
					if (gtext != null && !gtext.isEmpty() && gtext.length() < 200)
					{
						log.debug("[GE diag]     child={}.{} hidden={} text=\"{}\"",
							child, gi, gc.isSelfHidden(),
							gtext.replace("\n", "\\n").replace("\r", ""));
						found++;
					}
				}
			}
			Widget[] dynChildren = w.getDynamicChildren();
			if (dynChildren != null)
			{
				for (int gi = 0; gi < dynChildren.length; gi++)
				{
					Widget gc = dynChildren[gi];
					if (gc == null) continue;
					String gtext = gc.getText();
					if (gtext != null && !gtext.isEmpty() && gtext.length() < 200)
					{
						log.debug("[GE diag]     dyn-child={}.{} hidden={} text=\"{}\"",
							child, gi, gc.isSelfHidden(),
							gtext.replace("\n", "\\n").replace("\r", ""));
						found++;
					}
				}
			}
		}
		log.debug("[GE diag] === found {} widgets with text in slot={} ===", found, slot);
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
	 * Called by the plugin's existing onScriptPostFired handler after a
	 * GE_OFFERS_SETUP_BUILD has fired. Makes the convenience-fee info icon
	 * (SETUP_GRAPHIC4) invisible — our description already shows the tax
	 * explicitly so the icon is redundant, AND the runescript positions
	 * it based on the original (now-replaced) text metrics, which makes
	 * it overlap our multi-line content.
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

	/**
	 * Post-fetch repaint for the SETUP_DESC widget when the daily-volume cache
	 * lands. Schedules on the client thread; guarded against the player having
	 * switched items between the fetch firing and completing.
	 */
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

	/** Read-only cache lookup; never blocks. */
	private Integer getCachedDailyVolume(int itemId)
	{
		java.util.concurrent.CompletableFuture<Integer> f = apiClient.peekCachedDailyVolume(itemId);
		return (f != null && f.isDone()) ? f.getNow(null) : null;
	}

	/**
	 * Belt-and-suspenders widget invisibility — combine setHidden(true) with
	 * fully-transparent opacity. We do both because setHidden() alone did not
	 * stick on SETUP_GRAPHIC4 in user testing (likely overridden by a later
	 * widget-config script).
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
}
