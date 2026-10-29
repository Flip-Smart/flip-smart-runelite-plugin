package com.flipsmart;
import com.flipsmart.api.dto.Dtos.WikiPrice;
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
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

	// The max-cash update moved the typed offer price out of a varbit into this
	// 64-bit varp (unnamed in the RuneLite API), since prices can now exceed int.
	private static final int NEWOFFER_PRICE_VARP = 5753;

	// DETAILS_DESC sits 10px further left than SETUP_DESC in Jagex's
	// hand-tuned layout, exposing a parent-background strip between the icon
	// column and our text. Shift left to close the gap. Tracked per-widget so
	// the mutation only fires once per panel build.
	private static final int DETAILS_DESC_LEFT_SHIFT_PX = 10;
	private final Set<Integer> shiftedDescIds = new HashSet<>();

	// When each item's 4h buy limit resets, recorded per RS profile off the first
	// unit bought. The stock Grand Exchange plugin keeps the same record, so its
	// copy is read as a fallback — but it can be switched off, and then only ours
	// exists. Both are consulted; whichever expires later wins.
	private static final String OUR_CONFIG_GROUP = "flipsmart";
	private static final String GE_CONFIG_GROUP = "grandexchange";
	private static final String BUY_LIMIT_KEY_PREFIX = "buylimit.";

	// Snapshotted per item and held until the GE is reopened, so the per-frame
	// description rebuild does not deserialize the stored Instant every frame.
	private int cachedLimitResetItemId = -1;
	private Instant cachedLimitReset;

	// Running windows to announce when they end; reloaded whenever the RS profile changes.
	private final Map<Integer, Instant> pendingLimitResets = new HashMap<>();
	private String trackedResetsProfile;

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
	private final ConfigManager configManager;
	private final FlipSmartConfig config;
	private final Notifier notifier;

	@Inject
	public GeOfferDescriptionService(
		Client client,
		ClientThread clientThread,
		FlipSmartApiClient apiClient,
		FlipSmartPlugin plugin,
		ItemManager itemManager,
		FlipAssistOverlay flipAssistOverlay,
		ConfigManager configManager,
		FlipSmartConfig config,
		Notifier notifier)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.apiClient = apiClient;
		this.plugin = plugin;
		this.itemManager = itemManager;
		this.flipAssistOverlay = flipAssistOverlay;
		this.configManager = configManager;
		this.config = config;
		this.notifier = notifier;
	}

	// ---------------------------------------------------------------------
	// Setup screen — script-callback driven
	// ---------------------------------------------------------------------

	public boolean onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!config.showGeItemInfo()) return false;
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
		if (!config.showGeItemInfo()) return;
		hideAndTransparent(InterfaceID.GeOffers.SETUP_GRAPHIC4);
		hideAndTransparent(InterfaceID.GeOffers.SETUP_FEE);
	}

	// ---------------------------------------------------------------------
	// Click capture — authoritative source for the open-panel slot
	// ---------------------------------------------------------------------

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		int slot = slotOf(event.getParam1());
		if (slot >= 0)
		{
			lastClickedSlot = slot;
		}
	}

	public static int slotOf(int widgetId)
	{
		int slot = (widgetId & 0xFFFF) - INDEX_0_CHILD;
		return (widgetId >>> 16) == GE_OFFERS_GROUP && slot >= 0 && slot < MAX_GE_SLOTS ? slot : -1;
	}

	/**
	 * Drops the remembered slot when the GE interface is (re)opened. Offers are
	 * collected and slots reused between visits, so a slot index carried over from
	 * a previous visit can name an entirely different item.
	 */
	public void onGeOffersWidgetLoaded()
	{
		lastClickedSlot = -1;
		cachedLimitResetItemId = -1;
	}

	// ---------------------------------------------------------------------
	// Offer status panel — per-frame
	// ---------------------------------------------------------------------

	public void onBeforeRender(BeforeRender event)
	{
		// Ceasing the per-frame write is the whole revert: Jagex's own script
		// repaints the description on the next panel build.
		if (!config.showGeItemInfo()) return;
		Widget setupDesc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		Widget detailsDesc = client.getWidget(InterfaceID.GeOffers.DETAILS_DESC);
		if (setupDesc == null && detailsDesc == null)
		{
			return;
		}

		long[] ctx = resolveOfferContext();
		if (ctx == null)
		{
			return;
		}

		// ctx: [itemId, isBuy (0/1), price, qty]
		int itemId = (int) ctx[0];
		boolean isBuy = ctx[1] == 1;
		long price = ctx[2];
		int qty = (int) ctx[3];

		String desired = isBuy
			? buildBuyDescription(itemId)
			: buildSellDescriptionStatic(itemId, price, qty);

		writeIfNeeded(InterfaceID.GeOffers.DETAILS_DESC, desired, DETAILS_DESC_LEFT_SHIFT_PX);
		writeIfNeeded(InterfaceID.GeOffers.SETUP_DESC, desired, 0);
		hideAndTransparent(InterfaceID.GeOffers.DETAILS_GRAPHIC4);
		hideAndTransparent(InterfaceID.GeOffers.DETAILS_FEE);
	}

	/**
	 * Resolves the offer context (itemId, direction, price, quantity). The screen
	 * owns item and direction: the live "Set up offer" window, else the committed
	 * offer on the active slot. Flip Assist focus only supplies its recommended
	 * price/qty when it agrees with the screen on both, or when nothing is on
	 * screen yet.
	 *
	 * @return long[]{itemId, isBuy (1=buy/0=sell), price, qty}, or {@code null} if
	 *         no actionable context can be determined.
	 */
	long[] resolveOfferContext()
	{
		// The setup window is pre-confirm and belongs to no committed slot, so
		// while it is open it outranks the active slot: that slot may hold an
		// unrelated in-flight offer the player merely hovered or clicked earlier.
		long[] screenCtx = resolveSetupWindowContext();
		if (screenCtx == null)
		{
			screenCtx = resolveSlotContext();
		}

		long[] focusCtx = resolveFlipAssistFocusContext(screenCtx);
		return focusCtx != null ? focusCtx : screenCtx;
	}

	/**
	 * Flip Assist focus carries the richest context (recommended price/qty), but it
	 * tracks the plugin's *next recommended action* — routinely a different item, or
	 * the same item at a different step (a SELL focus lingering after a flip while
	 * the player re-buys). Trusting its item would bleed another item's numbers onto
	 * the panel; trusting its step would print sell stats on a buy screen. So it is
	 * used only when it matches the screen's item and direction, or when no screen
	 * context exists yet (e.g. a fresh buy before an item is picked).
	 *
	 * @return long[]{itemId, isBuy (1=buy/0=sell), price, qty}, or {@code null} if
	 *         Flip Assist focus is absent or disagrees with the screen.
	 */
	private long[] resolveFlipAssistFocusContext(long[] screenCtx)
	{
		FocusedFlip focus = flipAssistOverlay == null ? null : flipAssistOverlay.getFocusedFlip();
		if (focus == null)
		{
			return null;
		}

		boolean isBuy = focus.getStep() == FocusedFlip.FlipStep.BUY;
		if (screenCtx != null && (screenCtx[0] != focus.getItemId() || (screenCtx[1] == 1) != isBuy))
		{
			return null;
		}

		long price = isBuy ? focus.getBuyPrice() : focus.getSellPrice();
		int qty = isBuy ? focus.getBuyQuantity() : focus.getSellQuantity();
		return new long[]{focus.getItemId(), isBuy ? 1 : 0, price, qty};
	}

	/**
	 * Reads the current "Set up offer" screen state directly from varbits/varps.
	 * Returns {@code null} when the setup window isn't the surface on screen or no
	 * offer is being built. Direction encoding: {@code GE_NEWOFFER_TYPE == 1} →
	 * sell, anything else non-zero → buy.
	 *
	 * <p>The per-frame path must read this itself: the geBuyExamineText /
	 * geSellExamineText callbacks do not fire on the current Jagex setup window, so
	 * nothing else would replace SETUP_DESC for an offer built without a focus.
	 */
	private long[] resolveSetupWindowContext()
	{
		// The in-flight status panel is a different surface with a different
		// authority (its committed slot), so the setup window never speaks for it.
		if (isDetailsContainerVisible())
		{
			return null;
		}

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
		long price = Math.max(client.getVarpLongValue(NEWOFFER_PRICE_VARP), 0L);
		int qty = Math.max(client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY), 0);
		return new long[]{itemId, isBuy ? 1 : 0, price, qty};
	}

	private long[] resolveSlotContext()
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
		return new long[]{offer.getItemId(), isBuy ? 1 : 0, offer.getPrice(), offer.getTotalQuantity()};
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
			dailyVolume, lookupBuyLimit(itemId), lookupWikiInstaBuy(itemId),
			lookupLimitResetMillis(itemId));
	}

	/**
	 * Milliseconds until the item's 4h buy limit resets, or {@code null} when
	 * nothing was bought inside the window. The stored value is snapshotted per
	 * item; the remaining time is still derived from the current clock, so an
	 * open panel stays truthful without re-reading config each frame.
	 */
	Long lookupLimitResetMillis(int itemId)
	{
		if (cachedLimitResetItemId != itemId)
		{
			cachedLimitReset = readStoredLimitReset(itemId);
			cachedLimitResetItemId = itemId;
		}
		if (cachedLimitReset == null)
		{
			return null;
		}
		long remaining = Duration.between(Instant.now(), cachedLimitReset).toMillis();
		return remaining > 0 ? remaining : null;
	}

	private Instant readStoredLimitReset(int itemId)
	{
		Instant ours = readLimitReset(OUR_CONFIG_GROUP, itemId);
		Instant stock = readLimitReset(GE_CONFIG_GROUP, itemId);
		return ours == null || (stock != null && stock.isAfter(ours)) ? stock : ours;
	}

	private Instant readLimitReset(String group, int itemId)
	{
		try
		{
			return (Instant) configManager.getRSProfileConfiguration(
				group, BUY_LIMIT_KEY_PREFIX + itemId, Instant.class);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Opens the 4h window on the first unit bought, matching the stock plugin's
	 * rule so the two records agree. An already-running window is left alone —
	 * later fills on the same offer must not push the reset back.
	 */
	public void recordBuyLimitWindow(GrandExchangeOffer offer)
	{
		if (offer == null)
		{
			return;
		}
		GrandExchangeOfferState state = offer.getState();
		if (state != GrandExchangeOfferState.BOUGHT
			&& !(state == GrandExchangeOfferState.BUYING && offer.getQuantitySold() > 0))
		{
			return;
		}
		int itemId = offer.getItemId();
		Instant existing = readStoredLimitReset(itemId);
		if (existing != null && existing.isAfter(Instant.now()))
		{
			trackLimitReset(itemId, existing);
			return;
		}
		Instant resetAt = Instant.now().plus(Duration.ofHours(4));
		configManager.setRSProfileConfiguration(OUR_CONFIG_GROUP, BUY_LIMIT_KEY_PREFIX + itemId, resetAt);
		trackLimitReset(itemId, resetAt);
		if (cachedLimitResetItemId == itemId)
		{
			cachedLimitResetItemId = -1;
		}
	}

	public void checkBuyLimitResets()
	{
		String profile = configManager.getRSProfileKey();
		if (profile != null && !profile.equals(trackedResetsProfile))
		{
			trackedResetsProfile = profile;
			pendingLimitResets.clear();
			for (String key : configManager.getRSProfileConfigurationKeys(OUR_CONFIG_GROUP, profile, BUY_LIMIT_KEY_PREFIX))
			{
				int itemId = Integer.parseInt(key.substring(BUY_LIMIT_KEY_PREFIX.length()));
				trackLimitReset(itemId, readStoredLimitReset(itemId));
			}
		}
		notifyLimitResets(Instant.now());
	}

	void notifyLimitResets(Instant now)
	{
		List<String> names = new ArrayList<>();
		pendingLimitResets.entrySet().removeIf(e -> !e.getValue().isAfter(now) && names.add(plugin.getItemName(e.getKey())));
		if (!names.isEmpty())
		{
			notifier.notify(config.buyLimitResetAlert(), "Buy limit reset: " + String.join(", ", names));
		}
	}

	private void trackLimitReset(int itemId, Instant resetAt)
	{
		if (resetAt != null && resetAt.isAfter(Instant.now()))
		{
			pendingLimitResets.put(itemId, resetAt);
		}
	}

	private void refreshSetupBuyDescription(int itemId)
	{
		clientThread.invoke(() ->
		{
			// The volume fetch can land after the player switches the override off.
			if (!config.showGeItemInfo()) return;
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
		long sellPrice = Math.max(client.getVarpLongValue(NEWOFFER_PRICE_VARP), 0L);
		int quantity = Math.max(client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY), 0);
		Long recordedBuyPrice = BuyPriceLookup.findAverageBuyPriceWithFallback(
			plugin.getCurrentActiveFlips(), plugin.getCycleBasisForItem(itemId, quantity),
			plugin.getOfferRecordsForItem(itemId), itemId, plugin.getHeldQuantityForItem(itemId));
		return GeOfferDescriptionFormatter.formatSellDescription(itemId, recordedBuyPrice, sellPrice, quantity);
	}

	private String buildSellDescriptionStatic(int itemId, long listedPrice, int totalQuantity)
	{
		Long recordedBuyPrice = BuyPriceLookup.findAverageBuyPriceWithFallback(
			plugin.getCurrentActiveFlips(), plugin.getCycleBasisForItem(itemId, totalQuantity),
			plugin.getOfferRecordsForItem(itemId), itemId, plugin.getHeldQuantityForItem(itemId));
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

	private Long lookupWikiInstaBuy(int itemId)
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
