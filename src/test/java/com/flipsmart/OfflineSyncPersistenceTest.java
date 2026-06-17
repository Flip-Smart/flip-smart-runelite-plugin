package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OfflineSyncPersistenceTest
{
	private static final String CONFIG_GROUP = "flipsmart";

	private PlayerSession session;
	private ConfigManager configManager;
	private OfferStore store;
	private OfflineSyncService service;
	private Map<String, String> configStore;

	@Before
	public void setUp()
	{
		session = mock(PlayerSession.class);
		configManager = mock(ConfigManager.class);
		store = new OfferStore();
		configStore = new HashMap<>();

		when(session.getCollectedItemIds()).thenReturn(Collections.emptySet());

		doAnswer(inv -> {
			configStore.put(inv.getArgument(1), inv.getArgument(2));
			return null;
		}).when(configManager).setConfiguration(eq(CONFIG_GROUP), anyString(), any());

		doAnswer(inv -> {
			configStore.remove(inv.<String>getArgument(1));
			return null;
		}).when(configManager).unsetConfiguration(eq(CONFIG_GROUP), anyString());

		when(configManager.getConfiguration(eq(CONFIG_GROUP), anyString()))
			.thenAnswer(inv -> configStore.get(inv.<String>getArgument(1)));

		service = new OfflineSyncService(
			session,
			mock(FlipSmartApiClient.class),
			configManager,
			new Gson(),
			mock(Client.class),
			mock(ClientThread.class),
			mock(ActiveFlipTracker.class),
			mock(GEHistoryService.class),
			store,
			mock(ItemManager.class));
	}

	@Test
	public void persistThenLoad_roundTripsOfferRecordsByOfferId()
	{
		when(session.getRsn()).thenReturn("Zezima");

		store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), 1000L);
		store.apply(sig(1, GrandExchangeOfferState.BOUGHT, 5678, 5, 5), 2000L);
		List<OfferRecord> original = store.export();

		service.persistOfferState();

		assertTrue("RSN-keyed offers key written",
			configStore.containsKey("persistedOffers_Zezima"));

		List<OfferRecord> loaded = service.loadPersistedOfferRecords();
		assertEquals(original.size(), loaded.size());

		Map<Long, OfferRecord> byId = new HashMap<>();
		for (OfferRecord r : loaded)
		{
			byId.put(r.getOfferId(), r);
		}
		for (OfferRecord o : original)
		{
			OfferRecord r = byId.get(o.getOfferId());
			assertTrue("offerId preserved across persist/load", r != null);
			assertEquals(o.getItemId(), r.getItemId());
			assertEquals(o.getState(), r.getState());
			assertEquals(o.getFilledQuantity(), r.getFilledQuantity());
			assertEquals(o.getCreatedAtMillis(), r.getCreatedAtMillis());
		}
	}

	@Test
	public void persist_withNullRsn_writesNoKey()
	{
		when(session.getRsn()).thenReturn(null);
		// no lastKnownRsn in config either

		store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), 1000L);
		service.persistOfferState();

		assertTrue("no config keys written during a null-RSN window", configStore.isEmpty());
		verify(configManager, never()).setConfiguration(eq(CONFIG_GROUP), anyString(), any());
	}

	@Test
	public void persist_withEmptyStore_preservesPreviouslyPersistedOffers()
	{
		when(session.getRsn()).thenReturn("Zezima");

		// First persist with real offers in the store.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), 1000L);
		store.apply(sig(1, GrandExchangeOfferState.BOUGHT, 5678, 5, 5), 2000L);
		List<OfferRecord> original = store.export();
		service.persistOfferState();
		assertTrue("offers persisted on first save",
			configStore.containsKey("persistedOffers_Zezima"));

		// Now the store goes transiently empty (logout/hop window) and persist fires again.
		store.importRecords(Collections.emptyList());
		assertTrue("store is empty before second persist", store.export().isEmpty());
		service.persistOfferState();

		// The previously-persisted offers must NOT be wiped.
		assertTrue("persisted offers key still present after empty-store persist",
			configStore.containsKey("persistedOffers_Zezima"));
		verify(configManager, never())
			.unsetConfiguration(eq(CONFIG_GROUP), eq("persistedOffers_Zezima"));
		verify(configManager, never())
			.unsetConfiguration(eq(CONFIG_GROUP), eq("persistedOffers_lastSession"));

		List<OfferRecord> loaded = service.loadPersistedOfferRecords();
		assertEquals("previously-persisted offers survive the empty-store persist",
			original.size(), loaded.size());
	}

	@Test
	public void persist_fallsBackToLastKnownRsn_whenSessionRsnMissing()
	{
		when(session.getRsn()).thenReturn(null);
		configStore.put("lastKnownRsn", "Durial321");

		store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), 1000L);
		service.persistOfferState();

		assertTrue("persisted under the fallback RSN key",
			configStore.containsKey("persistedOffers_Durial321"));
		assertFalse("no unknown-RSN key written",
			configStore.containsKey("persistedOffers_unknown"));
	}

	private static com.flipsmart.domain.offer.OfferSignal sig(int slot, GrandExchangeOfferState s, int itemId, int sold, int total)
	{
		return new com.flipsmart.domain.offer.OfferSignal(slot, s, itemId, "i" + itemId, total, 100, sold, (long) sold * 100);
	}
}
