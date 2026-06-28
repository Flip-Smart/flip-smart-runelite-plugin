package com.flipsmart;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferEventMapper;
import com.flipsmart.trading.OfferReconciler;
import com.flipsmart.trading.OfferStore;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles offline fill detection, offer state persistence, and collected item tracking.
 * Responsible for syncing GE offer state across login/logout cycles.
 */
@Slf4j
@Singleton
public class OfflineSyncService
{
	private static final int INVENTORY_CONTAINER_ID = 93;
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String UNKNOWN_RSN_FALLBACK = "unknown";
	private static final String PERSISTED_OFFERS_KEY_PREFIX = "persistedOffers_";
	private static final String PERSISTED_OFFERS_FALLBACK_KEY = "persistedOffers_lastSession";
	private static final String COLLECTED_ITEMS_KEY_PREFIX = "collectedItems_";
	private static final String COLLECTED_QUANTITIES_KEY_PREFIX = "collectedQuantities_";
	private static final String COLLECTED_ITEMS_SAVED_AT_KEY_PREFIX = "collectedItemsSavedAt_";
	private static final String LAST_KNOWN_RSN_KEY = "lastKnownRsn";
	// Wall-clock of the last completed offline sync, per RSN. A persisted offer is only a genuine
	// offline fill if it was active since this marker; older leftovers are already-known history.
	private static final String OFFLINE_SYNC_MARKER_KEY_PREFIX = "offlineSyncAt_";

	/** Persisted collected-item entries older than this are dropped on restore. */
	static final long MAX_PERSISTED_COLLECTED_AGE_MS = 7L * 24 * 60 * 60 * 1000;
	private final PlayerSession session;
	private final ConfigManager configManager;
	private final Gson gson;
	private final Client client;
	private final ClientThread clientThread;
	private final ActiveFlipTracker activeFlipTracker;
	private final GEHistoryService geHistoryService;
	private final OfferStore offerStore;
	private final ItemManager itemManager;

	/** Callback invoked after sync is complete (for scheduling post-sync tasks) */
	private Runnable onSyncComplete;

	@Inject
	public OfflineSyncService(
		PlayerSession session,
		ConfigManager configManager,
		Gson gson,
		Client client,
		ClientThread clientThread,
		ActiveFlipTracker activeFlipTracker,
		GEHistoryService geHistoryService,
		OfferStore offerStore,
		ItemManager itemManager)
	{
		this.session = session;
		this.configManager = configManager;
		this.gson = gson;
		this.client = client;
		this.clientThread = clientThread;
		this.activeFlipTracker = activeFlipTracker;
		this.geHistoryService = geHistoryService;
		this.offerStore = offerStore;
		this.itemManager = itemManager;
	}

	public void setOnSyncComplete(Runnable onSyncComplete)
	{
		this.onSyncComplete = onSyncComplete;
	}

	/**
	 * Restore collected item IDs from persisted config.
	 * These are items that were bought but not yet sold when the player logged out.
	 * Entries older than {@link #MAX_PERSISTED_COLLECTED_AGE_MS} are dropped.
	 */
	public void restoreCollectedItems()
	{
		String key = getCollectedItemsKey();
		log.debug("Attempting to restore collected items for RSN: {} (key: {})", session.getRsn(), key);

		if (isPersistedCollectedTooOld())
		{
			log.debug("Persisted collected items for {} are stale - clearing", session.getRsn());
			clearPersistedCollectedItems();
			session.clearCollectedItems();
			return;
		}

		Set<Integer> persisted = loadPersistedCollectedItems();
		if (!persisted.isEmpty())
		{
			Map<Integer, Integer> quantities = loadPersistedCollectedQuantities();
			session.restoreCollectedItems(persisted, quantities);
			log.debug("Restored {} collected items from previous session: {} (with {} quantities)",
				persisted.size(), persisted, quantities.size());
		}
		else
		{
			log.debug("No collected items found in config for RSN: {}", session.getRsn());
			session.clearCollectedItems();
		}

	}

	/** Missing timestamps (legacy data) are treated as stale. */
	private boolean isPersistedCollectedTooOld()
	{
		String savedAtKey = getCollectedItemsSavedAtKey();
		String collectedKey = getCollectedItemsKey();

		String collectedJson = configManager.getConfiguration(CONFIG_GROUP, collectedKey);
		if (collectedJson == null || collectedJson.isEmpty())
		{
			return false;
		}

		String savedAtStr = configManager.getConfiguration(CONFIG_GROUP, savedAtKey);
		if (savedAtStr == null || savedAtStr.isEmpty())
		{
			return true;
		}

		try
		{
			long savedAt = Long.parseLong(savedAtStr);
			return System.currentTimeMillis() - savedAt > MAX_PERSISTED_COLLECTED_AGE_MS;
		}
		catch (NumberFormatException e)
		{
			return true;
		}
	}

	private void clearPersistedCollectedItems()
	{
		configManager.unsetConfiguration(CONFIG_GROUP, getCollectedItemsKey());
		configManager.unsetConfiguration(CONFIG_GROUP, getCollectedQuantitiesKey());
		configManager.unsetConfiguration(CONFIG_GROUP, getCollectedItemsSavedAtKey());
	}

	/**
	 * Persist the current GE offer state to config for offline tracking.
	 * Called when the player logs out or plugin shuts down.
	 */
	public void persistOfferState()
	{
		String rsn = resolvePersistenceRsn();
		if (rsn == null)
		{
			log.debug("Skipping offer persistence: no resolvable RSN (logout window)");
			return;
		}

		String offersKey = PERSISTED_OFFERS_KEY_PREFIX + rsn;
		String collectedKey = COLLECTED_ITEMS_KEY_PREFIX + rsn;
		String quantitiesKey = COLLECTED_QUANTITIES_KEY_PREFIX + rsn;

		List<OfferRecord> offersToSave = offerStore.export();
		if (offersToSave.isEmpty())
		{
			// The store is transiently empty during the logout/hop transition. Unsetting
			// the persisted keys here would wipe still-valid saved offers; instead preserve
			// them. Stale entries (slots no longer live) are downgraded to terminal history
			// by OfferReconciler on restore, so leaving them is benign.
			log.debug("Offer store empty — preserving existing persisted offers for {}", rsn);
		}
		else
		{
			try
			{
				String json = gson.toJson(offersToSave);
				configManager.setConfiguration(CONFIG_GROUP, offersKey, json);
				configManager.setConfiguration(CONFIG_GROUP, PERSISTED_OFFERS_FALLBACK_KEY, json);
				log.debug("Persisted {} offer records for {} (offline sync)", offersToSave.size(), rsn);
			}
			catch (Exception e)
			{
				log.error("Failed to persist offer state for {}: {}", rsn, e.getMessage());
			}
		}

		// Persist collected item IDs (items bought but not yet sold)
		String savedAtKey = COLLECTED_ITEMS_SAVED_AT_KEY_PREFIX + rsn;
		if (session.getCollectedItemIds().isEmpty())
		{
			// Same don't-destroy-on-empty treatment as the offers branch: a transient
			// empty collected set during the logout/hop window must not wipe saved data.
			log.debug("Collected set empty — preserving existing persisted collected items for {}", rsn);
		}
		else
		{
			try
			{
				String json = gson.toJson(new java.util.ArrayList<>(session.getCollectedItemsForPersistence()));
				configManager.setConfiguration(CONFIG_GROUP, collectedKey, json);

				Map<Integer, Integer> quantities = session.getCollectedQuantitiesForPersistence();
				if (!quantities.isEmpty())
				{
					String qtyJson = gson.toJson(quantities);
					configManager.setConfiguration(CONFIG_GROUP, quantitiesKey, qtyJson);
				}
				else
				{
					configManager.unsetConfiguration(CONFIG_GROUP, quantitiesKey);
				}

				configManager.setConfiguration(CONFIG_GROUP, savedAtKey, Long.toString(System.currentTimeMillis()));

				log.debug("Persisted {} collected item IDs for {} (active flips)", session.getCollectedItemIds().size(), session.getRsn());
			}
			catch (Exception e)
			{
				log.error("Failed to persist collected items for {}: {}", session.getRsn(), e.getMessage());
			}
		}

	}

	/**
	 * Preload persisted offer state before the login burst fires, so the store
	 * holds the correct reconciled state and createWithPreservedTimestamps finds
	 * the original timestamp.
	 *
	 * Persisted {@link OfferRecord}s are reconciled against the live GE slots:
	 * reattached records are imported into the store (preserving offerId and
	 * timestamps so the burst event records only a delta), offline-collected
	 * records are imported as terminal history, and live slots with no persisted
	 * match are left for the normal event path to mint.
	 */
	public void preloadPersistedOffers()
	{
		List<OfferRecord> persistedRecords = loadPersistedOfferRecords();
		if (persistedRecords.isEmpty())
		{
			return;
		}

		reconcilePersistedIntoStore(persistedRecords);

		log.debug("Preloaded {} persisted offer records into store for timestamp preservation",
			persistedRecords.size());
	}

	/**
	 * Reconcile persisted records against the live GE slots and apply the plan to
	 * the store: reattached records are imported with their slot restored; minted
	 * live slots are left to the normal event burst; offline-collected records are
	 * imported as terminal history so the store carries the full set.
	 */
	private void reconcilePersistedIntoStore(List<OfferRecord> persistedRecords)
	{
		long now = System.currentTimeMillis();
		List<OfferSignal> liveSlots = buildLiveSlotSignals();
		OfferReconciler.Plan plan = OfferReconciler.reconcile(persistedRecords, liveSlots, now);

		List<OfferRecord> toImport = new ArrayList<>();
		toImport.addAll(plan.reattached);
		// Offline-collected records often persist as CANCELLED_PARTIAL, which is non-terminal:
		// imported as-is they masquerade as live offers, so liveOffers() keeps returning them
		// (auto-mode stale-prompt flap) and pruning refuses to drop them. Terminalize to COLLECTED
		// so they read as finished history. plan.reattached (still-live slots) is left untouched.
		for (OfferRecord collected : plan.offlineCollected)
		{
			toImport.add(collected.withState(OfferState.COLLECTED, now));
		}
		offerStore.importRecords(toImport);

		log.debug("Reconciled persisted offers into store: {} reattached, {} minted, {} offline-collected",
			plan.reattached.size(), plan.minted.size(), plan.offlineCollected.size());
	}

	/** Reduce the live GE slots to {@link OfferSignal}s for reconciliation. */
	private List<OfferSignal> buildLiveSlotSignals()
	{
		List<OfferSignal> signals = new ArrayList<>();
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return signals;
		}
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			int itemId = offer.getItemId();
			String itemName = itemManager != null ? itemManager.getItemComposition(itemId).getName() : "";
			signals.add(OfferEventMapper.toSignal(
				slot,
				offer.getState(),
				itemId,
				itemName,
				offer.getTotalQuantity(),
				offer.getPrice(),
				offer.getQuantitySold(),
				offer.getSpent()));
		}
		return signals;
	}

	/**
	 * Sync fills that occurred while offline.
	 * Records current GE state to the backend.
	 */
	public void syncOfflineFills()
	{
		if (session.isOfflineSyncCompleted())
		{
			return;
		}
		session.setOfflineSyncCompleted(true);

		List<OfferRecord> persistedRecords = loadPersistedOfferRecords();

		// Live GE slots and item-name resolution must be read on the client thread —
		// this method is invoked from a Swing timer, and touching client/itemManager
		// off-thread throws.
		clientThread.invokeLater(() -> reconcileOfflineFills(persistedRecords));
	}

	private void reconcileOfflineFills(List<OfferRecord> persistedRecords)
	{
		long now = System.currentTimeMillis();
		long freshnessThreshold = readOfflineFillFreshnessThreshold(now);
		Map<Integer, OfferRecord> currentOffers = liveOffersBySlot();
		log.debug("Loaded {} persisted offers, comparing with {} current offers",
			persistedRecords.size(), currentOffers.size());

		// Hand the snapshot to GEHistoryService so fully-completed offline trades
		// (whose live record no longer exists post-sync) can still be matched and
		// backfilled when the user opens the History tab.
		geHistoryService.setRecentlyPersistedOffers(persistedRecords);

		// Reconcile persisted records against live slots to determine which offers
		// completed or were cancelled while offline. Records last active before the
		// freshness cutoff are leftovers from prior sessions (already backfilled) and
		// are routed to staleHistory so they never re-fire the "open GE History" prompt.
		List<OfferSignal> liveSlots = buildLiveSlotSignals();
		OfferReconciler.Plan plan = OfferReconciler.reconcile(persistedRecords, liveSlots, now, freshnessThreshold);

		// Restore original timestamps on still-live offers whose persisted record is older.
		for (OfferRecord reattached : plan.reattached)
		{
			OfferRecord live = findLiveRecordForSlot(reattached.getSlot(), currentOffers);
			if (live != null)
			{
				restoreTimestampIfOlder(live, reattached);
			}
		}

		// Each offline-collected record represents an offer whose slot is now gone on login.
		// Register exactly one backfill per record, using the reconciled filled qty — never
		// the order total — so a partial-cancel contributes only what was actually traded.
		for (OfferRecord record : plan.offlineCollected)
		{
			int itemId = record.getItemId();
			if (record.isBuy() && record.getFilledQuantity() > 0)
			{
				// Only re-add a collected buy that is actually still in inventory. A buy the
				// player already sold/used offline has no inventory and must not be re-injected,
				// or it strands a phantom collect/sell prompt every login. Backfill always fires.
				int inventory = inventoryCountOrZero(itemId);
				if (inventory > 0)
				{
					session.addCollectedItem(itemId, Math.min(inventory, record.getFilledQuantity()));
				}
				geHistoryService.registerOfflineFill(itemId);
			}
			else if (!record.isBuy())
			{
				geHistoryService.registerOfflineFill(itemId);
			}
		}

		if (!plan.staleHistory.isEmpty())
		{
			log.debug("Skipped {} stale persisted offer(s) older than last sync — already-known history, not prompted",
				plan.staleHistory.size());
		}

		pruneStaleCollectedItems();
		writeOfflineSyncMarker(now);
		persistOfferState();

		if (onSyncComplete != null)
		{
			onSyncComplete.run();
		}
	}

	/**
	 * Freshness cutoff for treating a persisted offer as a genuine offline fill: the wall-clock of
	 * the previous completed offline sync. On the first sync for an account (marker absent) the whole
	 * persisted blob predates the marker, so we return {@code now} — every existing record is treated
	 * as already-known history and suppressed, rather than nagging for a backlog the backend already has.
	 */
	private long readOfflineFillFreshnessThreshold(long now)
	{
		String rsn = resolvePersistenceRsn();
		if (rsn == null)
		{
			return now;
		}
		String raw = configManager.getConfiguration(CONFIG_GROUP, OFFLINE_SYNC_MARKER_KEY_PREFIX + rsn);
		if (raw == null || raw.isEmpty())
		{
			return now;
		}
		try
		{
			return Long.parseLong(raw);
		}
		catch (NumberFormatException e)
		{
			return now;
		}
	}

	private void writeOfflineSyncMarker(long now)
	{
		String rsn = resolvePersistenceRsn();
		if (rsn != null)
		{
			configManager.setConfiguration(CONFIG_GROUP, OFFLINE_SYNC_MARKER_KEY_PREFIX + rsn, Long.toString(now));
		}
	}

	private OfferRecord findLiveRecordForSlot(Integer slot, Map<Integer, OfferRecord> currentOffers)
	{
		if (slot == null)
		{
			return null;
		}
		return currentOffers.get(slot);
	}

	/**
	 * Drop collectedItems entries with no inventory, in-flight/uncollected buy,
	 * or active sell — they are "phantom" sell prompts from prior sessions (#451).
	 * Must be called from the client thread.
	 */
	int pruneStaleCollectedItems()
	{
		Set<Integer> currentIds = session.getCollectedItemIds();
		if (currentIds.isEmpty())
		{
			return 0;
		}

		List<Integer> toRemove = new ArrayList<>();
		for (int itemId : currentIds)
		{
			if (!isItemKnownPresent(itemId))
			{
				toRemove.add(itemId);
			}
		}
		for (int itemId : toRemove)
		{
			// A collected item disappearing without a tracked sell having
			// happened in this session means it was sold offline. Register
			// it for History backfill before we drop it from local state.
			geHistoryService.registerOfflineFill(itemId);
			session.removeCollectedItem(itemId);
		}
		return toRemove.size();
	}


	/**
	 * Inventory count for the add-collected path: returns 0 when inventory is genuinely empty
	 * or unavailable. Unlike {@link #isItemKnownPresent} (which is conservative against pruning),
	 * the add path must err toward NOT adding, since the History backfill still fires regardless.
	 */
	private int inventoryCountOrZero(int itemId)
	{
		try
		{
			return Math.max(0, activeFlipTracker.getInventoryCountForItem(itemId));
		}
		catch (Exception | AssertionError e)
		{
			return 0;
		}
	}

	private boolean isItemKnownPresent(int itemId)
	{
		if (offerStore.hasLiveBuyOfferForItem(itemId)
			|| offerStore.hasActiveSellOfferForItem(itemId))
		{
			return true;
		}
		try
		{
			return activeFlipTracker.getInventoryCountForItem(itemId) > 0;
		}
		catch (Exception | AssertionError e)
		{
			// Conservative on inventory-unavailable: never wrongly prune.
			return true;
		}
	}

	private void restoreTimestampIfOlder(OfferRecord current, OfferRecord persisted)
	{
		if (persisted.getCreatedAtMillis() > 0
			&& persisted.getCreatedAtMillis() < current.getCreatedAtMillis())
		{
			offerStore.correctCreatedAt(current.getOfferId(), persisted.getCreatedAtMillis());
		}
		// The adjustment timer reads getEffectiveLastActivityAtMillis(), which prefers
		// lastActivityAtMillis — re-anchored to login on a fresh sighting — so restoring
		// createdAt alone leaves the offer's age reset across a relog.
		long persistedActivity = persisted.getEffectiveLastActivityAtMillis();
		if (persistedActivity > 0 && persistedActivity < current.getEffectiveLastActivityAtMillis())
		{
			offerStore.correctActivityAt(current.getOfferId(), persistedActivity);
		}
	}

	// =====================
	// Persistence Helpers
	// =====================

	/**
	 * Single resolution point for the RSN used to key persistence: live session
	 * RSN first, then the persisted last-known RSN. Returns null only when neither
	 * is available (e.g. mid-logout transition) so callers skip the write rather
	 * than emit a {@code null}-keyed config entry.
	 */
	String resolvePersistenceRsn()
	{
		String rsn = session.getRsn();
		if (rsn != null && !rsn.isEmpty())
		{
			return rsn;
		}
		String persisted = configManager.getConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY);
		if (persisted != null && !persisted.isEmpty())
		{
			return persisted;
		}
		return null;
	}

	public String getPersistedOffersKey()
	{
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			return PERSISTED_OFFERS_KEY_PREFIX + UNKNOWN_RSN_FALLBACK;
		}
		return PERSISTED_OFFERS_KEY_PREFIX + session.getRsn();
	}

	public String getCollectedItemsKey()
	{
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			return COLLECTED_ITEMS_KEY_PREFIX + UNKNOWN_RSN_FALLBACK;
		}
		return COLLECTED_ITEMS_KEY_PREFIX + session.getRsn();
	}

	public String getCollectedQuantitiesKey()
	{
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			return COLLECTED_QUANTITIES_KEY_PREFIX + UNKNOWN_RSN_FALLBACK;
		}
		return COLLECTED_QUANTITIES_KEY_PREFIX + session.getRsn();
	}

	public String getCollectedItemsSavedAtKey()
	{
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			return COLLECTED_ITEMS_SAVED_AT_KEY_PREFIX + UNKNOWN_RSN_FALLBACK;
		}
		return COLLECTED_ITEMS_SAVED_AT_KEY_PREFIX + session.getRsn();
	}

	/**
	 * Load previously persisted collected item IDs from config.
	 */
	private Set<Integer> loadPersistedCollectedItems()
	{
		String key = getCollectedItemsKey();

		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, key);
			if (json == null || json.isEmpty())
			{
				return new HashSet<>();
			}

			Type type = new TypeToken<java.util.List<Integer>>(){}.getType();
			java.util.List<Integer> items = gson.fromJson(json, type);
			log.debug("Loaded {} persisted collected items for {}", items != null ? items.size() : 0, session.getRsn());
			return items != null ? new HashSet<>(items) : new HashSet<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted collected items for {}: {}", session.getRsn(), e.getMessage());
			return new HashSet<>();
		}
	}

	/**
	 * Load previously persisted collected quantities from config.
	 */
	private Map<Integer, Integer> loadPersistedCollectedQuantities()
	{
		String key = getCollectedQuantitiesKey();

		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, key);
			if (json == null || json.isEmpty())
			{
				return new HashMap<>();
			}

			Type type = new TypeToken<Map<Integer, Integer>>(){}.getType();
			Map<Integer, Integer> quantities = gson.fromJson(json, type);
			log.debug("Loaded {} persisted collected quantities for {}", quantities != null ? quantities.size() : 0, session.getRsn());
			return quantities != null ? quantities : new HashMap<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted collected quantities for {}: {}", session.getRsn(), e.getMessage());
			return new HashMap<>();
		}
	}

	/**
	 * Load the raw persisted {@link OfferRecord} list (RSN key, then fallback /
	 * key-scan). This is the canonical persisted state restored into the store.
	 */
	List<OfferRecord> loadPersistedOfferRecords()
	{
		List<OfferRecord> records = tryLoadRecordsFromKey(getPersistedOffersKey());
		if (!records.isEmpty())
		{
			log.debug("Loaded {} persisted offer records for {}", records.size(), session.getRsn());
			return records;
		}
		return loadPersistedOfferRecordsByKeyScan();
	}

	/**
	 * Scan all config keys matching persistedOffers_* to find offer data
	 * when the RSN-specific key lookup failed (cold start, RSN not yet available).
	 */
	private List<OfferRecord> loadPersistedOfferRecordsByKeyScan()
	{
		try
		{
			List<OfferRecord> result = tryLoadRecordsFromKey(PERSISTED_OFFERS_FALLBACK_KEY);
			if (!result.isEmpty())
			{
				log.debug("Loaded {} offer records from fallback key", result.size());
				return result;
			}

			String prefix = CONFIG_GROUP + "." + PERSISTED_OFFERS_KEY_PREFIX;
			List<String> keys = configManager.getConfigurationKeys(prefix);
			for (String fullKey : keys)
			{
				String keyPart = fullKey.substring((CONFIG_GROUP + ".").length());
				if (keyPart.equals(PERSISTED_OFFERS_FALLBACK_KEY))
				{
					continue;
				}
				result = tryLoadRecordsFromKey(keyPart);
				if (!result.isEmpty())
				{
					log.debug("Loaded {} offer records via key scan (key: {})", result.size(), keyPart);
					return result;
				}
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted offer records by key scan: {}", e.getMessage());
		}
		return new ArrayList<>();
	}

	private List<OfferRecord> tryLoadRecordsFromKey(String key)
	{
		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, key);
			if (json == null || json.isEmpty())
			{
				return new ArrayList<>();
			}
			Type type = new TypeToken<List<OfferRecord>>(){}.getType();
			List<OfferRecord> records = gson.fromJson(json, type);
			return records != null ? records : new ArrayList<>();
		}
		catch (Exception e)
		{
			// Expected one-time migration: the old persistence format stored a JSON
			// object, not a List<OfferRecord> array. Deserialization fails on first
			// login after upgrade — return empty without flooding the log at ERROR.
			if (log.isDebugEnabled())
			{
				log.debug("Ignoring legacy/unreadable persisted offers at key {} ({})", key, e.getMessage());
			}
			return new ArrayList<>();
		}
	}

	/** The store's current live offers indexed by GE slot. */
	private Map<Integer, OfferRecord> liveOffersBySlot()
	{
		Map<Integer, OfferRecord> out = new HashMap<>();
		for (OfferRecord r : offerStore.liveOffers())
		{
			if (r.getSlot() != null)
			{
				out.put(r.getSlot(), r);
			}
		}
		return out;
	}

}
