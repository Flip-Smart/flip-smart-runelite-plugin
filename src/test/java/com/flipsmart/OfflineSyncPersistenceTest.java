package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OfflineSyncPersistenceTest
{
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String SYNC_MARKER_ZEZIMA = "offlineSyncAt_Zezima";

	private PlayerSession session;
	private ConfigManager configManager;
	private Client client;
	private ClientThread clientThread;
	private GEHistoryService geHistoryService;
	private OfferStore store;
	private ActiveFlipTracker activeFlipTracker;
	private OfflineSyncService service;
	private ItemManager itemManager;
	private Map<String, String> configStore;

	@Before
	public void setUp()
	{
		session = mock(PlayerSession.class);
		configManager = mock(ConfigManager.class);
		client = mock(Client.class);
		clientThread = mock(ClientThread.class);
		geHistoryService = mock(GEHistoryService.class);
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

		// Execute clientThread.invokeLater runnables synchronously so tests can verify
		// side-effects that happen inside the callback.
		doAnswer(inv -> {
			inv.<Runnable>getArgument(0).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		activeFlipTracker = mock(ActiveFlipTracker.class);
		itemManager = mock(ItemManager.class);

		service = new OfflineSyncService(
			session,
			configManager,
			new Gson(),
			client,
			clientThread,
			activeFlipTracker,
			geHistoryService,
			store,
			itemManager);
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

	/**
	 * A partial-cancel buy (total=5, filled=2, CANCELLED_PARTIAL) that is gone from live slots
	 * on login must produce exactly one offline fill registered at the reconciled filled qty (2),
	 * never the order total (5) and never a double-count (5+2=7).
	 */
	@Test
	public void offlinePartialCancelRegistersSingleBackfillAtReconciledQty()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);
		// We've synced before (marker 500) and this fill is fresh since then (activity 2000).
		configStore.put(SYNC_MARKER_ZEZIMA, "500");
		// Item still in inventory (2 traded units), so the inventory gate allows the re-add.
		when(activeFlipTracker.getInventoryCountForItem(111)).thenReturn(2);

		// Build a CANCELLED_PARTIAL record: place a buy, then cancel with 2 fills of 5.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 111, 0, 5), 1000L);
		store.apply(sig(0, GrandExchangeOfferState.CANCELLED_BUY, 111, 2, 5), 2000L);

		// Confirm the store has the partial-cancel record before persisting.
		List<OfferRecord> records = store.export();
		assertEquals(1, records.size());
		assertEquals(OfferState.CANCELLED_PARTIAL, records.get(0).getState());
		assertEquals(2, records.get(0).getFilledQuantity());

		// Persist this session state so syncOfflineFills can load it.
		service.persistOfferState();
		store.importRecords(Collections.emptyList()); // simulate fresh login — store is empty

		// Live GE slots are empty on login (the partial-cancel slot is gone).
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.syncOfflineFills();

		// The reconciler plan routes the gone record to offlineCollected.
		// Exactly one registerOfflineFill must fire — never twice (no double-count).
		verify(geHistoryService, times(1)).registerOfflineFill(111);

		// Collected qty must be the reconciled filled qty (2), never the order total (5).
		verify(session, times(1)).addCollectedItem(111, 2);
		verify(session, never()).addCollectedItem(eq(111), eq(5));
	}

	/**
	 * An offline-collected BUY (filled>0) whose item is no longer in inventory (sold/used offline)
	 * must NOT be re-added to the collected set — only the History backfill fires. Re-adding it
	 * strands a phantom collect/sell prompt every login (#736 regression).
	 */
	@Test
	public void offlineCollectedBuyWithNoInventory_doesNotReAddCollectedItem()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);
		configStore.put(SYNC_MARKER_ZEZIMA, "500"); // fresh since last sync (activity 2000)
		when(activeFlipTracker.getInventoryCountForItem(4824)).thenReturn(0);

		store.apply(sig(0, GrandExchangeOfferState.BUYING, 4824, 0, 10), 1000L);
		store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 4824, 10, 10), 2000L);

		service.persistOfferState();
		store.importRecords(Collections.emptyList());
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.syncOfflineFills();

		verify(session, never()).addCollectedItem(eq(4824), anyInt());
		verify(geHistoryService, times(1)).registerOfflineFill(4824);
	}

	/**
	 * An offline-collected BUY whose item IS still in inventory is added to the collected set
	 * at min(inventory, filled), and a History backfill fires.
	 */
	@Test
	public void offlineCollectedBuyWithInventory_addsCollectedItemAtMinQty()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);
		configStore.put(SYNC_MARKER_ZEZIMA, "500"); // fresh since last sync (activity 2000)
		when(activeFlipTracker.getInventoryCountForItem(4824)).thenReturn(7);

		store.apply(sig(0, GrandExchangeOfferState.BUYING, 4824, 0, 10), 1000L);
		store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 4824, 10, 10), 2000L);

		service.persistOfferState();
		store.importRecords(Collections.emptyList());
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.syncOfflineFills();

		verify(session, times(1)).addCollectedItem(4824, 7);
		verify(geHistoryService, times(1)).registerOfflineFill(4824);
	}

	/**
	 * After reconcilePersistedIntoStore (via preloadPersistedOffers), an offline-collected record
	 * imported into the store must be TERMINAL — not returned by liveOffers() and not reported as a
	 * live buy — so the auto-mode stale queue can't re-flag it and pruning can remove it (#736).
	 */
	@Test
	public void offlineCollectedImportIsTerminal_notLive()
	{
		when(session.getRsn()).thenReturn("Zezima");

		// A partial-cancel buy (CANCELLED_PARTIAL — non-terminal) that is gone from live slots.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 4824, 0, 5), 1000L);
		store.apply(sig(0, GrandExchangeOfferState.CANCELLED_BUY, 4824, 2, 5), 2000L);
		assertEquals(OfferState.CANCELLED_PARTIAL, store.export().get(0).getState());

		service.persistOfferState();
		store.importRecords(Collections.emptyList());
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.preloadPersistedOffers();

		assertTrue("offline-collected import must not be a live offer", store.liveOffers().isEmpty());
		assertFalse("offline-collected import must not report as a live buy",
			store.hasLiveBuyOfferForItem(4824));
		OfferRecord imported = store.forItem(4824).get(0);
		assertTrue("imported offline-collected record must be terminal", imported.getState().isTerminal());
	}

	/**
	 * End-to-end: a restored collected item whose store record is terminal and whose inventory is 0
	 * is pruned (its phantom prompt removed). isItemKnownPresent must return false for it.
	 */
	@Test
	public void restoredPhantomCollectedItem_isPruned()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.getCollectedItemIds()).thenReturn(new java.util.HashSet<>(java.util.Arrays.asList(4824)));
		when(activeFlipTracker.getInventoryCountForItem(4824)).thenReturn(0);

		// Terminal store record for the item (as Part B would import it), no live offer.
		OfferRecord terminal = OfferRecord.newOffer(1L, 0, 4824, "Rune nails", true, 5, 100, 1000L)
			.withFill(2, 200L, OfferState.CANCELLED_PARTIAL, 2000L)
			.withState(OfferState.COLLECTED, 3000L);
		store.importRecords(java.util.Collections.singletonList(terminal));

		int removed = service.pruneStaleCollectedItems();

		assertEquals("phantom collected item with terminal record + no inventory must be pruned", 1, removed);
		verify(session, times(1)).removeCollectedItem(4824);
	}

	/**
	 * A non-terminal persisted record last active BEFORE the previous sync is a leftover from an
	 * earlier session (already backfilled) — relogging must not re-fire the "open GE History" prompt
	 * for it. This is the false-nag the user hit: a relog with no new trades still prompted.
	 */
	@Test
	public void staleOfflineRecordOlderThanLastSync_doesNotPrompt()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);
		// Last sync ran at 5000; this record's last activity (2000) predates it → already-known history.
		configStore.put(SYNC_MARKER_ZEZIMA, "5000");

		store.apply(sig(0, GrandExchangeOfferState.BUYING, 222, 0, 5), 1000L);
		store.apply(sig(0, GrandExchangeOfferState.CANCELLED_BUY, 222, 2, 5), 2000L);
		service.persistOfferState();
		store.importRecords(Collections.emptyList());
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.syncOfflineFills();

		verify(geHistoryService, never()).registerOfflineFill(222);
	}

	/**
	 * First sync for an account (no marker yet): the persisted blob predates the feature and is
	 * already known to the backend, so nothing is prompted — and the marker is written so genuinely
	 * new offline fills on later logins are detected.
	 */
	@Test
	public void firstSyncWithNoMarker_suppressesPreExistingBlob_andWritesMarker()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);
		// No offlineSyncAt_Zezima marker present.

		store.apply(sig(0, GrandExchangeOfferState.BUYING, 333, 0, 5), 1000L);
		store.apply(sig(0, GrandExchangeOfferState.CANCELLED_BUY, 333, 2, 5), 2000L);
		service.persistOfferState();
		store.importRecords(Collections.emptyList());
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.syncOfflineFills();

		verify(geHistoryService, never()).registerOfflineFill(333);
		assertTrue("a sync marker is written so later genuine fills are detected",
			configStore.containsKey(SYNC_MARKER_ZEZIMA));
	}

	@Test
	public void relog_restoresOfferAge_forStillLiveOffer()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);

		// Offer placed long ago — activity anchored at 1000.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 111, 0, 5), 1000L);
		service.persistOfferState();

		// Fresh login re-anchors the still-live offer to "now" (9000).
		store.importRecords(Collections.emptyList());
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 111, 0, 5), 9000L);
		assertEquals("re-anchored to login time before restore", 9000L,
			store.bySlot(0).getEffectiveLastActivityAtMillis());

		ItemComposition comp = itemComp("i111");
		when(itemManager.getItemComposition(111)).thenReturn(comp);
		GrandExchangeOffer live = geOffer(111, GrandExchangeOfferState.BUYING, 5, 100);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[]{live});

		service.syncOfflineFills();

		assertEquals("offer age restored across relog", 1000L,
			store.bySlot(0).getEffectiveLastActivityAtMillis());
	}

	private static GrandExchangeOffer geOffer(int itemId, GrandExchangeOfferState state, int total, int price)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getItemId()).thenReturn(itemId);
		when(o.getState()).thenReturn(state);
		when(o.getTotalQuantity()).thenReturn(total);
		when(o.getPrice()).thenReturn(price);
		when(o.getQuantitySold()).thenReturn(0);
		when(o.getSpent()).thenReturn(0);
		return o;
	}

	private static ItemComposition itemComp(String name)
	{
		ItemComposition c = mock(ItemComposition.class);
		when(c.getName()).thenReturn(name);
		return c;
	}

	private static com.flipsmart.domain.offer.OfferSignal sig(int slot, GrandExchangeOfferState s, int itemId, int sold, int total)
	{
		return new com.flipsmart.domain.offer.OfferSignal(slot, s, itemId, "i" + itemId, total, 100, sold, (long) sold * 100);
	}
}
