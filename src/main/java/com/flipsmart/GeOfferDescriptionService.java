package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptCallbackEvent;
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
 * data (issue #665) by intercepting the {@code geBuyExamineText} /
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
 */
@Slf4j
@Singleton
public class GeOfferDescriptionService
{
	static final String EVENT_BUY_EXAMINE = "geBuyExamineText";
	static final String EVENT_SELL_EXAMINE = "geSellExamineText";

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
	 * Handle a {@link ScriptCallbackEvent} from the plugin's dispatcher.
	 * Returns true when the event was a GE examine callback (handled, regardless
	 * of whether we wrote a replacement); false when the event was unrelated and
	 * the dispatcher should treat it as a no-op.
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
		// Kick off async fetch on cache miss. When the value lands, repaint the
		// SETUP_DESC widget so the user sees the real volume without having to
		// reopen the window — the first sync render rendered "N/A" because the
		// cache was cold, and the script-callback won't refire until the player
		// edits the price or reopens, so we have to do it ourselves.
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
					refreshBuyDescriptionFor(itemId);
				}
			});
		}

		Integer buyLimit = lookupBuyLimit(itemId);
		Integer wikiInstaBuy = lookupWikiInstaBuy(itemId);

		return GeOfferDescriptionFormatter.formatBuyDescription(dailyVolume, buyLimit, wikiInstaBuy);
	}

	/**
	 * Recompute the buy-description string with whatever's now in cache and
	 * write it directly to the SETUP_DESC widget. Scheduled on the client thread
	 * because Widget mutations must happen there. Guarded against the user
	 * having switched items between the fetch firing and completing — we no-op
	 * if the currently-selected item is no longer the one we fetched for.
	 */
	private void refreshBuyDescriptionFor(int itemId)
	{
		log.debug("[GE desc] scheduling repaint for itemId={}", itemId);
		clientThread.invoke(() ->
		{
			int currentItem = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
			int offerType = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE);
			Widget desc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
			log.debug("[GE desc] repaint exec itemId={} currentItem={} offerType={} desc={}",
				itemId, currentItem, offerType, desc == null ? "null" : "present");

			if (currentItem != itemId)
			{
				log.debug("[GE desc] repaint SKIPPED: item changed ({} -> {})", itemId, currentItem);
				return;
			}
			// Sell-side type is 2; we only want to repaint when the buy setup
			// screen is the one currently rendered (a stale fetch from a recent
			// buy could otherwise land while the player is now constructing a
			// sell and clobber the sell description).
			if (offerType != 1)
			{
				log.debug("[GE desc] repaint SKIPPED: offerType={} not buy", offerType);
				return;
			}
			if (desc == null)
			{
				log.debug("[GE desc] repaint SKIPPED: widget null");
				return;
			}
			// Intentionally not checking desc.isHidden() — that walks the whole
			// ancestor chain and can return true for visually-visible widgets
			// during in-flight script execution. If the player has truly closed
			// the window, the widget is destroyed (null) and we bail above.
			String newText = buildBuyDescription(itemId);
			desc.setText(newText);
			log.debug("[GE desc] repaint setText DONE for itemId={}", itemId);
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
