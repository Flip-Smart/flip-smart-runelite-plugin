package com.flipsmart.plugin;

import com.flipsmart.BankSnapshotService;
import com.flipsmart.FlipSmartConfig;
import com.flipsmart.FlipSmartPlugin;
import com.flipsmart.GEHistoryService;
import com.flipsmart.GeOfferDescriptionService;
import com.flipsmart.GrandExchangeTracker;
import com.flipsmart.OfflineSyncService;
import com.flipsmart.PlayerSession;
import com.flipsmart.WebhookSyncService;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ScriptID;
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
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.events.ConfigChanged;

/**
 * Routes RuneLite EventBus events to the appropriate collaborators.
 *
 * The 13 {@code @Subscribe} methods stay physically on {@link FlipSmartPlugin}
 * (RuneLite scans the registered plugin object for them) but are reduced to thin
 * stubs that delegate here. The handler bodies that depend only on injected
 * collaborators and already-exposed plugin hooks were moved verbatim into this
 * class. A handful of handlers ({@code onGameTick}, {@code onVarClientIntChanged},
 * {@code onVarClientStrChanged}) remain coupled to plugin-private mutable state
 * set during startUp; for those, the router delegates straight back to the plugin
 * so the behavior and ordering are preserved exactly.
 */
public class EventRouter
{
	private static final String CONFIG_GROUP = "flipsmart";
	private static final int INVENTORY_CONTAINER_ID = 93;

	private final FlipSmartPlugin plugin;
	private final Client client;
	private final FlipSmartConfig config;
	private final PlayerSession session;
	private final WebhookSyncService webhookSyncService;
	private final OfflineSyncService offlineSyncService;
	private final BankSnapshotService bankSnapshotService;
	private final GEHistoryService geHistoryService;
	private final GeOfferDescriptionService geOfferDescriptionService;
	private final GrandExchangeTracker grandExchangeTracker;

	public EventRouter(FlipSmartPlugin plugin, Client client, FlipSmartConfig config,
		PlayerSession session, WebhookSyncService webhookSyncService, OfflineSyncService offlineSyncService,
		BankSnapshotService bankSnapshotService, GEHistoryService geHistoryService,
		GeOfferDescriptionService geOfferDescriptionService, GrandExchangeTracker grandExchangeTracker)
	{
		this.plugin = plugin;
		this.client = client;
		this.config = config;
		this.session = session;
		this.webhookSyncService = webhookSyncService;
		this.offlineSyncService = offlineSyncService;
		this.bankSnapshotService = bankSnapshotService;
		this.geHistoryService = geHistoryService;
		this.geOfferDescriptionService = geOfferDescriptionService;
		this.grandExchangeTracker = grandExchangeTracker;
	}

	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (!CONFIG_GROUP.equals(configChanged.getGroup()))
		{
			return;
		}

		if ("enableAutoRecommend".equals(configChanged.getKey()) && plugin.getFlipFinderPanel() != null)
		{
			plugin.getFlipFinderPanel().setAutoRecommendVisible(config.enableAutoRecommend());
		}

		if ("f2pMode".equals(configChanged.getKey()) && plugin.getFlipFinderPanel() != null)
		{
			plugin.getFlipFinderPanel().refresh();
		}

		if (("cashstackOverrideEnabled".equals(configChanged.getKey())
			|| "cashstackOverrideAmount".equals(configChanged.getKey())) && plugin.getFlipFinderPanel() != null)
		{
			plugin.getFlipFinderPanel().updateCashstackOverrideIndicator();
			plugin.getFlipFinderPanel().refresh();
		}

		String key = configChanged.getKey();
		if ("discordWebhookUrl".equals(key) || "notifySaleCompleted".equals(key))
		{
			webhookSyncService.syncIfChanged();
		}
	}

	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState gameState = gameStateChanged.getGameState();

		if (gameState == GameState.LOGGING_IN || gameState == GameState.HOPPING || gameState == GameState.CONNECTION_LOST)
		{
			session.onLoginStateChange(client.getTickCount());

			if (!session.getTrackedOffers().isEmpty())
			{
				offlineSyncService.persistOfferState();
			}
		}

		if (gameState == GameState.LOGIN_SCREEN)
		{
			plugin.handleLogoutState();
		}

		if (gameState == GameState.LOGGED_IN)
		{
			plugin.handleLoggedInState();
			plugin.getMotdService().onLogin();
		}
	}

	public void onWorldChanged(WorldChanged event)
	{
		plugin.updateMembersWorldCache();
		if (plugin.getFlipFinderPanel() != null)
		{
			plugin.getFlipFinderPanel().refresh();
		}
	}

	public void onGameTick(GameTick event)
	{
		plugin.onGameTickHandler(event);
	}

	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();

		if (containerId == INVENTORY_CONTAINER_ID)
		{
			plugin.updateCashStack();
			return;
		}

		if (containerId == InventoryID.BANK.getId())
		{
			bankSnapshotService.onBankContainerChanged(plugin.getCurrentRsnSafe().orElse(null));
		}
	}

	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.GE_OFFERS_SETUP_BUILD)
		{
			return;
		}

		if (geOfferDescriptionService != null)
		{
			geOfferDescriptionService.onSetupBuildScriptPostFired();
		}

		int openItemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (openItemId > 0 && plugin.getAutoRecommendService() != null)
		{
			plugin.getAutoRecommendService().acquireOfferLock(openItemId);
		}

		int offerType = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE);
		if (offerType != 1)
		{
			if (openItemId > 0 && plugin.getAutoRecommendService() != null)
			{
				plugin.getAutoRecommendService().overrideFocusForBuy(openItemId);
			}
			return;
		}

		if (openItemId <= 0)
		{
			return;
		}

		grandExchangeTracker.autoFocusOnActiveFlip(openItemId);
	}

	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerEvent)
	{
		// Delegated back to the plugin: the body builds GrandExchangeTracker.OfferContext,
		// a package-private nested type, so it stays in the com.flipsmart package.
		plugin.onGrandExchangeOfferChangedHandler(offerEvent);
	}

	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		plugin.onVarClientIntChangedHandler(event);
	}

	public void onVarClientStrChanged(VarClientStrChanged event)
	{
		plugin.onVarClientStrChangedHandler(event);
	}

	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.GE_HISTORY)
		{
			geHistoryService.onHistoryWidgetLoaded();
		}
	}

	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (geOfferDescriptionService != null)
		{
			geOfferDescriptionService.onScriptCallbackEvent(event);
		}
	}

	public void onBeforeRender(BeforeRender event)
	{
		if (geOfferDescriptionService != null)
		{
			geOfferDescriptionService.onBeforeRender(event);
		}
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (geOfferDescriptionService != null)
		{
			geOfferDescriptionService.onMenuOptionClicked(event);
		}
	}
}
