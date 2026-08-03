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
	/** Prior-sync wall-clock: records with later activity count as fresh offline fills. */
	private static final String PRIOR_SYNC_AT = "500";

	private PlayerSession session;
	private ConfigManager configManager;
	private Client client;
	private ClientThread clientThread;
	private GEHistoryService geHistoryService;
	private OfferStore store;
	private ActiveFlipTracker activeFlipTracker;
	private OfflineSyncService service;
	private com.flipsmart.trading.RoundTripLedger ledger;
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

		ledger = new com.flipsmart.trading.RoundTripLedger();
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
			itemManager,
			ledger);
	}

	/**
	 * A collected entry that has lost its backing is dropped silently.
	 *
	 * <p>Registering a History backfill here bypassed the reconciler's freshness cutoff
	 * entirely, so records it had just routed to staleHistory as already-known were
	 * prompted for anyway — and on every login, because a set pruned to empty never
	 * cleared its own persisted blob.</p>
	 */
	@Test
	public void prunedCollectedItemDoesNotRegisterHistoryBackfill()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.getCollectedItemIds())
			.thenReturn(new java.util.HashSet<>(Collections.singletonList(4151)));
		when(activeFlipTracker.getInventoryCountForItem(4151)).thenReturn(0);

		assertEquals(1, service.pruneStaleCollectedItems());

		verify(session, times(1)).removeCollectedItem(4151);
		verify(geHistoryService, never()).registerOfflineFill(4151);
	}

	/**
	 * The collected set is derived, not persisted: rebuilt from the persisted offer records
	 * capped by what the player actually still holds, and never written to config.
	 */
	@Test
	public void collectedSetIsRebuiltFromPersistedOffersAndNeverWrittenToConfig()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);
		configStore.put(SYNC_MARKER_ZEZIMA, PRIOR_SYNC_AT);
		// Bought 10, but only 3 remain — the rest were sold or used while offline.
		when(activeFlipTracker.getInventoryCountForItem(4824)).thenReturn(3);

		store.apply(sig(0, GrandExchangeOfferState.BUYING, 4824, 0, 10), 1000L);
		store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 4824, 10, 10), 2000L);

		service.persistOfferState();
		store.importRecords(Collections.emptyList()); // fresh login
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.syncOfflineFills();

		// Capped at the live inventory, not the 10 originally bought — the config-backed
		// restore would have replayed the stale figure recorded at collect time.
		verify(session, times(1)).addCollectedItem(4824, 3);

		assertFalse("collected IDs must not be persisted", configStore.containsKey("collectedItems_Zezima"));
		assertFalse("collected quantities must not be persisted", configStore.containsKey("collectedQuantities_Zezima"));
		assertFalse("collected savedAt must not be persisted", configStore.containsKey("collectedItemsSavedAt_Zezima"));
	}

	/**
	 * A partially-filled buy still sitting in its GE slot has NOT been collected — those fills are
	 * in the Exchange, not the inventory. It must not contribute to the collected set even when the
	 * player holds units of that item for unrelated reasons (supplies, an earlier flip), or the
	 * rebuild strands exactly the phantom sell prompt this change exists to remove.
	 *
	 * <p>Found during in-game QA on a live 28924 partial-fill.</p>
	 */
	@Test
	public void reattachedLiveBuyIsNotRebuiltIntoCollectedSet()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);
		configStore.put(SYNC_MARKER_ZEZIMA, PRIOR_SYNC_AT);
		when(activeFlipTracker.getInventoryCountForItem(28924)).thenReturn(7);

		// A partial-fill buy that is still live in slot 0 when we log back in.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 28924, 20, 100), 2000L);
		service.persistOfferState();
		store.importRecords(Collections.emptyList());

		// Built before the outer stubbing calls — these helpers stub inner mocks, and Mockito
		// rejects a when() that lands inside an in-progress one.
		ItemComposition comp = itemComp("i28924");
		GrandExchangeOffer live = geOffer(28924, GrandExchangeOfferState.BUYING, 100, 100);
		when(itemManager.getItemComposition(28924)).thenReturn(comp);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[]{live});

		service.syncOfflineFills();

		verify(session, never()).addCollectedItem(eq(28924), anyInt());
	}

	/**
	 * When the GE snapshot is unreadable at preload, an offline-collected record must be carried
	 * into the store unchanged rather than dropped.
	 *
	 * <p>It is non-terminal, so {@code retainRecentTerminalHistory} will not pick it up, and
	 * {@code importRecords} replaces the store wholesale — omitting it drops the record, and the
	 * persist that follows writes the truncated set back over the saved blob. That erases the cost
	 * basis of a position the player still holds, and because the collected set is now derived from
	 * these records, it erases the position from Active Flips too.</p>
	 *
	 * <p>Found during in-game QA: a 10,329-unit snape grass (231) buy disappeared after one login
	 * where the slots were not yet loaded.</p>
	 */
	@Test
	public void offlineCollectedRecordSurvivesPreloadWhenSlotsUnreadable()
	{
		when(session.getRsn()).thenReturn("Zezima");

		// A filled buy that is gone from its slot, persisted from the previous session.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 231, 0, 10329), 1000L);
		store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 231, 10329, 10329), 2000L);
		service.persistOfferState();
		store.importRecords(Collections.emptyList());

		// GE snapshot not loaded yet — the state this regression needs.
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.preloadPersistedOffers();

		boolean survived = false;
		for (OfferRecord r : store.export())
		{
			if (r.getItemId() == 231 && r.getFilledQuantity() == 10329)
			{
				survived = true;
			}
		}
		assertTrue("offline-collected record must survive an unreadable-slot preload", survived);

		// And the persist that follows must not write a set that has lost it.
		service.persistOfferState();
		boolean stillPersisted = false;
		for (OfferRecord r : service.loadPersistedOfferRecords())
		{
			if (r.getItemId() == 231 && r.getFilledQuantity() == 10329)
			{
				stillPersisted = true;
			}
		}
		assertTrue("record must remain in the persisted blob", stillPersisted);
	}

	/** Blobs written by an older client are cleaned up rather than left orphaned. */
	@Test
	public void legacyCollectedConfigKeysAreRemovedOnSync()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);
		configStore.put("collectedItems_Zezima", "[4151]");
		configStore.put("collectedQuantities_Zezima", "{\"4151\":5}");
		configStore.put("collectedItemsSavedAt_Zezima", "1700000000000");
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.syncOfflineFills();

		assertFalse(configStore.containsKey("collectedItems_Zezima"));
		assertFalse(configStore.containsKey("collectedQuantities_Zezima"));
		assertFalse(configStore.containsKey("collectedItemsSavedAt_Zezima"));
	}

	@Test
	public void nextOfferIdSurvivesARestartThatPrunesTheRecordsBackingIt()
	{
		// The counter is derived from the records the store imports, and retention pruning drops
		// the oldest terminal ones. Across a restart that let it fall back and remint an id the
		// backend still holds fills under, so the high-water mark is persisted separately.
		when(session.getRsn()).thenReturn("Zezima");
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), 1000L);
		store.apply(sig(1, GrandExchangeOfferState.BUYING, 5678, 0, 10), 1000L);
		long highWater = store.nextOfferId();

		service.persistOfferState();
		assertTrue("high-water offer id key written", configStore.containsKey("nextOfferId_Zezima"));

		// A restart with an empty store, as if every record had aged out of retention.
		OfferStore restarted = new OfferStore();
		restarted.raiseNextOfferId(Long.parseLong(configStore.get("nextOfferId_Zezima")));
		assertEquals("counter must not fall back below what was already issued",
			highWater, restarted.nextOfferId());
	}

	@Test
	public void transientlyEmptyStoreDoesNotClobberTheHighWaterOfferId()
	{
		// persistOfferState runs during the logout/hop transition, where the store is empty and
		// its counter reads 1. Writing that erased the mark — and the mark is the only surviving
		// record of ids whose offers have aged out of retention, so it is precisely the case the
		// persistence exists for. Caught in-game: the key was written as 1 against persisted
		// records topping out at offerId 22.
		when(session.getRsn()).thenReturn("Zezima");
		configStore.put("nextOfferId_Zezima", "60");

		service.persistOfferState();

		assertEquals("an empty store must never lower the mark",
			"60", configStore.get("nextOfferId_Zezima"));
	}

	@Test
	public void unparseableOrAbsentNextOfferIdIsIgnoredRatherThanThrowing()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.isOfflineSyncCompleted()).thenReturn(false);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		// Absent, blank and corrupt are all handled by one parse rather than separate branches,
		// so each shape needs to survive it.
		for (String stored : new String[]{"not-a-number", "", "   "})
		{
			configStore.put("nextOfferId_Zezima", stored);
			service.syncOfflineFills();
			assertTrue("a mark of '" + stored + "' must not break the sync", store.nextOfferId() >= 1);
		}
	}

	@Test
	public void flipFinderSourced_persistsAndRestoresAcrossRestart()
	{
		when(session.getRsn()).thenReturn("Zezima");
		when(session.getFlipFinderSourcedItems())
			.thenReturn(new java.util.HashSet<>(java.util.Arrays.asList(100, 200)));

		service.persistOfferState();
		assertTrue("Flip Finder-sourced set key written",
			configStore.containsKey("flipFinderSourced_Zezima"));

		// A restart: restore reads the persisted set back into the session.
		service.restoreFlipFinderSourcedItems();
		verify(session).restoreFlipFinderSourced(
			eq(new java.util.HashSet<>(java.util.Arrays.asList(100, 200))), org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	public void flipFinderSourced_emptySetDoesNotWipePersistedData()
	{
		when(session.getRsn()).thenReturn("Zezima");
		configStore.put("flipFinderSourced_Zezima", "[100,200]");
		when(session.getFlipFinderSourcedItems()).thenReturn(Collections.emptySet());

		// A transient empty set during logout must not erase the saved data.
		service.persistOfferState();
		assertEquals("[100,200]", configStore.get("flipFinderSourced_Zezima"));
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
		configStore.put(SYNC_MARKER_ZEZIMA, PRIOR_SYNC_AT);
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
		configStore.put(SYNC_MARKER_ZEZIMA, PRIOR_SYNC_AT); // fresh since last sync (activity 2000)
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
		configStore.put(SYNC_MARKER_ZEZIMA, PRIOR_SYNC_AT); // fresh since last sync (activity 2000)
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
		// Live slots ARE readable (a different offer occupies slot 1), so reconcile can correctly
		// classify the gone 4824 record as offline-collected and terminalize it.
		ItemComposition comp999 = itemComp("i999");
		when(itemManager.getItemComposition(999)).thenReturn(comp999);
		GrandExchangeOffer live999 = geOffer(999, GrandExchangeOfferState.BUYING, 5, 100);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[]{null, live999});

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

	/**
	 * Blob-growth guard (#759 release): at preload the GE slots are usually not loaded yet, so the
	 * live snapshot is empty. Reconciling a still-live persisted offer against that empty snapshot
	 * must NOT manufacture a terminal COLLECTED duplicate — doing so grew the persisted blob ~8
	 * records every login. When slots are unreadable, classification is deferred to the +2s sync.
	 */
	@Test
	public void preloadWithUnloadedSlots_doesNotManufactureTerminalDuplicate()
	{
		when(session.getRsn()).thenReturn("Zezima");

		// A still-live BUY offer persisted from last session (NEW, slot 0).
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 7777, 0, 10), 1000L);
		service.persistOfferState();
		store.importRecords(Collections.emptyList());

		// GE slots not loaded yet at preload time (the login-timing race) → empty snapshot.
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[0]);

		service.preloadPersistedOffers();

		// The record must be CARRIED, not dropped. This assertion used to require the store to be
		// empty for the item, which conflated "do not manufacture a terminal duplicate" with
		// "discard the record" — and the discard erased a still-held position's cost basis on the
		// persist that follows. Classification is deferred to the +2s sync, which cannot classify a
		// record that is no longer there.
		List<OfferRecord> carried = store.forItem(7777);
		assertEquals("record carried across an unreadable-slot preload", 1, carried.size());
		assertFalse("no COLLECTED duplicate manufactured when GE slots are unloaded",
			carried.get(0).getState().isTerminal());
	}

	@Test
	public void preload_withUnreadableGeSnapshot_preservesLiveOffers()
	{
		when(session.getRsn()).thenReturn("Zezima");
		// A live buy sits in slot 0 and is persisted.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), 1000L);
		service.persistOfferState();
		org.junit.Assert.assertNotNull("sanity: live offer present before preload", store.bySlot(0));

		// Simulate a world hop: the GE offers array is not loaded yet.
		when(client.getGrandExchangeOffers()).thenReturn(null);
		service.preloadPersistedOffers();

		org.junit.Assert.assertNotNull("live offer must survive an unreadable-snapshot preload", store.bySlot(0));
		assertEquals(1234, store.bySlot(0).getItemId());
	}

	@Test
	public void preload_withNonNullButEmptyGeSnapshot_preservesLiveOffers()
	{
		when(session.getRsn()).thenReturn("Zezima");
		// A live buy sits in slot 0 and is persisted.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), 1000L);
		service.persistOfferState();
		org.junit.Assert.assertNotNull("sanity: live offer present before preload", store.bySlot(0));

		// World hop: the GE offers array is present but not yet populated — every slot reads EMPTY.
		GrandExchangeOffer empty = mock(GrandExchangeOffer.class);
		when(empty.getState()).thenReturn(GrandExchangeOfferState.EMPTY);
		when(client.getGrandExchangeOffers())
			.thenReturn(new GrandExchangeOffer[] { empty, empty, empty, empty, empty, empty, empty, empty });
		service.preloadPersistedOffers();

		org.junit.Assert.assertNotNull("live offer must survive a non-null-but-empty snapshot preload",
			store.bySlot(0));
		assertEquals(1234, store.bySlot(0).getItemId());
	}

	/**
	 * A collected buy is the cost basis for the sell that follows it, so breakeven and profit go
	 * unresolvable if a reconcile drops it. LOGGED_IN fires on every world hop, so this ran
	 * mid-session with no logout, and the following persist wrote the loss through to disk.
	 */
	@Test
	public void preload_withReadableGeSnapshot_preservesCollectedBuyHistory()
	{
		when(session.getRsn()).thenReturn("Zezima");

		// Wall-clock relative: the service reconciles against System.currentTimeMillis().
		long now = System.currentTimeMillis();

		// Earlier this session: bought 10 of item 4444 and collected them (terminal history).
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 4444, 0, 10), now - 300_000L);
		store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 4444, 10, 10), now - 200_000L);
		store.apply(sig(0, GrandExchangeOfferState.EMPTY, 4444, 10, 10), now - 100_000L);
		// A live sell for a different item occupies slot 1.
		store.apply(sig(1, GrandExchangeOfferState.SELLING, 5555, 0, 10), now - 50_000L);
		service.persistOfferState();

		assertFalse("sanity: collected buy present before preload", store.forItem(4444).isEmpty());

		// World hop where the GE snapshot IS readable: slot 1 still holds the live sell.
		ItemComposition comp5555 = itemComp("i5555");
		when(itemManager.getItemComposition(5555)).thenReturn(comp5555);
		GrandExchangeOffer liveSell = geOffer(5555, GrandExchangeOfferState.SELLING, 10, 100);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[]{null, liveSell});

		service.preloadPersistedOffers();

		boolean survivedInStore = !store.forItem(4444).isEmpty();

		// The next persist writes the store back over the saved blob, so if the record was
		// dropped in memory it is also gone from disk — a relog cannot bring it back.
		store.apply(sig(2, GrandExchangeOfferState.BUYING, 6666, 0, 5), 5000L);
		service.persistOfferState();
		boolean survivedInPersistence = configStore.get("persistedOffers_Zezima") != null
			&& configStore.get("persistedOffers_Zezima").contains("4444");

		assertTrue("collected buy history must survive a readable-snapshot preload", survivedInStore);
		assertTrue("collected buy history must survive the following persist", survivedInPersistence);
	}

	@Test
	public void terminalHistoryRetention_dropsRecordsOlderThanTheWindow()
	{
		long now = 10_000_000_000L;
		// A collect only terminalizes a filled offer, so each record runs BUYING -> BOUGHT -> EMPTY.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 111, 0, 10), now);
		store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 111, 10, 10), now);
		store.apply(sig(0, GrandExchangeOfferState.EMPTY, 111, 10, 10),
			now - OfflineSyncService.TERMINAL_HISTORY_RETENTION_MS - 1);
		store.apply(sig(1, GrandExchangeOfferState.BUYING, 222, 0, 10), now);
		store.apply(sig(1, GrandExchangeOfferState.BOUGHT, 222, 10, 10), now);
		store.apply(sig(1, GrandExchangeOfferState.EMPTY, 222, 10, 10), now - 1000L);

		List<OfferRecord> retained =
			service.retainRecentTerminalHistory(store.export(), now);

		assertEquals("only the in-window terminal record is retained", 1, retained.size());
		assertEquals(222, retained.get(0).getItemId());
	}

	/**
	 * Age is the wrong sole criterion. A buy collected two days ago is still the cost basis for
	 * stock the player is holding right now, and dropping it puts breakeven back to "?" — the same
	 * defect the retention was added to fix, just on a slower clock.
	 */
	@Test
	public void terminalHistoryRetention_keepsRecordsBackingAnOpenPosition()
	{
		when(session.getRsn()).thenReturn("Zezima");
		long now = System.currentTimeMillis();
		long longAgo = now - OfflineSyncService.TERMINAL_HISTORY_RETENTION_MS - 60_000L;

		// Bought and collected well outside the window, but never sold: the position is still open.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 333, 0, 10), longAgo);
		store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 333, 10, 10), longAgo);
		store.apply(sig(0, GrandExchangeOfferState.EMPTY, 333, 10, 10), longAgo);
		ledger.recordFill("Zezima", 333, true, 10);

		// Equally old, but fully liquidated — nothing depends on it any more.
		store.apply(sig(1, GrandExchangeOfferState.BUYING, 444, 0, 10), longAgo);
		store.apply(sig(1, GrandExchangeOfferState.BOUGHT, 444, 10, 10), longAgo);
		store.apply(sig(1, GrandExchangeOfferState.EMPTY, 444, 10, 10), longAgo);
		ledger.recordFill("Zezima", 444, true, 10);
		ledger.recordFill("Zezima", 444, false, 10);

		List<OfferRecord> retained = service.retainRecentTerminalHistory(store.export(), now);

		java.util.Set<Integer> items = new java.util.HashSet<>();
		for (OfferRecord r : retained)
		{
			items.add(r.getItemId());
		}
		assertTrue("a held position keeps its basis however old", items.contains(333));
		assertFalse("a liquidated position ages out as before", items.contains(444));
	}

	/**
	 * The exemption is only as good as the ledger backing it. On a cold start the ledger lives in
	 * config, not memory, so it has to be restored before retention asks it what is still held —
	 * otherwise every held position reads as zero and its basis ages out exactly as before.
	 */
	@Test
	public void terminalHistoryRetention_consultsAPersistedLedgerOnAColdStart()
	{
		when(session.getRsn()).thenReturn("Zezima");
		long longAgo = System.currentTimeMillis()
			- OfflineSyncService.TERMINAL_HISTORY_RETENTION_MS - 60_000L;

		// An old, collected buy for an item the player still holds.
		store.apply(sig(0, GrandExchangeOfferState.BUYING, 333, 0, 10), longAgo);
		store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 333, 10, 10), longAgo);
		store.apply(sig(0, GrandExchangeOfferState.EMPTY, 333, 10, 10), longAgo);
		service.persistOfferState();

		// A cold start: the ledger is empty in memory and its state sits in config.
		configStore.put("roundTripLedger_Zezima", "{\"333\":{\"heldQuantity\":10,\"cycleId\":1}}");
		store.importRecords(Collections.emptyList());
		when(client.getGrandExchangeOffers()).thenReturn(null);

		service.preloadPersistedOffers();

		assertFalse("the held position's basis must survive a cold start",
			store.forItem(333).isEmpty());
	}

	@Test
	public void terminalHistoryRetention_capsRecordCountKeepingNewest()
	{
		long now = 10_000_000_000L;
		for (int i = 0; i < OfflineSyncService.MAX_RETAINED_TERMINAL_RECORDS + 25; i++)
		{
			int itemId = 1000 + i;
			store.apply(sig(0, GrandExchangeOfferState.BUYING, itemId, 0, 1), now);
			store.apply(sig(0, GrandExchangeOfferState.BOUGHT, itemId, 1, 1), now);
			// Newer index == more recent activity.
			store.apply(sig(0, GrandExchangeOfferState.EMPTY, itemId, 1, 1), now - 100_000L + i);
		}

		List<OfferRecord> retained =
			service.retainRecentTerminalHistory(store.export(), now);

		assertEquals("retained set is capped", OfflineSyncService.MAX_RETAINED_TERMINAL_RECORDS,
			retained.size());
		assertEquals("newest record is kept", 1000 + OfflineSyncService.MAX_RETAINED_TERMINAL_RECORDS + 24,
			retained.get(0).getItemId());
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
