package com.flipsmart.trading;

import com.flipsmart.ActiveFlipTracker;
import com.flipsmart.GEHistoryService;
import com.flipsmart.OfflineSyncService;
import com.flipsmart.PlayerSession;
import com.flipsmart.domain.offer.OfferSignal;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Replays a session-corpus timeline through the real {@link OfferStore} and
 * {@link OfflineSyncService}. Unlike the fill-level golden corpus, a timeline can express the
 * session lifecycle — world hops, relogs and offline gaps — which is where retention and
 * fill-accounting defects actually surface.
 */
final class SessionCorpusHarness
{
	private static final String CONFIG_GROUP = "flipsmart";
	private static final int GE_SLOT_COUNT = 8;

	static final class Result
	{
		final OfferStore store;
		final List<OfferEvent> reportedFills;

		Result(OfferStore store, List<OfferEvent> reportedFills)
		{
			this.store = store;
			this.reportedFills = reportedFills;
		}
	}

	private SessionCorpusHarness()
	{
		// Test helper - prevent instantiation
	}

	static Result run(JsonObject fixture)
	{
		// The services age records against the wall clock, so timeline offsets anchor to it.
		long base = System.currentTimeMillis();
		String rsn = fixture.has("rsn") ? fixture.get("rsn").getAsString() : "TestPlayer";

		OfferStore store = new OfferStore();
		List<OfferEvent> fills = new ArrayList<>();
		store.addListener(e ->
		{
			if (e.newlyFilledQuantity > 0)
			{
				fills.add(e);
			}
		});

		PlayerSession session = mock(PlayerSession.class);
		when(session.getRsn()).thenReturn(rsn);
		when(session.getCollectedItemIds()).thenReturn(Collections.emptySet());

		Map<String, String> configStore = new HashMap<>();
		ConfigManager configManager = mock(ConfigManager.class);
		doAnswer(inv ->
		{
			configStore.put(inv.getArgument(1), inv.getArgument(2));
			return null;
		}).when(configManager).setConfiguration(eq(CONFIG_GROUP), anyString(), any());
		doAnswer(inv ->
		{
			configStore.remove(inv.<String>getArgument(1));
			return null;
		}).when(configManager).unsetConfiguration(eq(CONFIG_GROUP), anyString());
		when(configManager.getConfiguration(eq(CONFIG_GROUP), anyString()))
			.thenAnswer(inv -> configStore.get(inv.<String>getArgument(1)));

		Client client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		doAnswer(inv ->
		{
			inv.<Runnable>getArgument(0).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		// Stubbed up front: building a mock inside a when(...) argument throws
		// UnfinishedStubbingException.
		ItemComposition anyComposition = mock(ItemComposition.class);
		when(anyComposition.getName()).thenReturn("item");
		ItemManager itemManager = mock(ItemManager.class);
		when(itemManager.getItemComposition(anyInt())).thenReturn(anyComposition);

		// Everything in the environment survives a restart because it stands in for the client
		// and its config file. The Session below is process state, rebuilt from the config blob
		// alone when the timeline restarts.
		Environment env = new Environment(session, configManager, client, clientThread,
			itemManager, fills);
		Session live = new Session();
		live.rebuild(env);

		for (JsonElement element : fixture.getAsJsonArray("timeline"))
		{
			applyEvent(element.getAsJsonObject(), base, live, env);
		}
		return new Result(live.store, fills);
	}

	/**
	 * The client/config/item stand-ins a real restart would rebuild the process against. Grouped
	 * so the harness can pass "what a restart reads" as a single unit.
	 */
	private static final class Environment
	{
		final PlayerSession session;
		final ConfigManager configManager;
		final Client client;
		final ClientThread clientThread;
		final ItemManager itemManager;
		final List<OfferEvent> fills;

		Environment(PlayerSession session, ConfigManager configManager, Client client,
			ClientThread clientThread, ItemManager itemManager, List<OfferEvent> fills)
		{
			this.session = session;
			this.configManager = configManager;
			this.client = client;
			this.clientThread = clientThread;
			this.itemManager = itemManager;
			this.fills = fills;
		}
	}

	/**
	 * The process-local half of the plugin: the offer store and the service that reconciles into
	 * it. A restart replaces both, which is what distinguishes it from a relog — a relog keeps the
	 * running instance and only loses the GE slots, so anything held outside the store (the fill
	 * watermarks, for one) survives it.
	 */
	private static final class Session
	{
		private OfferStore store;
		private OfflineSyncService service;

		void rebuild(Environment env)
		{
			store = new OfferStore();
			store.addListener(e ->
			{
				if (e.newlyFilledQuantity > 0)
				{
					env.fills.add(e);
				}
			});
			service = new OfflineSyncService(
				env.session,
				env.configManager,
				new Gson(),
				env.client,
				env.clientThread,
				mock(ActiveFlipTracker.class),
				mock(GEHistoryService.class),
				store,
				env.itemManager,
				new RoundTripLedger());
		}
	}

	private static void applyEvent(JsonObject event, long base, Session live, Environment env)
	{
		long at = base + event.get("at_seconds").getAsLong() * 1000L;
		String type = event.get("type").getAsString();

		if ("observe".equals(type))
		{
			live.store.apply(toSignal(event), at);
			return;
		}

		// A hop and a relog both persist then preload; only a relog loses the in-memory store.
		// "persist": false models the window where the RSN cannot be resolved and the newer
		// store state never reaches disk, so the preload reattaches an older persisted record.
		if (!event.has("persist") || event.get("persist").getAsBoolean())
		{
			live.service.persistOfferState();
		}
		if ("relog".equals(type))
		{
			live.store.importRecords(Collections.emptyList());
		}
		if ("restart".equals(type))
		{
			// Nothing but the config blob crosses a process boundary.
			live.rebuild(env);
		}
		// Built before the stubbing call: creating mocks inside a when(...) argument throws
		// UnfinishedStubbingException.
		GrandExchangeOffer[] snapshot = toSnapshot(event);
		when(env.client.getGrandExchangeOffers()).thenReturn(snapshot);
		live.service.preloadPersistedOffers();
	}

	private static OfferSignal toSignal(JsonObject e)
	{
		int itemId = e.get("item_id").getAsInt();
		return new OfferSignal(
			e.get("slot").getAsInt(),
			GrandExchangeOfferState.valueOf(e.get("state").getAsString()),
			itemId,
			"i" + itemId,
			e.get("total").getAsInt(),
			e.get("price").getAsInt(),
			e.get("sold").getAsInt(),
			e.get("spent").getAsLong());
	}

	/**
	 * The GE snapshot as the client would report it: {@code null} when it is not readable yet,
	 * otherwise a full-length array whose unused slots read EMPTY.
	 */
	private static GrandExchangeOffer[] toSnapshot(JsonObject event)
	{
		if (event.has("ge_readable") && !event.get("ge_readable").getAsBoolean())
		{
			return null;
		}

		GrandExchangeOffer empty = mock(GrandExchangeOffer.class);
		when(empty.getState()).thenReturn(GrandExchangeOfferState.EMPTY);
		GrandExchangeOffer[] slots = new GrandExchangeOffer[GE_SLOT_COUNT];
		Arrays.fill(slots, empty);

		JsonArray live = event.has("live_slots")
			? event.getAsJsonArray("live_slots") : new JsonArray();
		for (JsonElement element : live)
		{
			JsonObject s = element.getAsJsonObject();
			GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
			when(offer.getState())
				.thenReturn(GrandExchangeOfferState.valueOf(s.get("state").getAsString()));
			when(offer.getItemId()).thenReturn(s.get("item_id").getAsInt());
			when(offer.getTotalQuantity()).thenReturn(s.get("total").getAsInt());
			when(offer.getPrice()).thenReturn(s.get("price").getAsInt());
			when(offer.getQuantitySold()).thenReturn(s.get("sold").getAsInt());
			when(offer.getSpent()).thenReturn(s.get("spent").getAsInt());
			slots[s.get("slot").getAsInt()] = offer;
		}
		return slots;
	}
}
