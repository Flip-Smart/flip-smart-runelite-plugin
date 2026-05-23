package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
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
 * {@code sz-1}) is sufficient — no widget mutation, no re-render races, no
 * cleanup on close (AC7, AC8, AC9 all satisfied by construction).</p>
 */
@Slf4j
@Singleton
public class GeOfferDescriptionService
{
	static final String EVENT_BUY_EXAMINE = "geBuyExamineText";
	static final String EVENT_SELL_EXAMINE = "geSellExamineText";

	private final Client client;
	private final FlipSmartApiClient apiClient;
	private final FlipSmartPlugin plugin;
	private final ItemManager itemManager;

	@Inject
	public GeOfferDescriptionService(
		Client client,
		FlipSmartApiClient apiClient,
		FlipSmartPlugin plugin,
		ItemManager itemManager)
	{
		this.client = client;
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
		// Kick off async fetch — once the cache is warm, subsequent rebuilds of
		// the description (same item or other items) will pick up the value.
		if (dailyVolume == null)
		{
			apiClient.getDailyVolumeAsync(itemId).whenComplete((v, ex) ->
			{
				if (ex != null)
				{
					log.debug("Failed to fetch daily volume for item {}: {}", itemId, ex.getMessage());
				}
			});
		}

		Integer buyLimit = lookupBuyLimit(itemId);
		Integer wikiInstaBuy = lookupWikiInstaBuy(itemId);

		return GeOfferDescriptionFormatter.formatBuyDescription(dailyVolume, buyLimit, wikiInstaBuy);
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
