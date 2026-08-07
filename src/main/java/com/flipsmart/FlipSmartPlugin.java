package com.flipsmart;

import com.flipsmart.api.dto.Dtos.OfferAdviceRequest;
import com.flipsmart.api.dto.Dtos.OfferAdviceResponse;
import com.flipsmart.api.dto.Dtos.OfferAdviceResult;
import com.flipsmart.api.dto.Dtos.SellPriceCheckRequest;
import com.flipsmart.api.dto.Dtos.WikiPrice;
import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.flip.ActiveFlipItemIds;
import com.flipsmart.domain.flip.ActiveFlipProjection;
import com.flipsmart.domain.flip.ActiveFlipsSnapshotPayload;
import com.flipsmart.domain.flip.AwaitingSaleLot;
import com.flipsmart.domain.flip.AwaitingSaleLots;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.offer.PendingOrder;
import com.flipsmart.exit.ExitTradesController;
import com.flipsmart.plugin.EventRouter;
import com.flipsmart.plugin.PanelRefreshCoalescer;
import com.flipsmart.plugin.PluginScheduler;
import com.flipsmart.plugin.RsnSyncGate;
import com.flipsmart.plugin.ServiceWiring;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.util.BuyPriceLookup;
import com.flipsmart.util.GpUtils;
import com.flipsmart.util.ItemUtils;
import com.flipsmart.util.TimeUtils;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "FlipSmart",
	description = "A tool to help with item flipping in the Grand Exchange",
	tags = {"grand exchange", "flipping", "trading", "money making"}
)
public class FlipSmartPlugin extends Plugin
{
	private static final int INVENTORY_CONTAINER_ID = 93;
	private static final int COINS_ITEM_ID = 995;
	private static final int GE_REGION_ID = 12598;

	@Inject
	private Client client;

	@Inject
	private FlipSmartConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private GrandExchangeOverlay geOverlay;

	@Inject
	private GrandExchangeSlotOverlay geSlotOverlay;

	@Inject
	private GeSlotWidgetDecorator geSlotDecorator;

	@Inject
	private FlipAssistOverlay flipAssistOverlay;

	@Inject
	private InventoryHighlightOverlay inventoryHighlightOverlay;

	@Inject
	@Getter
	private FlipSmartApiClient apiClient;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Notifier notifier;

	@Inject
	private ClientUI clientUI;

	@Inject
	private KeyManager keyManager;
	
	@Inject
	private ClientThread clientThread;

	@Inject
	private net.runelite.client.ui.ClientToolbar clientToolbar;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;


	@Inject
	private ConfigManager configManager;


	@Inject
	private OfflineSyncService offlineSyncService;

	@Inject
	private BankSnapshotService bankSnapshotService;

	@Inject
	private ActiveFlipTracker activeFlipTracker;

	@Inject
	private GrandExchangeTracker grandExchangeTracker;

	@Inject
	@Getter
	private OfferStore offerStore;

	@Inject
	private com.flipsmart.trading.RoundTripLedger roundTripLedger;

	@Inject
	private WebhookSyncService webhookSyncService;

	@Inject
	private GEHistoryService geHistoryService;

	@Inject
	private GeOfferDescriptionService geOfferDescriptionService;

	@Inject
	private TradeStationSlotPushService tradeStationSlotPushService;

	@Inject
	private ActiveFlipsSnapshotPushService activeFlipsSnapshotPushService;

	@Inject
	private Gson gson;

	// Flip Finder panel
	@Getter
	private FlipFinderPanel flipFinderPanel;
	private net.runelite.client.ui.NavigationButton flipFinderNavButton;

	// Auto-recommend service
	@Getter
	private AutoRecommendService autoRecommendService;

	// Exit Trades controller (guided mass sell/cancel)
	@Getter
	private ExitTradesController exitTradesController;

	// Manual flip adjustment tracker (API-based staleness detection)
	private ManualAdjustmentTracker manualAdjustmentTracker;

	// Centralized session state management (provided via @Provides @Singleton)
	@Inject
	@Getter
	private PlayerSession session;

	// Timer / one-shot ownership extracted into PluginScheduler
	private final PluginScheduler scheduler = new PluginScheduler();

	// Post-construction setter wiring extracted into ServiceWiring
	private final ServiceWiring serviceWiring = new ServiceWiring();

	// EventBus routing extracted into EventRouter (built in startUp once collaborators exist)
	private EventRouter eventRouter;

	// Active-offer advisor service (poll timer owned by the scheduler)
	private ActiveOfferAdvisorService activeOfferAdvisorService;
	private long lastAdvisorPollMs;

	// Last known RSN — saved when we learn it, used as fallback for persistence on shutdown
	// when session.getRsn() may already be null
	private volatile String lastKnownRsn;

	// True when handleLoggedInState ran but the local player was not yet
	// populated, so syncRSN couldn't capture the current account's name. The
	// onGameTick handler retries until it succeeds. Issue #556.
	private volatile boolean rsnSyncPending;
	private final RsnSyncGate rsnSyncGate = new RsnSyncGate();

	// Cached world-type flag — updated on the client thread (WorldChanged, login) and read
	// from any thread (Swing EDT, scheduler). Defaults to true so unlinked callers see more items.
	private volatile boolean membersWorld = true;

	// Cached account-type string — updated on the client thread, read from any thread.
	@Getter
	private volatile String accountType;

	// Cached GE location flag — updated each game tick on the client thread.
	@Getter
	private volatile boolean atGrandExchange = false;

	// Canonical item ids currently held in the inventory. Rebuilt on the client
	// thread (inventory ItemContainerChanged) and published as one volatile swap to
	// an immutable set, so the Swing EDT reader in the Active Flips filter always
	// sees a complete snapshot, never a partially-rebuilt set.
	@Getter
	private volatile Set<Integer> inventoryFlipItemIds = Collections.emptySet();

	// Canonical item id -> inventory count, rebuilt on the client thread alongside
	// inventoryFlipItemIds and published as one volatile swap. Safe to read off the
	// client thread (e.g. the Swing EDT) where the live client inventory API cannot be.
	private volatile Map<Integer, Integer> inventoryFlipItemCounts = Collections.emptyMap();

	// False until an inventory container has actually been read. Distinguishes "holds
	// nothing" from "not logged in", so a collected lot is only dropped as sold when the
	// inventory genuinely says so.
	private volatile boolean inventorySnapshotKnown;

	// True once the offer store has been seeded from real GE state (login burst or
	// preloadPersistedOffers), set only on the client thread. Lets the snapshot-push
	// executor (background thread) know whether emptiness is observed or just startup
	// default, without itself calling the client-thread-only GE offers API.
	private volatile boolean offerStoreSeeded;

	// Track login to avoid recording existing offers as new transactions
	private static final int GE_LOGIN_BURST_WINDOW = 3; // ticks

	// Config keys for persisting state
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String UNKNOWN_RSN_FALLBACK = "unknown";
	private static final String AUTO_RECOMMEND_STATE_KEY_PREFIX = "autoRecommendState_";
	private static final String EXIT_TRADES_STATE_KEY_PREFIX = "exitTradesState_";
	private static final long EXIT_TRADES_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000; // 7 days
	private static final String LAST_KNOWN_RSN_KEY = "lastKnownRsn";

	// Flip Assist input listener for hotkey handling
	private FlipAssistInputListener flipAssistInputListener;

	// Injects the clickable "FlipSmart item" shortcut into GE search results
	private GeSearchSuggestion geSearchSuggestion;

	// Threshold constants
	/** Minimum interval between auto-refreshes (30 seconds) */
	private static final long AUTO_REFRESH_MIN_INTERVAL_MS = 30_000;
	/** Minimum cash stack to trigger auto-refresh on change */
	private static final int AUTO_REFRESH_CASH_THRESHOLD = 100_000;

	/**
	 * Enum representing offer competitiveness relative to Wiki prices
	 */
	public enum OfferCompetitiveness
	{
		COMPETITIVE,      // Green checkmark - price is at or better than Wiki price
		UNCOMPETITIVE,    // Red X - price is worse than Wiki price
		UNKNOWN           // Gray ? - Wiki price unavailable
	}



	/**
	 * Get current cash stack (delegates to session for backwards compatibility).
	 */
	public int getCurrentCashStack()
	{
		return session.getCurrentCashStack();
	}

	/**
	 * Check if player is logged into RuneScape (delegates to session for backwards compatibility).
	 */
	public boolean isLoggedIntoRunescape()
	{
		return session.isLoggedIntoRunescape();
	}

	/**
	 * Check if the current user has premium subscription.
	 */
	public boolean isPremium()
	{
		return apiClient.isPremium();
	}

	/**
	 * Returns true if recommendations should include members items.
	 * Returns false when on an F2P world, or when F2P Mode is enabled in config.
	 *
	 * Reads a cached value updated on the client thread to avoid off-thread Client API access.
	 */
	public boolean isMembersWorld()
	{
		if (config.f2pMode())
		{
			return false;
		}
		return membersWorld;
	}


	/**
	 * Refresh the cached members-world state from the Client API.
	 * Must be called on the client thread.
	 */
	public void updateMembersWorldCache()
	{
		membersWorld = client.getWorldType().contains(WorldType.MEMBERS);
	}

	/**
	 * Refresh the cached account-type string from the Client API.
	 * Must be called on the client thread.
	 */
	public void updateAccountTypeCache()
	{
		accountType = AccountTypeMapper.toApiValue(client.getAccountType());
	}


	public int getFlipSlotLimit()
	{
		return isPremium() ? 8 : 2;
	}

	/**
	 * Count of in-flight flips started from a Flip Finder recommendation. Items listed
	 * manually (outside Flip Finder) are excluded, so they do not consume the free cap.
	 * Prunes resolved flips as a side effect (an item releases once it leaves the
	 * active-flip set — live offer or collected inventory).
	 */
	public int getFlipFinderActiveCount()
	{
		return session.retainAndCountFlipFinderActive(getActiveFlipItemIds(), System.currentTimeMillis());
	}

	/**
	 * Whether a free-tier user has reached their Flip Finder item cap. Premium is never
	 * limited; for free users only Flip Finder-sourced flips count, not manual listings.
	 */
	public boolean isFlipFinderLimitReached()
	{
		return !isPremium() && getFlipFinderActiveCount() >= getFlipSlotLimit();
	}

	public List<ActiveFlip> getCurrentActiveFlips()
	{
		return flipFinderPanel != null ? flipFinderPanel.getCurrentActiveFlips() : null;
	}

	/**
	 * Average buy price for the round trip currently open on {@code itemId}, or {@code null} when
	 * no position is open. Scoped to the cycle, so it never reflects stock already sold.
	 */
	public Integer getCycleBasisForItem(int itemId)
	{
		return roundTripLedger == null
			? null : roundTripLedger.currentBasis(getCurrentRsnSafe().orElse(null), itemId);
	}

	/**
	 * Local OfferStore records for an item — the fallback source for the recorded
	 * buy price when the backend-sourced active-flips snapshot is empty.
	 */
	public List<OfferRecord> getOfferRecordsForItem(int itemId)
	{
		return offerStore.forItem(itemId);
	}

	/**
	 * Buy cost basis for {@code itemId} derived from the offer store: the most-recent buy
	 * record with a fill, falling back to any buy record when nothing has filled yet.
	 * Null when the store holds no buy record for the item.
	 */
	public AwaitingSaleLots.BuyBasis buyBasisForItem(int itemId)
	{
		List<OfferRecord> buys = new ArrayList<>();
		for (OfferRecord r : offerStore.forItem(itemId))
		{
			if (r.isBuy())
			{
				buys.add(r);
			}
		}
		OfferRecord bestFilled = mostRecentFilledBuy(buys);
		OfferRecord best = bestFilled != null ? bestFilled : mostRecentBuy(buys);
		if (best == null)
		{
			return null;
		}
		return new AwaitingSaleLots.BuyBasis(best.getItemName(), avgBuyPrice(best), firstBuyTimeIso(best));
	}

	/** Most recently active buy record in {@code buys}, or {@code null} when the list is empty. */
	private static OfferRecord mostRecentBuy(
		List<OfferRecord> buys)
	{
		OfferRecord best = null;
		for (OfferRecord r : buys)
		{
			if (best == null || r.getEffectiveLastActivityAtMillis() > best.getEffectiveLastActivityAtMillis())
			{
				best = r;
			}
		}
		return best;
	}

	/** Most recently active buy record in {@code buys} that has at least one filled unit. */
	private static OfferRecord mostRecentFilledBuy(
		List<OfferRecord> buys)
	{
		OfferRecord best = null;
		for (OfferRecord r : buys)
		{
			if (r.getFilledQuantity() <= 0)
			{
				continue;
			}
			if (best == null || r.getEffectiveLastActivityAtMillis() > best.getEffectiveLastActivityAtMillis())
			{
				best = r;
			}
		}
		return best;
	}

	private static int avgBuyPrice(OfferRecord best)
	{
		return best.getSpent() > 0 && best.getFilledQuantity() > 0
			? (int) (best.getSpent() / best.getFilledQuantity())
			: best.getPrice();
	}

	private static String firstBuyTimeIso(OfferRecord best)
	{
		long firstBuyMillis = best.getCreatedAtMillis() > 0
			? best.getCreatedAtMillis()
			: best.getEffectiveLastActivityAtMillis();
		return firstBuyMillis > 0 ? java.time.Instant.ofEpochMilli(firstBuyMillis).toString() : null;
	}

	/**
	 * Store-derived projection of the Active Flips tab: live sell offers plus awaiting-sale
	 * inventory lots, both sourced from the offer store so the panel has no maintained cache
	 * of its own. {@code enrichmentByItemId} layers in the last backend snapshot (recommended
	 * sell price, P&L) without it ever being the source of truth for what's actually live.
	 */
	/** Quantity already sold on live sell offers for {@code itemId} (see OfferStore). */
	public int getLiveSellFilledQuantity(int itemId)
	{
		return offerStore.liveSellFilledQuantity(itemId);
	}

	public List<ActiveFlip> getProjectedActiveFlips(Map<Integer, ActiveFlip> enrichmentByItemId)
	{
		List<OfferRecord> liveSellOffers = new ArrayList<>();
		Set<Integer> liveSellItemIds = new HashSet<>();
		for (OfferRecord r : offerStore.liveOffers())
		{
			if (!r.isBuy() && r.getSlot() != null)
			{
				liveSellOffers.add(r);
				liveSellItemIds.add(r.getItemId());
			}
		}

		Map<Integer, Integer> inventoryCounts = new HashMap<>();
		for (int itemId : getInventoryFlipItemIds())
		{
			int count = getInventoryCountSnapshot(itemId);
			if (count > 0)
			{
				inventoryCounts.put(itemId, count);
			}
		}

		List<AwaitingSaleLot> awaitingSaleLots =
			AwaitingSaleLots.derive(inventoryCounts, this::buyBasisForItem, liveSellItemIds);

		return ActiveFlipProjection.project(liveSellOffers, this::buyBasisForItem, awaitingSaleLots,
			enrichmentByItemId == null ? Collections.emptyMap() : enrichmentByItemId);
	}

	/**
	 * Serialise the current projection to the backend so the website mirrors the Active
	 * Flips tab. Enrichment is intentionally empty: the server only needs which flips
	 * exist, and an empty map avoids taking the panel's lock from the event thread.
	 * Deferred to send time (inside the supplier) so the pushed payload and the
	 * timestamp the endpoint stamps around it describe the same instant.
	 */
	private void pushActiveFlipsSnapshot()
	{
		activeFlipsSnapshotPushService.scheduleSnapshotPush(this::buildActiveFlipsSnapshotPayload);
	}

	/**
	 * Combines the sell-phase projection with live pending buy orders, marking the
	 * latter {@code phase = "buy"} so the backend (and therefore the website) sees
	 * live buy offers too. Returns {@code null} — meaning "skip this push" — when the
	 * combined payload is empty AND the offer store hasn't been seeded from real GE
	 * state yet (e.g. plugin enabled mid-session with no persisted offers, before the
	 * first live GE event), so an unseeded store never erases a real dashboard.
	 *
	 * <p>Runs on the snapshot-push executor, not the client thread, so this reads the
	 * {@code offerStoreSeeded} field (set on the client thread) instead of calling the
	 * client API directly.
	 */
	private List<ActiveFlip> buildActiveFlipsSnapshotPayload()
	{
		List<ActiveFlip> projection = getProjectedActiveFlips(Collections.emptyMap());
		List<PendingOrder> pendingBuys = getPendingBuyOrders();
		List<ActiveFlip> payload = ActiveFlipsSnapshotPayload.combine(projection, pendingBuys);

		return ActiveFlipsSnapshotPayload.isUnobservedEmpty(payload, !offerStoreSeeded) ? null : payload;
	}

	public boolean isAutoRecommendActive()
	{
		return autoRecommendService != null && autoRecommendService.isActive();
	}

	public String getAutoRecommendOverlayMessage()
	{
		return autoRecommendService != null ? autoRecommendService.getLastOverlayMessage() : null;
	}

	public FlipAssistOverlay.FlipAssistStep getFlipAssistOverlayStep()
	{
		return flipAssistOverlay != null ? flipAssistOverlay.getCurrentStep() : FlipAssistOverlay.FlipAssistStep.SELECT_ITEM;
	}

	/**
	 * Count the number of GE slots that are currently occupied (non-EMPTY) in the game.
	 * Returns the flip slot limit if GE offers are not yet available (conservative).
	 */
	public int getFilledGESlotCount()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return getFlipSlotLimit();
		}
		int count = 0;
		for (GrandExchangeOffer offer : offers)
		{
			if (offer.getState() != GrandExchangeOfferState.EMPTY)
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * True when at least one GE slot holds uncollected items or coins. Used to
	 * suppress collection prompts once everything has been collected (all slots EMPTY).
	 */
	public boolean hasCollectableGEOffers()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return false;
		}
		for (GrandExchangeOffer offer : offers)
		{
			if (offer.getState() != GrandExchangeOfferState.EMPTY && offer.getQuantitySold() > 0)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Get the inventory count for a specific item (delegate to ActiveFlipTracker).
	 */
	public int getInventoryCountForItem(int itemId)
	{
		return activeFlipTracker.getInventoryCountForItem(itemId);
	}

	/**
	 * Inventory count for an item from the last client-thread snapshot — safe to
	 * read from any thread (e.g. the Swing EDT), unlike getInventoryCountForItem
	 * which touches client-only API. Keyed by canonical id; a backend flip's item
	 * id is already the canonical GE id.
	 */
	public int getInventoryCountSnapshot(int itemId)
	{
		return inventoryFlipItemCounts.getOrDefault(itemId, 0);
	}

	public String getItemName(int itemId)
	{
		return ItemUtils.getItemName(itemManager, itemId);
	}

	/**
	 * Store recommended sell price when user views/acts on a flip recommendation
	 */
	public void setRecommendedSellPrice(int itemId, int recommendedSellPrice)
	{
		session.setRecommendedPrice(itemId, recommendedSellPrice);
	}

	/**
	 * Get the current flip assist step for GE button highlighting
	 */
	public FlipAssistOverlay.FlipAssistStep getFlipAssistStep()
	{
		if (flipAssistOverlay == null || flipAssistOverlay.getFocusedFlip() == null)
		{
			return null;
		}
		return flipAssistOverlay.getCurrentStep();
	}

	/**
	 * Check if flip assist is active with a focused flip
	 */
	public boolean isFlipAssistActive()
	{
		return flipAssistOverlay != null && flipAssistOverlay.getFocusedFlip() != null;
	}

	/**
	 * Calculate competitiveness of an offer compared to real-time wiki prices.
	 *
	 * For BUY offers: competitive if player price >= insta-sell (low) price
	 * For SELL offers: competitive if player price <= insta-buy (high) price
	 *
	 * This shows if your offer is "in the margin" and likely to fill.
	 */
	public OfferCompetitiveness calculateCompetitiveness(OfferRecord record)
	{
		if (record == null)
		{
			return OfferCompetitiveness.UNKNOWN;
		}
		return calculateCompetitiveness(record.getItemId(), record.getPrice(), record.isBuy());
	}

	/**
	 * Competitiveness from the three facts a price check actually needs. The GE slot reports all
	 * of them, so a render pass can decide a border without a tracked record existing.
	 */
	public OfferCompetitiveness calculateCompetitiveness(int itemId, int price, boolean isBuy)
	{
		// Try to get real-time wiki prices first
		WikiPrice wikiPrice = apiClient.getWikiPrice(itemId);

		if (wikiPrice != null)
		{
			int targetPrice = isBuy ? wikiPrice.instaSell : wikiPrice.instaBuy;
			return compareOfferPrice(price, targetPrice, isBuy);
		}

		// Fallback to GE guide price if real-time prices unavailable.
		// getItemPrice requires the client thread — return UNKNOWN if called off-thread.
		if (!client.isClientThread())
		{
			return OfferCompetitiveness.UNKNOWN;
		}
		int guidePrice = itemManager.getItemPrice(itemId);
		if (guidePrice <= 0)
		{
			return OfferCompetitiveness.UNKNOWN;
		}

		return compareOfferPrice(price, guidePrice, isBuy);
	}

	/**
	 * Compare offer price against target price to determine competitiveness.
	 * Buy offers are competitive if price >= target, sell offers if price <= target.
	 */
	private OfferCompetitiveness compareOfferPrice(int offerPrice, int targetPrice, boolean isBuy)
	{
		boolean isCompetitive = isBuy ? offerPrice >= targetPrice : offerPrice <= targetPrice;
		return isCompetitive ? OfferCompetitiveness.COMPETITIVE : OfferCompetitiveness.UNCOMPETITIVE;
	}

	/**
	 * Get real-time wiki price for an item (for tooltip display)
	 */
	public WikiPrice getWikiPrice(int itemId)
	{
		return apiClient.getWikiPrice(itemId);
	}

	/**
	 * Trigger a refresh of wiki prices if needed
	 */
	public void refreshWikiPrices()
	{
		if (apiClient.needsWikiPriceRefresh())
		{
			apiClient.fetchWikiPrices();
		}
	}

	/**
	 * Get current buy orders in GE slots (pending or partially filled).
	 * These are buy orders that haven't been fully collected yet.
	 */
	public List<PendingOrder> getPendingBuyOrders()
	{
		List<PendingOrder> pendingOrders = new ArrayList<>();

		for (OfferRecord offer : offerStore.liveOffers())
		{
			// Include all buy orders (pending or partially filled)
			if (offer.isBuy() && offer.getSlot() != null)
			{
				Integer recommendedSellPrice = session.getRecommendedPrice(offer.getItemId());

				PendingOrder pending = new PendingOrder(
					offer.getItemId(),
					offer.getItemName(),
					offer.getTotalQuantity(),
					offer.getFilledQuantity(),
					offer.getPrice(),
					recommendedSellPrice,
					offer.getSlot()
				);

				pendingOrders.add(pending);
			}
		}

		return pendingOrders;
	}
	
	/**
	 * Get all active flip item IDs - items that should show as active flips.
	 * This includes:
	 * 1. Items currently in GE buy slots (pending or filled)
	 * 2. Items currently in GE sell slots (pending sale)
	 * 3. Items collected from GE and still held (waiting to be sold)
	 *
	 * <p>A collected lot the player no longer holds is a finished flip: the collected set is
	 * restored from disk at login and never cleared while empty, so a sold item would
	 * otherwise linger there forever and keep consuming a slot.</p>
	 */
	public Set<Integer> getActiveFlipItemIds()
	{
		Set<Integer> liveOfferItemIds = new HashSet<>();
		for (OfferRecord offer : offerStore.liveOffers())
		{
			liveOfferItemIds.add(offer.getItemId());
		}
		return ActiveFlipItemIds.derive(liveOfferItemIds, session.getCollectedItemIds(),
			inventoryFlipItemCounts, inventorySnapshotKnown);
	}


	@Override
	protected void startUp() throws Exception
	{
		log.debug("FlipSmart started!");
		overlayManager.add(geOverlay);
		overlayManager.add(geSlotOverlay);
		overlayManager.add(flipAssistOverlay);
		overlayManager.add(inventoryHighlightOverlay);
		clientThread.invoke(geSlotDecorator::reconcile);

		// Initialize Flip Assist input listener for hotkey support
		flipAssistInputListener = new FlipAssistInputListener(client, clientThread, config, flipAssistOverlay);
		keyManager.registerKeyListener(flipAssistInputListener);

		// GE search suggestion injector (clickable "FlipSmart item" shortcut row)
		geSearchSuggestion = new GeSearchSuggestion(client, config, flipAssistOverlay);

		// Initialize Flip Finder panel
		if (config.showFlipFinder())
		{
			initializeFlipFinderPanel();
		}

		// Start auto-refresh timer for flip finder
		startFlipFinderRefreshTimer();

		// Wire service callbacks and initialize auto-recommend
		PanelRefreshCoalescer refreshCoalescer = new PanelRefreshCoalescer(
			this::scheduleOneShot,
			System::currentTimeMillis,
			() -> { if (flipFinderPanel != null) flipFinderPanel.refresh(); },
			() -> { if (flipFinderPanel != null) flipFinderPanel.refreshActiveFlips(); });
		serviceWiring.wireServiceCallbacks(this, offlineSyncService, activeFlipTracker, refreshCoalescer);
		autoRecommendService = serviceWiring.initializeAutoRecommendService(this, config, flipAssistOverlay, geSlotOverlay, offerStore, notifier, clientUI);
		activeOfferAdvisorService = serviceWiring.initializeActiveOfferAdvisor(this);
		scheduler.startActiveOfferAdvisorTimer(session::isLoggedIntoRunescape, this::pollActiveOfferAdvisor);
		scheduler.startActiveFlipsSnapshotTimer(session::isLoggedIntoRunescape, () ->
		{
			// Periodic safety net: repairs drift from any dropped event push and
			// refreshes the server-side TTL. Dedup means an idle account with an
			// unchanged board sends nothing, so this costs no traffic when quiet.
			activeFlipsSnapshotPushService.invalidateDedup();
			pushActiveFlipsSnapshot();
		});
		exitTradesController = serviceWiring.initializeExitTradesController(this, flipAssistOverlay, geSlotOverlay, offerStore);
		manualAdjustmentTracker = serviceWiring.initializeManualAdjustmentTracker(this, config, flipAssistOverlay,
			geSlotOverlay, inventoryHighlightOverlay, session, grandExchangeTracker, activeOfferAdvisorService, offerStore);
		grandExchangeTracker.setOfferStore(offerStore);
		serviceWiring.wireTransactionLogger(this, session, offerStore, roundTripLedger);
		serviceWiring.wireGrandExchangeTrackerCallbacks(this, grandExchangeTracker, autoRecommendService, geHistoryService,
			offerStore, refreshCoalescer);

		// Build the event router now that all collaborators exist
		eventRouter = new EventRouter(this, client, config, session, webhookSyncService,
			offlineSyncService, bankSnapshotService, geHistoryService, geOfferDescriptionService,
			grandExchangeTracker, offerStore);


		// Sync webhook config to backend if configured
		webhookSyncService.syncIfChanged();

		// Note: Cash stack and RSN will be synced when player logs in via onGameStateChanged
		// Don't access client data during startup - must be on client thread
	}

	public void highlightSlotForItem(int itemId)
	{
		geSlotOverlay.clearAllAdjustmentHighlights();
		for (OfferRecord offer : offerStore.liveOffers())
		{
			if (offer.getItemId() == itemId && offer.getSlot() != null)
			{
				geSlotOverlay.setAdjustmentHighlight(offer.getSlot(), 0);
				return;
			}
		}
	}

	public void updateInventoryHighlightForFocus(FocusedFlip focus)
	{
		// The overlay canonicalizes item ids, which RuneLite requires on the client thread.
		// Clear + add run together so a rapid focus change can't leave a stale highlight.
		clientThread.invoke(() ->
		{
			inventoryHighlightOverlay.clearAll();
			if (focus != null && focus.isSelling())
			{
				inventoryHighlightOverlay.addHighlight(focus.getItemId());
			}
		});
	}

	public void handleAutoRecommendFocusChanged(FocusedFlip focus)
	{
		flipAssistOverlay.setFocusedFlip(focus);
		updateInventoryHighlightForFocus(focus);
		if (focus != null)
		{
			geSlotOverlay.clearAllAdjustmentHighlights();
			log.debug("Auto-recommend focus set: {} {} @ {} gp",
				focus.getStep(), focus.getItemName(), focus.getCurrentStepPrice());
			// Keep panel focus in sync so the active flip refresh cycle doesn't
			// re-create a stale sell overlay for a previously focused item. Run
			// synchronously (we are already on the EDT, like setFocusedFlip above):
			// deferring this left currentFocus stale for one EDT turn, during which
			// the active-flip refresh re-emitted the old sell focus and clobbered a
			// just-set buy after a sell order was placed.
			if (flipFinderPanel != null)
			{
				flipFinderPanel.setExternalFocus(focus);
			}
		}
		else
		{
			// Don't call flipAssistOverlay.clearFocus() here — setFocusedFlip(null) above
			// already clears the focused flip, and clearFocus() would also clear
			// autoStatusMessage which destroys the "Waiting for flips" overlay message
			// set by promptCollection() via invokeOverlayMessageCallback.

			// Clear panel focus to prevent the active flip refresh cycle
			// from re-creating the sell overlay after a sell order was placed
			if (flipFinderPanel != null)
			{
				SwingUtilities.invokeLater(() -> flipFinderPanel.clearFocus());
			}
		}
	}

	public void handleGETrackerFocusChanged(FocusedFlip focus)
	{
		flipAssistOverlay.setFocusedFlip(focus);
		updateInventoryHighlightForFocus(focus);
		if (flipFinderPanel != null)
		{
			SwingUtilities.invokeLater(() -> flipFinderPanel.setExternalFocus(focus));
		}
	}

	/**
	 * Mark a buy as Flip Finder-sourced when an order is submitted for the focused item.
	 * Fired for BOTH manual and Auto placements (via onOrderSubmitted, which is not skipped
	 * during Auto), so the free-tier cap counts Auto-placed buys too. Manual GE listings of a
	 * non-focused item never match, so they never count.
	 */
	public void handleOrderSubmittedForSourcing(int itemId, boolean isBuy)
	{
		if (!isBuy)
		{
			return;
		}
		FocusedFlip focusedFlip = flipAssistOverlay.getFocusedFlip();
		if (focusedFlip != null && focusedFlip.getItemId() == itemId && focusedFlip.isBuying())
		{
			session.markFlipFinderSourced(itemId, System.currentTimeMillis());
		}
	}

	public void handleGETrackerFocusClear(int itemId, boolean isBuy)
	{
		FocusedFlip focusedFlip = flipAssistOverlay.getFocusedFlip();
		if (focusedFlip == null || focusedFlip.getItemId() != itemId)
		{
			return;
		}
		boolean stepMatches = (isBuy && focusedFlip.isBuying()) || (!isBuy && focusedFlip.isSelling());
		if (stepMatches)
		{
			log.debug("Clearing Flip Assist focus - order submitted for {} ({})",
				focusedFlip.getItemName(), isBuy ? "BUY" : "SELL");
			flipAssistOverlay.clearFocus();
			updateInventoryHighlightForFocus(null);
			if (flipFinderPanel != null)
			{
				SwingUtilities.invokeLater(() -> flipFinderPanel.clearFocus());
			}
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("FlipSmart stopped!");

		// Persist refresh token on shutdown to prevent session loss
		String currentRefreshToken = apiClient.getRefreshToken();
		if (currentRefreshToken != null && !currentRefreshToken.isEmpty())
		{
			configManager.setConfiguration(CONFIG_GROUP, "refreshToken", currentRefreshToken);
			log.debug("Persisted refresh token on shutdown");
		}

		// Persist offer state before shutting down (handles cases where client is closed without logout)
		// Try multiple RSN sources: session → lastKnownRsn → config
		String rsnForPersistence = session.getRsn();
		if (rsnForPersistence == null || rsnForPersistence.isEmpty())
		{
			rsnForPersistence = lastKnownRsn;
		}
		if (rsnForPersistence == null || rsnForPersistence.isEmpty())
		{
			rsnForPersistence = configManager.getConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY);
		}
		if (rsnForPersistence != null && !rsnForPersistence.isEmpty())
		{
			session.setRsn(rsnForPersistence);
		}
		if (!offerStore.export().isEmpty())
		{
			offlineSyncService.persistOfferState();
			log.debug("Persisted offer state on shutdown for {}", rsnForPersistence);
		}
		
		overlayManager.remove(geOverlay);
		overlayManager.remove(geSlotOverlay);
		overlayManager.remove(flipAssistOverlay);
		overlayManager.remove(inventoryHighlightOverlay);
		clientThread.invoke(geSlotDecorator::shutDownRevert);
		
		// Unregister Flip Assist input listener
		if (flipAssistInputListener != null)
		{
			keyManager.unregisterKeyListener(flipAssistInputListener);
			flipAssistInputListener = null;
		}
		geSearchSuggestion = null;
		
		// Remove flip finder panel
		if (flipFinderNavButton != null)
		{
			clientToolbar.removeNavigation(flipFinderNavButton);
		}
		
		// Shutdown flip finder panel (cleanup device auth polling, etc.)
		if (flipFinderPanel != null)
		{
			flipFinderPanel.shutdown();
		}
		
		// Stop auto-refresh timer
		scheduler.stopFlipFinderRefreshTimer();

		// Stop all pending one-shot timers
		scheduler.stopAllOneShotTimers();


		// Stop auto-recommend service and timer
		scheduler.stopAutoRecommendRefreshTimer();
		if (autoRecommendService != null)
		{
			persistAutoRecommendState();
			autoRecommendService.stop();
		}

		// Stop active-offer advisor poll timer
		scheduler.stopActiveOfferAdvisorTimer();

		// Clear API client cache
		apiClient.clearCache();

		// Shut down the trade-station snapshot pusher's executor.
		tradeStationSlotPushService.shutdown();

		// Stop the heartbeat FIRST: it fires on its own Timer thread, so a tick landing
		// after the executor below shuts down would throw RejectedExecutionException
		// out of TimerTask.run() and kill the Timer thread.
		scheduler.stopActiveFlipsSnapshotTimer();
		activeFlipsSnapshotPushService.shutdown();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		eventRouter.onConfigChanged(configChanged);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		eventRouter.onGameStateChanged(gameStateChanged);
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		eventRouter.onWorldChanged(event);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		eventRouter.onGameTick(event);
	}

	/**
	 * Game-tick handler body. Kept on the plugin because it mutates plugin-private
	 * volatile state ({@code atGrandExchange}, {@code rsnSyncPending}) and calls
	 * private RSN sync logic. The router delegates straight here.
	 */
	public void onGameTickHandler(GameTick event)
	{
		Player localPlayer = client.getLocalPlayer();
		atGrandExchange = localPlayer != null && localPlayer.getWorldLocation().getRegionID() == GE_REGION_ID;
		if (atGrandExchange)
		{
			geSlotDecorator.reconcile();
		}

		// On the first LOGGED_IN tick the local player is sometimes not yet
		// populated, so syncRSN()'s early-return fires and we'd be stuck with
		// either no RSN or (worse) the previous account's name. Retry here on
		// each tick until the live name is captured. Issue #556.
		if (rsnSyncPending && localPlayer != null && localPlayer.getName() != null && !localPlayer.getName().isEmpty())
		{
			syncRSN();
		}

		geHistoryService.onGameTick();

		grandExchangeTracker.retryPendingSellFocusTick();

		releaseOfferLockIfSetupClosed();

		// Heal transient auto-mode blanks within ~1s instead of waiting for the next GE offer
		// event. Cheap, deterministic, deduped, and gated on the offer-screen lock internally.
		if (autoRecommendService != null)
		{
			autoRecommendService.onGameTickReresolve();
		}
	}

	private void releaseOfferLockIfSetupClosed()
	{
		if (autoRecommendService == null || autoRecommendService.getLockedItemId() == null)
		{
			return;
		}
		Widget setupDesc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		if (setupDesc == null || setupDesc.isHidden())
		{
			autoRecommendService.releaseOfferLock();
			autoRecommendService.refreshFocusAfterUnlock();
		}
	}

	public void handleLogoutState()
	{
		pushFinalCapitalSnapshot();
		session.onLogout();
		offlineSyncService.persistOfferState();
		geHistoryService.reset();
		persistAutoRecommendState();
		persistExitTradesState();
		geSlotDecorator.revertAll();

		// Stop auto-recommend on logout
		if (autoRecommendService != null && autoRecommendService.isActive())
		{
			autoRecommendService.stop();
			if (flipFinderPanel != null)
			{
				flipFinderPanel.updateAutoRecommendButton(false);
			}
		}
		stopAutoRecommendRefreshTimer();

		// Clear manual adjustment timers on logout
		if (manualAdjustmentTracker != null)
		{
			manualAdjustmentTracker.clearAll();
		}

		if (flipFinderPanel != null)
		{
			SwingUtilities.invokeLater(() -> flipFinderPanel.showLoggedOutOfGameState());
		}
	}

	/**
	 * Report the final inventory coins as the player leaves, so the web card does not
	 * sit on a poll-interval-old reading for as long as they stay offline.
	 *
	 * Must run before {@link PlayerSession#onLogout()}, which clears the RSN. The
	 * logged-in guard matters: this same handler runs for the login screen shown at
	 * startup, where the cash stack is still zero and reporting it would overwrite a
	 * real stored value. Async, so logout is never delayed.
	 */
	private void pushFinalCapitalSnapshot()
	{
		if (!session.isLoggedIntoRunescape())
		{
			return;
		}

		getCurrentRsnSafe().ifPresent(rsn -> apiClient.pushRsnCapitalAsync(rsn, session.getCurrentCashStack()));
	}

	public void handleLoggedInState()
	{
		log.debug("Player logged in");
		updateMembersWorldCache();
		updateAccountTypeCache();
		session.onLoggedIn();
		syncRSN();

		// If syncRSN couldn't capture the live name yet (player object not
		// populated on the first LOGGED_IN tick), fall back to the persisted
		// last-known RSN so offer preloading and the entitlements call below
		// have *something* to work with. onGameTick will refresh to the live
		// name as soon as it's available. Issue #556.
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			String persistedRsn = configManager.getConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY);
			if (persistedRsn != null && !persistedRsn.isEmpty())
			{
				session.setRsn(persistedRsn);
				lastKnownRsn = persistedRsn;
				log.debug("Using persisted RSN fallback: {}", persistedRsn);
			}
		}

		updateCashStack();

		apiClient.fetchWikiPrices();

		apiClient.fetchEntitlementsAsync(getCurrentRsnSafe().orElse(null)).thenAccept(isPremium -> {
			log.debug("User premium status: {}", isPremium);
			if (flipFinderPanel != null)
			{
				SwingUtilities.invokeLater(() -> flipFinderPanel.updatePremiumStatus());
			}

			// Pull webhook config after auth is confirmed
			webhookSyncService.pullFromBackend();
		});

		// Collected items are no longer restored here: they are derived from the persisted offer
		// records and live inventory, rebuilt by the offline sync scheduled below. That sync runs
		// on a delay precisely because the inventory container is not reliably loaded on the first
		// LOGGED_IN tick, which is what a restore at this point would have had to read.

		// Restore the Flip Finder-sourced set so the free-tier cap survives a restart.
		offlineSyncService.restoreFlipFinderSourcedItems();

		// Preload persisted offers into the session BEFORE login burst fires.
		// This ensures createWithPreservedTimestamps() finds the existing offer
		// with its original timestamp, giving us accurate timers from the start.
		offlineSyncService.preloadPersistedOffers();
		offerStoreSeeded = true;

		restoreAutoRecommendState();
		restoreExitTradesState();

		// Start the refresh timer if not already running (needed for manual adjustment checks)
		if (!scheduler.isAutoRecommendRefreshTimerRunning())
		{
			startAutoRecommendRefreshTimer();
		}

		// Schedule offline sync after a delay to ensure all GE events have been processed
		if (!session.isOfflineSyncCompleted())
		{
			scheduleOneShot(PluginScheduler.OFFLINE_SYNC_DELAY_MS, offlineSyncService::syncOfflineFills);
		}

		if (flipFinderPanel != null)
		{
			flipFinderPanel.refresh();
		}
	}
	
	
	/**
	 * Schedule panel refresh and stale flip cleanup after offline sync.
	 */
	public void schedulePostSyncTasks()
	{
		// Backfill any missing timestamps from backend before panel refresh
		backfillMissingTimestamps();

		if (flipFinderPanel != null)
		{
			scheduleOneShot(PluginScheduler.PANEL_REFRESH_DELAY_MS, () -> flipFinderPanel.refresh());
		}

		scheduleOneShot(PluginScheduler.STALE_FLIP_CLEANUP_DELAY_MS, this::performStaleFlipCleanup);

		if (autoRecommendService != null && autoRecommendService.isActive())
		{
			scheduleOneShot(PluginScheduler.AUTO_RECOMMEND_REEVALUATE_DELAY_MS, this::reevaluateAutoRecommendAfterLogin);
		}
	}

	/**
	 * Correct offer timestamps using backend active flips as source of truth.
	 * Always runs — if the backend has an OLDER timestamp than the local one,
	 * the local one was likely reset (login burst, rebuild, hop) and should
	 * be replaced with the backend's more accurate value.
	 */
	private void backfillMissingTimestamps()
	{
		List<OfferRecord> tracked = offerStore.liveOffers();
		if (tracked.isEmpty())
		{
			return;
		}

		log.debug("Checking offer timestamps against backend active flips");
		String rsn = getCurrentRsnSafe().orElse(null);
		apiClient.getActiveFlipsAsync(rsn).thenAccept(response ->
		{
			if (response == null || response.getActiveFlips() == null)
			{
				return;
			}

			Map<Integer, ActiveFlip> flipsByItem = new HashMap<>();
			for (ActiveFlip flip : response.getActiveFlips())
			{
				flipsByItem.put(flip.getItemId(), flip);
			}

			int corrected = 0;
			for (OfferRecord offer : offerStore.liveOffers())
			{
				if (correctOfferTimestamp(offer, flipsByItem))
				{
					corrected++;
				}
			}

			if (corrected > 0)
			{
				log.debug("Corrected {} offer timestamps from backend active flips", corrected);
			}
		}).exceptionally(e ->
		{
			log.debug("Failed to check timestamps against backend: {}", e.getMessage());
			return null;
		});
	}

	/**
	 * Backfill a missing timestamp from the backend.
	 * Only fills in timestamps that are 0 (unknown) — does NOT override existing
	 * local timestamps, since they're more accurate (set at offer placement time).
	 * The backend's last_buy_time can be from older transactions for the same item.
	 */
	private boolean correctOfferTimestamp(OfferRecord offer, Map<Integer, ActiveFlip> flipsByItem)
	{
		if (offer.getCreatedAtMillis() > 0)
		{
			return false; // Local timestamp exists — trust it
		}

		ActiveFlip flip = flipsByItem.get(offer.getItemId());
		if (flip == null)
		{
			return false;
		}

		long backendMs = (!offer.isBuy() && flip.getSellPlacedTime() != null)
			? TimeUtils.parseIsoToMillis(flip.getSellPlacedTime())
			: TimeUtils.parseIsoToMillis(flip.getLastBuyTime());

		if (backendMs > 0)
		{
			log.debug("Backfilled missing timestamp for {} from backend ({}m ago)",
				offer.getItemName(), (System.currentTimeMillis() - backendMs) / 60000);
			offerStore.correctCreatedAt(offer.getOfferId(), backendMs);
			return true;
		}
		return false;
	}

	public boolean isClientThread()
	{
		return client.isClientThread();
	}

	public void runOnClientThread(Runnable r)
	{
		clientThread.invokeLater(r);
	}

	private void performStaleFlipCleanup()
	{
		if (!offerStore.liveOffers().isEmpty() || !session.getCollectedItemIds().isEmpty())
		{
			activeFlipTracker.cleanupStaleActiveFlips();
			scheduleOneShot(PluginScheduler.INVENTORY_VALIDATION_DELAY_MS, () ->
				clientThread.invokeLater(activeFlipTracker::validateInventoryQuantities));
		}
		else
		{
			log.debug("Skipping cleanup - no GE offers or collected items detected, may not be safe");
		}
	}

	private void reevaluateAutoRecommendAfterLogin()
	{
		boolean hasStaleOffers = autoRecommendService.reevaluateAfterLogin();
		if (hasStaleOffers)
		{
			scheduleOneShot(10_000, this::runEarlyAdjustmentCheck);
		}
	}

	private void runEarlyAdjustmentCheck()
	{
		PlayerSession sess = getSession();
		if (sess != null)
		{
			autoRecommendService.checkAdjustmentTimers(
				flipFinderPanel != null ? flipFinderPanel.getCurrentRecommendations() : null
			);
			autoRecommendService.checkSellAdjustmentTimers();
		}
	}

	
	/**
	 * Sync the player's RSN with the API and store locally.
	 *
	 * On the first LOGGED_IN tick the local player can still be null or
	 * unnamed; in that case we set rsnSyncPending so onGameTick retries
	 * until the live name is captured. Issue #556.
	 */
	private void syncRSN()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			log.debug("syncRSN: getLocalPlayer() is null, will retry on next tick");
			rsnSyncPending = true;
			return;
		}

		String rsn = localPlayer.getName();
		if (rsn == null || rsn.isEmpty())
		{
			log.debug("syncRSN: player name is null or empty, will retry on next tick");
			rsnSyncPending = true;
			return;
		}

		rsnSyncPending = false;
		String previous = session.getRsn();
		session.setRsn(rsn);
		lastKnownRsn = rsn;
		configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY, rsn);
		if (previous != null && !previous.equals(rsn))
		{
			log.debug("RSN switched: {} -> {}", previous, rsn);
		}
		else
		{
			log.debug("RSN synced: {}", rsn);
		}
		updateAccountTypeCache();
		pushRsnIfNeeded(rsn);
	}

	/**
	 * Push the RSN to the backend only when it has not been confirmed pushed
	 * this client session — LOGGED_IN fires on every world hop, not just logins.
	 */
	private void pushRsnIfNeeded(String rsn)
	{
		if (!rsnSyncGate.shouldPush(rsn))
		{
			log.debug("RSN already pushed this session, skipping: {}", rsn);
			return;
		}
		apiClient.updateRSN(rsn, accountType).thenAccept(confirmed ->
		{
			if (Boolean.TRUE.equals(confirmed))
			{
				rsnSyncGate.markPushed(rsn);
			}
		});
	}

	/**
	 * Get the current RSN.
	 *
	 * When the player is logged in, the live client is authoritative — the
	 * cache may still hold the previous account's name from a quick character
	 * switch (issue #556). When offline, fall back to the cached or persisted
	 * value so background work (offer persistence, config keys) still resolves.
	 */
	public Optional<String> getCurrentRsnSafe()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			Player localPlayer = client.getLocalPlayer();
			if (localPlayer != null && localPlayer.getName() != null && !localPlayer.getName().isEmpty())
			{
				String liveRsn = localPlayer.getName();
				String cached = session.getRsn();
				if (!liveRsn.equals(cached))
				{
					log.debug("RSN refreshed from client: {} -> {}", cached, liveRsn);
					session.setRsn(liveRsn);
					lastKnownRsn = liveRsn;
					configManager.setConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY, liveRsn);
				}
				return Optional.of(liveRsn);
			}
			// Logged in but the player object isn't ready yet — fall through
			// to the cache so the request still has *some* RSN. onGameTick
			// will refresh once the live name is available.
		}

		String cached = session.getRsn();
		if (cached != null && !cached.isEmpty())
		{
			return Optional.of(cached);
		}

		log.warn("Unable to determine RSN - transactions will be recorded without RSN");
		return Optional.empty();
	}
	
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		eventRouter.onItemContainerChanged(event);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		eventRouter.onScriptPostFired(event);
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerEvent)
	{
		eventRouter.onGrandExchangeOfferChanged(offerEvent);
	}

	/**
	 * GE offer-changed handler body. Kept on the plugin because it builds
	 * {@link GrandExchangeTracker.OfferContext}, a package-private type. The router
	 * delegates straight here.
	 */
	public void onGrandExchangeOfferChangedHandler(GrandExchangeOfferChanged offerEvent)
	{
		final int slot = offerEvent.getSlot();
		final GrandExchangeOffer offer = offerEvent.getOffer();

		if (client.getGameState() != GameState.LOGGED_IN && offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			return;
		}

		// Warm the Trade Station "Import from RuneLite" cache (issue #683
		// AC7). Captured here on the client thread; pushed off-thread.
		tradeStationSlotPushService.scheduleSnapshotPush(
			tradeStationSlotPushService.readCurrentSlotIds());

		int itemId = offer.getItemId();
		int quantitySold = offer.getQuantitySold();
		int totalQuantity = offer.getTotalQuantity();
		int price = offer.getPrice();
		int spent = offer.getSpent();
		GrandExchangeOfferState state = offer.getState();

		String itemName = ItemUtils.getItemName(itemManager, itemId);

		// Check if this is during the login burst window
		int currentTick = client.getTickCount();
		boolean isLoginBurst = (currentTick - session.getLastLoginTick()) <= GE_LOGIN_BURST_WINDOW;

		if (isLoginBurst && state != GrandExchangeOfferState.EMPTY)
		{
			log.debug("Login burst: initializing tracking for slot {} with {} items sold", slot, quantitySold);
			// Seed the offer store baseline (cumulative fill/spend) without recording a
			// transaction, so the first live event after the burst records only the delta.
			offerStore.apply(
				com.flipsmart.trading.OfferEventMapper.toSignal(
					slot, state, itemId, itemName, totalQuantity, price, quantitySold, spent),
				System.currentTimeMillis());
			offerStoreSeeded = true;
			pushActiveFlipsSnapshot();
			return;
		}

		grandExchangeTracker.handleOfferChanged(GrandExchangeTracker.OfferContext.builder()
			.slot(slot)
			.itemId(itemId)
			.itemName(itemName)
			.quantitySold(quantitySold)
			.totalQuantity(totalQuantity)
			.price(price)
			.spent(spent)
			.isBuy(OfferSignal.isBuyState(state))
			.state(state)
			.build());
		pushActiveFlipsSnapshot();
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		eventRouter.onVarClientIntChanged(event);
	}

	/**
	 * VarClientInt handler body. Kept on the plugin because it touches plugin-private
	 * listener/suggestion state ({@code flipAssistInputListener}, {@code geSearchSuggestion})
	 * created during startUp. The router delegates straight here.
	 */
	public void onVarClientIntChangedHandler(VarClientIntChanged event)
	{
		if (event.getIndex() == FlipAssistInputListener.VARCLIENT_INPUT_TYPE && flipAssistInputListener != null)
		{
			int inputType = client.getVarcIntValue(FlipAssistInputListener.VARCLIENT_INPUT_TYPE);
			flipAssistInputListener.updateInputType(inputType);

			// When the GE item-search dialog opens, inject the clickable
			// "FlipSmart item" shortcut row. Deferred so the search-results
			// widget is built before we add children to it.
			if (inputType == FlipAssistInputListener.INPUT_TYPE_GE_ITEM_SEARCH && geSearchSuggestion != null)
			{
				clientThread.invokeLater(geSearchSuggestion::showSuggestedItemInSearch);
			}
		}
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged event)
	{
		eventRouter.onVarClientStrChanged(event);
	}

	/**
	 * VarClientStr handler body. Kept on the plugin because it touches the
	 * plugin-private {@code flipAssistInputListener} created during startUp. The
	 * router delegates straight here.
	 */
	public void onVarClientStrChangedHandler(VarClientStrChanged event)
	{
		// Keep the listener's cached search text fresh so its EDT-side consume
		// gate (suppressing the stray hotkey char in GE item search) can compare
		// what's typed against the focused item name.
		if (event.getIndex() == FlipAssistInputListener.VARCLIENT_INPUT_TEXT && flipAssistInputListener != null)
		{
			flipAssistInputListener.updateInputText(client.getVarcStrValue(FlipAssistInputListener.VARCLIENT_INPUT_TEXT));
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		eventRouter.onWidgetLoaded(event);
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		eventRouter.onScriptCallbackEvent(event);
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		eventRouter.onBeforeRender(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		eventRouter.onMenuOptionClicked(event);
	}


	/**
	 * Initialize the Flip Finder panel and add it to the sidebar
	 */
	private void initializeFlipFinderPanel()
	{
		flipFinderPanel = new FlipFinderPanel(config, apiClient, itemManager, this, configManager)
		{
			@Override
			protected Integer getCashStack()
			{
				if (config.cashstackOverrideEnabled())
				{
					java.util.OptionalInt override = GpUtils.parseGp(config.cashstackOverrideAmount());
					if (override.isPresent())
					{
						return override.getAsInt();
					}
				}
				return session.getCurrentCashStack() > 0 ? session.getCurrentCashStack() : null;
			}

			@Override
			protected Integer getInventoryGp()
			{
				// Never the override: this is the reported figure, not the one
				// recommendations are built from. Zero is reported as zero.
				return session.isLoggedIntoRunescape() ? session.getCurrentCashStack() : null;
			}

			@Override
			protected Integer getFilledSlots()
			{
				return getFilledGESlotCount();
			}
		};
		
		// Connect Flip Assist focus callback
		flipFinderPanel.setOnFocusChanged(focus -> {
			flipAssistOverlay.setFocusedFlip(focus);
			if (focus != null)
			{
				log.debug("Flip Assist focus set: {} {} - {} @ {} gp", 
					focus.getStep(),
					focus.getItemName(),
					focus.getCurrentStepQuantity(),
					focus.getCurrentStepPrice());
			}
			else
			{
				log.debug("Flip Assist focus cleared");
				flipAssistOverlay.clearFocus();
			}
		});

		// Connect auth success callback to sync RSN after Discord login
		flipFinderPanel.setOnAuthSuccess(() -> {
			// Sync RSN to API if we have one (player is logged in)
			if (session.getRsn() != null && !session.getRsn().isEmpty())
			{
				log.debug("Auth success callback - syncing RSN: {}", session.getRsn());
				// A fresh auth is a new backend session; the old pushed state no longer proves the binding exists
				rsnSyncGate.reset();
				pushRsnIfNeeded(session.getRsn());
			}
			else
			{
				log.debug("Auth success callback - no RSN to sync yet");
			}
		});

		// Try to load custom icon from resources
		BufferedImage iconImage = null;
		try
		{
			iconImage = net.runelite.client.util.ImageUtil.loadImageResource(getClass(), "/flip_finder_icon.png");
		}
		catch (Exception e)
		{
			log.debug("Could not load flip finder icon, using default icon");
		}

		// If custom icon not found, create a default one
		if (iconImage == null)
		{
			iconImage = createDefaultIcon();
		}

		// Create navigation button
		flipFinderNavButton = net.runelite.client.ui.NavigationButton.builder()
			.tooltip("FlipSmart")
			.icon(iconImage)
			.priority(7)
			.panel(flipFinderPanel)
			.build();

		clientToolbar.addNavigation(flipFinderNavButton);
		log.debug("Flip Finder panel initialized");
	}

	/**
	 * Create a default icon for the Flip Finder button
	 */
	private BufferedImage createDefaultIcon()
	{
		// Create a simple default icon
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = image.createGraphics();
		g.setColor(java.awt.Color.ORANGE);
		g.fillRect(2, 2, 12, 12);
		g.setColor(java.awt.Color.WHITE);
		g.drawString("F", 5, 12);
		g.dispose();
		return image;
	}

	/**
	 * Update the player's current cash stack from inventory
	 */
	public void updateCashStack()
	{
		ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
		if (inventory == null)
		{
			session.setCashStack(0);
			inventoryFlipItemIds = Collections.emptySet();
			inventoryFlipItemCounts = Collections.emptyMap();
			inventorySnapshotKnown = false;
			return;
		}

		int totalCash = 0;
		Item[] items = inventory.getItems();

		Set<Integer> currentInventoryIds = new HashSet<>();
		Map<Integer, Integer> currentInventoryCounts = new HashMap<>();
		for (Item item : items)
		{
			if (item.getId() == COINS_ITEM_ID)
			{
				totalCash += item.getQuantity();
			}
			if (item.getId() > 0)
			{
				int canonicalId = itemManager.canonicalize(item.getId());
				currentInventoryIds.add(canonicalId);
				currentInventoryCounts.merge(canonicalId, item.getQuantity(), Integer::sum);
			}
		}
		Map<Integer, Integer> previousInventoryCounts = inventoryFlipItemCounts;
		inventoryFlipItemIds = Collections.unmodifiableSet(currentInventoryIds);
		inventoryFlipItemCounts = Collections.unmodifiableMap(currentInventoryCounts);
		inventorySnapshotKnown = true;

		// An inventory change adds or removes an awaiting-sale lot, so re-derive the
		// Active Flips projection whenever the held-item composition changes. Coins are
		// ignored: they move on every trade but never form an awaiting-sale card.
		if (heldItemsChanged(previousInventoryCounts, currentInventoryCounts))
		{
			if (flipFinderPanel != null)
			{
				flipFinderPanel.onInventoryChanged();
			}
			// Collecting a bought lot changes the awaiting-sale set without any GE offer
			// event, so push here too — otherwise the website can lag up to the 5-minute
			// heartbeat before the collected item appears.
			pushActiveFlipsSnapshot();
		}

		if (totalCash != session.getCurrentCashStack())
		{
			session.setCashStack(totalCash);
			log.debug("Updated cash stack: {}", session.getCurrentCashStack());

			// If cash stack changed significantly and we have a flip finder panel, refresh it
			if (flipFinderPanel != null && totalCash > AUTO_REFRESH_CASH_THRESHOLD)
			{
				// Only auto-refresh if it's been more than the minimum interval since last refresh
				long now = System.currentTimeMillis();
				if (now - session.getLastFlipFinderRefresh() > AUTO_REFRESH_MIN_INTERVAL_MS)
				{
					session.setLastFlipFinderRefresh(now);
					flipFinderPanel.refresh();
				}
			}
		}
	}

	/**
	 * True when the non-coin inventory composition differs between two snapshots.
	 * Awaiting-sale derivation ignores coins, so cash-only movement must not force
	 * a projection re-render.
	 */
	private boolean heldItemsChanged(Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		Map<Integer, Integer> a = new HashMap<>(before);
		Map<Integer, Integer> b = new HashMap<>(after);
		a.remove(COINS_ITEM_ID);
		b.remove(COINS_ITEM_ID);
		return !a.equals(b);
	}

	/**
	 * Start the auto-refresh timer for flip finder
	 */
	private void startFlipFinderRefreshTimer()
	{
		scheduler.startFlipFinderRefreshTimer(
			config.flipFinderRefreshMinutes(),
			session::isLoggedIntoRunescape,
			this::runFlipFinderRefreshBody);
	}

	/**
	 * Restart the flip-finder auto-refresh timer, realigning its next fire to a full
	 * interval from now. Called when the player triggers a manual refresh so the
	 * actual scheduled refresh and the visual countdown reset as one operation.
	 */
	void resetFlipFinderRefreshTimer()
	{
		startFlipFinderRefreshTimer();
	}

	/** Wall-clock instant of the next flip-finder auto-refresh (0 when not running). */
	long getNextFlipFinderRefreshAtMillis()
	{
		return scheduler.getNextFlipFinderRefreshAtMillis();
	}

	/**
	 * Per-tick body for the flip-finder auto-refresh timer. Runs only when the
	 * player is logged in (the scheduler applies that guard before calling).
	 */
	private void runFlipFinderRefreshBody()
	{
		if (flipFinderPanel != null && config.showFlipFinder())
		{
			SwingUtilities.invokeLater(() ->
			{
				log.debug("Auto-refreshing flip finder");
				session.setLastFlipFinderRefresh(System.currentTimeMillis());
				flipFinderPanel.refresh();
			});
		}

		// Check for inactive auto-recommend offers
		if (autoRecommendService != null && autoRecommendService.isActive() && flipFinderPanel != null)
		{
			autoRecommendService.checkInactiveOffers(
				flipFinderPanel.getCurrentRecommendations()
			);
		}
	}

	/**
	 * Create and start a tracked one-shot Swing timer (delegates to the scheduler).
	 */
	public void scheduleOneShot(int delayMs, Runnable action)
	{
		scheduler.scheduleOneShot(delayMs, action);
	}

	/**
	 * Start the auto-recommend refresh timer (2-minute interval).
	 * Fetches fresh recommendations and feeds them to the auto-recommend queue.
	 * Also checks manual adjustment timers when auto-recommend is inactive.
	 */
	void startAutoRecommendRefreshTimer()
	{
		scheduler.startAutoRecommendRefreshTimer(session::isLoggedIntoRunescape, this::runRefreshCycle);
	}

	private void runRefreshCycle()
	{
		boolean autoActive = autoRecommendService != null && autoRecommendService.isActive();

		if (autoActive)
		{
			if (flipFinderPanel != null)
			{
				SwingUtilities.invokeLater(() ->
				{
					log.debug("Auto-recommend refresh cycle");
					flipFinderPanel.refresh();
				});
			}

			autoRecommendService.ensureAllOffersHaveTimers();
			autoRecommendService.checkAdjustmentTimers(
				flipFinderPanel != null ? flipFinderPanel.getCurrentRecommendations() : null);
			autoRecommendService.checkSellAdjustmentTimers();
		}

		if (manualAdjustmentTracker != null)
		{
			manualAdjustmentTracker.checkTimers();
		}
	}

	void stopAutoRecommendRefreshTimer()
	{
		scheduler.stopAutoRecommendRefreshTimer();
	}

	// Re-evaluate promptly on GE offer changes (fill/complete/cancel/new) instead of
	// waiting up to a full 30s cycle, debounced so a burst of slot events polls once.
	public void maybeEventPollAdvisor()
	{
		if (!config.enableActiveOfferAdvisor() || config.flipTimeframe() != FlipSmartConfig.FlipTimeframe.ACTIVE)
		{
			return;
		}
		if (System.currentTimeMillis() - lastAdvisorPollMs >= PluginScheduler.ACTIVE_OFFER_ADVISOR_EVENT_DEBOUNCE_MS)
		{
			pollActiveOfferAdvisor();
		}
	}

	private void pollActiveOfferAdvisor()
	{
		if (!config.enableActiveOfferAdvisor() || config.flipTimeframe() != FlipSmartConfig.FlipTimeframe.ACTIVE)
		{
			return;
		}
		PlayerSession sess = getSession();
		if (sess == null)
		{
			return;
		}
		lastAdvisorPollMs = System.currentTimeMillis();
		List<OfferRecord> liveOffers = offerStore.liveOffers();
		Set<Integer> activeItemIds = new HashSet<>();
		for (OfferRecord o : liveOffers)
		{
			if (o.getState() != OfferState.FILLED)
			{
				activeItemIds.add(o.getItemId());
			}
		}
		if (activeOfferAdvisorService != null)
		{
			activeOfferAdvisorService.reconcile(activeItemIds);
		}
		List<OfferAdviceRequest> requests = new ArrayList<>();
		for (OfferRecord offer : liveOffers)
		{
			if (offer.getState() == OfferState.FILLED)
			{
				continue;
			}
			if (offer.getCreatedAtMillis() <= 0)
			{
				continue;
			}
			if (autoRecommendService != null
				&& Integer.valueOf(offer.getItemId()).equals(autoRecommendService.getLockedItemId()))
			{
				continue;
			}
			Integer dailyVolume = apiClient.getCachedDailyVolume(offer.getItemId());
			WikiPrice market = apiClient.getWikiPrice(offer.getItemId());
			Integer avgBuy = avgBuyPriceFor(offer);
			// Gate the competitive re-prompts + margin-decay exit behind the experimental toggle:
			// relaying the margin/courier only when it's on leaves the base advisor advice unchanged.
			boolean aggressive = config.enableAggressiveAdvisor();
			Integer originalMargin = ActiveOfferAdvisorService.relayedMargin(aggressive,
				autoRecommendService == null ? null : autoRecommendService.getOriginalMargin(offer.getItemId()));
			ActiveOfferAdvisorService.CourierState courier = ActiveOfferAdvisorService.relayedCourier(
				aggressive, activeOfferAdvisorService.getCourierState(offer.getItemId()));
			requests.add(ActiveOfferAdvisorService.buildSnapshot(
				offer, market, avgBuy, dailyVolume, originalMargin, courier));
		}
		if (requests.isEmpty())
		{
			return;
		}
		if (log.isDebugEnabled())
		{
			log.debug("active-offer advisor poll: {} offers (batched)", requests.size());
		}
		// On batch failure, skip this cycle — the next scheduled or event-driven poll retries.
		apiClient.postOfferActionsBatchAsync(requests)
			.thenAccept(batch ->
			{
				if (batch == null || batch.getResults() == null)
				{
					return;
				}
				for (OfferAdviceResult result : batch.getResults())
				{
					if (log.isDebugEnabled())
					{
						log.debug("offer-action {} -> {} newPrice={}",
							result.getItemId(), result.getAction(), result.getNewPrice());
					}
					activeOfferAdvisorService.applyResponse(result.getItemId(), result);
				}
			})
			.exceptionally(ex ->
			{
				if (log.isDebugEnabled())
				{
					log.debug("offer-actions batch poll failed: {}", ex.getMessage());
				}
				return null;
			});
	}

	/**
	 * Average price the user paid for the units they hold. For a sell that is the
	 * matched buy cost from active flips; for a partially-filled buy it is this
	 * offer's own average fill (falling back to the listed price), which the
	 * margin-decay exit (#918 AC2) needs.
	 */
	private Integer avgBuyPriceFor(OfferRecord offer)
	{
		if (!offer.isBuy())
		{
			return BuyPriceLookup.findAverageBuyPriceWithFallback(
				getCurrentActiveFlips(), getCycleBasisForItem(offer.getItemId()),
				offerStore.forItem(offer.getItemId()), offer.getItemId());
		}
		if (offer.getFilledQuantity() > 0 && offer.getSpent() > 0)
		{
			return (int) Math.round(offer.getSpent() / (double) offer.getFilledQuantity());
		}
		return offer.getPrice();
	}

	public void applyActiveOfferSurface(OfferAdviceResponse resp)
	{
		if (resp == null || resp.getNewPrice() == null)
		{
			return;
		}
		PlayerSession sess = getSession();
		if (sess == null)
		{
			return;
		}
		Integer itemId = resp.getItemIdHint();
		if (itemId == null)
		{
			return;
		}
		sess.setRecommendedPrice(itemId, resp.getNewPrice());
		if (autoRecommendService != null)
		{
			OfferRecord offer = findLiveOfferForItem(itemId);
			if (offer != null)
			{
				autoRecommendService.surfaceAdvisorResell(offer, resp.getNewPrice(), resp.getNetProfitEstimate());
			}
		}
	}

	public void clearActiveOfferSurface(int itemId)
	{
		if (autoRecommendService != null)
		{
			autoRecommendService.removeAdvisorResell(itemId);
		}
	}

	private static final long SELL_RECALC_DEBOUNCE_MS = 2000;
	// Once a 12h sell is older than the rung-1 boundary, the /flips/adjustment ladder owns
	// its price; re-anchoring to the fresh overnight estimate here would fight the ladder.
	static final long LADDER_HANDOFF_MINUTES = 360;
	private int last12hRecalcItemId = -1;
	private long last12hRecalcMs;

	static boolean ladderOwnsSell(OfferRecord liveSell, long now)
	{
		if (liveSell == null)
		{
			return false;
		}
		long ageMinutes = (now - liveSell.getEffectiveLastActivityAtMillis()) / 60_000L;
		return ageMinutes >= LADDER_HANDOFF_MINUTES;
	}

	private OfferRecord findLiveSellForItem(int itemId)
	{
		for (OfferRecord o : offerStore.liveOffers())
		{
			if (o.getItemId() == itemId && !o.isBuy()
				&& o.getState() != OfferState.FILLED)
			{
				return o;
			}
		}
		return null;
	}

	public void maybeRecalc12hSellPrice(int itemId)
	{
		if (config.flipTimeframe() != FlipSmartConfig.FlipTimeframe.TWELVE_HOURS)
		{
			return;
		}
		PlayerSession sess = getSession();
		if (sess == null)
		{
			return;
		}
		Integer originalSell = sess.getRecommendedPrice(itemId);
		if (originalSell == null || originalSell <= 0)
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (ladderOwnsSell(findLiveSellForItem(itemId), now))
		{
			return;
		}
		if (itemId == last12hRecalcItemId && (now - last12hRecalcMs) < SELL_RECALC_DEBOUNCE_MS)
		{
			return;
		}
		last12hRecalcItemId = itemId;
		last12hRecalcMs = now;

		WikiPrice market = apiClient.getWikiPrice(itemId);
		if (market == null)
		{
			return;
		}
		Integer dailyVolume = apiClient.getCachedDailyVolume(itemId);
		SellPriceCheckRequest req = SellPriceCheckRequest.builder()
			.itemId(itemId)
			.originalSellPrice(originalSell)
			.currentMarketHigh(market.instaBuy)
			.dailyVolume(dailyVolume == null ? 0 : dailyVolume)
			.timeframe(FlipSmartConfig.FlipTimeframe.TWELVE_HOURS.getApiValue())
			.style(config.flipStyle().getApiValue())
			.rsn(sess.getRsn())
			.build();

		apiClient.postSellPriceCheckAsync(req)
			.thenAccept(resp ->
			{
				if (resp == null)
				{
					return;
				}
				int fresh = resp.getRecommendedSellPrice();
				if (fresh <= 0 || fresh == originalSell)
				{
					return;
				}
				clientThread.invokeLater(() -> applyRecalced12hSellPrice(itemId, fresh));
			})
			.exceptionally(ex ->
			{
				if (log.isDebugEnabled())
				{
					log.debug("12h sell-price recalc failed for {}: {}", itemId, ex.getMessage());
				}
				return null;
			});
	}

	private void applyRecalced12hSellPrice(int itemId, int freshSellPrice)
	{
		PlayerSession sess = getSession();
		if (sess == null)
		{
			return;
		}
		sess.setRecommendedPrice(itemId, freshSellPrice);
		if (flipFinderPanel != null)
		{
			flipFinderPanel.setDisplayedSellPrice(itemId, freshSellPrice);
		}
		if (grandExchangeTracker != null)
		{
			grandExchangeTracker.refreshSellFocus(itemId);
		}
		notifyRecalced12hSellPrice(itemId, freshSellPrice);
	}

	private void notifyRecalced12hSellPrice(int itemId, int freshSellPrice)
	{
		String itemName = itemManager.getItemComposition(itemId).getName();
		String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[FlipSmart] ")
			.append(ChatColorType.NORMAL)
			.append("Refreshed overnight sell price for " + itemName + " to ")
			.append(ChatColorType.HIGHLIGHT)
			.append(GpUtils.formatGPWithSuffix(freshSellPrice))
			.append(ChatColorType.NORMAL)
			.append(".")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	private OfferRecord findLiveOfferForItem(int itemId)
	{
		for (OfferRecord o : offerStore.liveOffers())
		{
			if (o.getItemId() == itemId && o.getState() != OfferState.FILLED)
			{
				return o;
			}
		}
		return null;
	}

	public void handleActiveOfferHandoff(OfferAdviceResponse resp)
	{
		if (autoRecommendService == null || resp == null || resp.getItemIdHint() == null)
		{
			return;
		}
		int itemId = resp.getItemIdHint();
		for (OfferRecord offer : offerStore.liveOffers())
		{
			if (offer.getItemId() == itemId)
			{
				if (resp.getNewPrice() != null)
				{
					// AC2 margin-decay exit: cancel the buy and re-sell the held units at the
					// advisor's jittered price. Routed through the stale-price map (not the
					// session price) so the sell lists via the no-offset path — no double-adjust.
					autoRecommendService.surfaceAdvisorExitResell(offer, resp.getNewPrice(), resp.getNetProfitEstimate());
				}
				else
				{
					autoRecommendService.surfaceAdvisorCancel(offer);
				}
				break;
			}
		}
	}

	// =====================
	// Exit Trades suppliers & persistence
	// =====================

	/**
	 * Cost basis for a breakeven exit: the average price actually paid on the most
	 * recent filled buy for {@code itemId}, derived from the offer store. Returns 0
	 * when unknown, which drives {@code ExitPriceResolver} to the mid-price fallback.
	 */
	public int getExitBuyBasis(int itemId)
	{
		if (offerStore == null)
		{
			return 0;
		}
		OfferRecord best = null;
		for (OfferRecord r : offerStore.forItem(itemId))
		{
			if (r.isBuy() && r.getFilledQuantity() > 0
				&& (best == null
					|| r.getEffectiveLastActivityAtMillis() > best.getEffectiveLastActivityAtMillis()))
			{
				best = r;
			}
		}
		return best == null ? 0 : (int) (best.getSpent() / best.getFilledQuantity());
	}

	public int getExitInventoryQty(int itemId)
	{
		return activeFlipTracker != null ? activeFlipTracker.getInventoryCountForItem(itemId) : 0;
	}

	/**
	 * Backend-computed exit sell price for {@code itemId} (the advisor's exit-at-breakeven,
	 * stored in the session), used as the source of truth for breakeven mode. 0 when unknown.
	 */
	public int getExitBackendSellPrice(int itemId)
	{
		Integer price = session != null ? session.getRecommendedPrice(itemId) : null;
		return price == null ? 0 : price;
	}

	/**
	 * Begin an Exit Trades run. Runs on the client thread because surfacing re-validates against
	 * live game state (inventory / offers), which must not be read from the Swing dialog thread.
	 */
	public void startExitTrades(com.flipsmart.exit.ExitTradesMode mode)
	{
		if (exitTradesController == null)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			exitTradesController.start(mode);
			// Clear any stale buy focus left over from auto-recommend so sell-only mode doesn't
			// briefly flash a buy before the normal flow re-focuses (most visible in REGULAR).
			if (flipAssistOverlay != null)
			{
				FocusedFlip current = flipAssistOverlay.getFocusedFlip();
				if (current != null && current.isBuying())
				{
					flipAssistOverlay.setFocusedFlip(null);
				}
			}
			exitTradesController.surfaceCurrent();
		});
	}

	/** Leave Exit Trades (sell-only) mode: drop the run and hand the overlay back to auto-recommend. */
	public void exitSellOnlyMode()
	{
		clientThread.invoke(() ->
		{
			if (exitTradesController != null)
			{
				exitTradesController.clear();
			}
			if (flipAssistOverlay != null)
			{
				flipAssistOverlay.setFocusedFlip(null);
				flipAssistOverlay.setAutoStatusMessage("", 0);
			}
			if (geSlotOverlay != null)
			{
				geSlotOverlay.clearAllAdjustmentHighlights();
			}
		});
	}

	private String getExitTradesStateKey()
	{
		return stateKeyFor(EXIT_TRADES_STATE_KEY_PREFIX);
	}

	/**
	 * Per-RSN config key for {@code prefix}, falling back to the last known RSN and then
	 * to a fixed placeholder, so state never leaks between accounts.
	 */
	private String stateKeyFor(String prefix)
	{
		String rsn = session.getRsn();
		if (rsn == null || rsn.isEmpty())
		{
			rsn = lastKnownRsn;
		}
		if (rsn == null || rsn.isEmpty())
		{
			return prefix + UNKNOWN_RSN_FALLBACK;
		}
		return prefix + rsn;
	}

	private void persistExitTradesState()
	{
		if (exitTradesController == null)
		{
			return;
		}
		ExitTradesController.PersistedState state =
			exitTradesController.getStateForPersistence(System.currentTimeMillis());
		if (state == null)
		{
			// Nothing acted (AC6) or inactive: drop the run and clear any stale blob.
			configManager.unsetConfiguration(CONFIG_GROUP, getExitTradesStateKey());
			exitTradesController.clear();
			return;
		}
		configManager.setConfiguration(CONFIG_GROUP, getExitTradesStateKey(), gson.toJson(state));
	}

	private void restoreExitTradesState()
	{
		if (exitTradesController == null)
		{
			return;
		}
		String json = configManager.getConfiguration(CONFIG_GROUP, getExitTradesStateKey());
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			ExitTradesController.PersistedState state =
				gson.fromJson(json, ExitTradesController.PersistedState.class);
			if (exitTradesController.restoreState(state, System.currentTimeMillis(), EXIT_TRADES_MAX_AGE_MS))
			{
				exitTradesController.surfaceCurrent(); // re-prompt pending slots (AC9 immediate resell)
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to restore exit-trades state: {}", e.getMessage());
		}
		configManager.unsetConfiguration(CONFIG_GROUP, getExitTradesStateKey());
	}

	// =====================
	// Auto-Recommend Persistence
	// =====================

	private String getAutoRecommendStateKey()
	{
		return stateKeyFor(AUTO_RECOMMEND_STATE_KEY_PREFIX);
	}

	/**
	 * Persist auto-recommend state to config for session survival.
	 */
	private void persistAutoRecommendState()
	{
		if (autoRecommendService == null || !autoRecommendService.isActive())
		{
			// Clean up any stale persisted state
			configManager.unsetConfiguration(CONFIG_GROUP, getAutoRecommendStateKey());
			return;
		}

		AutoRecommendService.PersistedState state = autoRecommendService.getStateForPersistence();
		String json = gson.toJson(state);
		configManager.setConfiguration(CONFIG_GROUP, getAutoRecommendStateKey(), json);
		log.debug("Persisted auto-recommend state ({} items in queue)", state.queue != null ? state.queue.size() : 0);
	}

	/**
	 * Restore auto-recommend state from config after login.
	 */
	private void restoreAutoRecommendState()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, getAutoRecommendStateKey());
		if (json == null || json.isEmpty())
		{
			return;
		}

		try
		{
			AutoRecommendService.PersistedState state = gson.fromJson(json, AutoRecommendService.PersistedState.class);
			if (autoRecommendService.restoreState(state, AutoRecommendService.MAX_PERSISTED_AGE_MS))
			{
				log.debug("Restored auto-recommend state from previous session");

				// Update the panel button
				if (flipFinderPanel != null)
				{
					flipFinderPanel.updateAutoRecommendButton(true);
				}

				// Start the refresh timer
				startAutoRecommendRefreshTimer();
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to restore auto-recommend state: {}", e.getMessage());
		}

		// Always clear the persisted state after attempting restore
		configManager.unsetConfiguration(CONFIG_GROUP, getAutoRecommendStateKey());
	}

	@Provides
	FlipSmartConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipSmartConfig.class);
	}

	@Provides
	@javax.inject.Singleton
	PlayerSession providePlayerSession()
	{
		return new PlayerSession();
	}


}

