package com.flipsmart;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.trading.OfferEventMapper;
import com.flipsmart.trading.OfferReconciler;
import com.flipsmart.trading.OfferStore;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
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
import java.util.Optional;
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

	/** Persisted collected-item entries older than this are dropped on restore. */
	static final long MAX_PERSISTED_COLLECTED_AGE_MS = 7L * 24 * 60 * 60 * 1000;
	private final PlayerSession session;
	private final FlipSmartApiClient apiClient;
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
		FlipSmartApiClient apiClient,
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
		this.apiClient = apiClient;
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
		toImport.addAll(plan.offlineCollected);
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
		Map<Integer, OfferRecord> persistedOffers = recordsToSlotMap(persistedRecords);
		Map<Integer, OfferRecord> currentOffers = liveOffersBySlot();
		log.debug("Loaded {} persisted offers, comparing with {} current offers",
			persistedOffers.size(), currentOffers.size());

		// Hand the snapshot to GEHistoryService so fully-completed offline trades
		// (whose live record no longer exists post-sync) can still be matched and
		// backfilled when the user opens the History tab.
		geHistoryService.setRecentlyPersistedOffers(persistedRecords);

		if (!currentOffers.isEmpty())
		{
			syncCurrentOffersWithPersisted(persistedOffers, currentOffers);
		}

		if (!persistedOffers.isEmpty())
		{
			handleEmptyPersistedSlots(persistedOffers, currentOffers);
		}

		// Inventory check requires the client thread.
		clientThread.invokeLater(() ->
		{
			pruneStaleCollectedItems();
			persistOfferState();

			if (onSyncComplete != null)
			{
				onSyncComplete.run();
			}
		});
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

	private void syncCurrentOffersWithPersisted(Map<Integer, OfferRecord> persistedOffers,
		Map<Integer, OfferRecord> currentOffers)
	{
		for (Map.Entry<Integer, OfferRecord> entry : currentOffers.entrySet())
		{
			int slot = entry.getKey();
			OfferRecord currentOffer = entry.getValue();
			OfferRecord persistedOffer = persistedOffers.get(slot);

			if (persistedOffer != null && persistedOffer.getItemId() == currentOffer.getItemId())
			{
				restoreTimestampIfOlder(currentOffer, persistedOffer);
			}
			else
			{
				if (persistedOffer != null && persistedOffer.getItemId() != currentOffer.getItemId())
				{
					// Slot reused with a different item — the persisted offer must
					// have completed offline (or been cancelled). Register it so
					// the History backfill can disambiguate via the History tab,
					// which only shows actual completions.
					geHistoryService.registerOfflineFill(persistedOffer.getItemId());
				}
				if (currentOffer.isBuy() && currentOffer.getFilledQuantity() > 0)
				{
					// Track inventory locally so a sell can be queued; backend rejects
					// is_offline_fill submissions after #597, so no transaction is recorded.
					session.addCollectedItem(currentOffer.getItemId(), currentOffer.getFilledQuantity());
					geHistoryService.registerOfflineFill(currentOffer.getItemId());
				}
			}
		}
	}

	private void restoreTimestampIfOlder(OfferRecord current, OfferRecord persisted)
	{
		if (persisted.getCreatedAtMillis() > 0
			&& persisted.getCreatedAtMillis() < current.getCreatedAtMillis())
		{
			offerStore.correctCreatedAt(current.getOfferId(), persisted.getCreatedAtMillis());
		}
	}

	/**
	 * Handle persisted slots that are now empty (offer completed or cancelled offline).
	 */
	private void handleEmptyPersistedSlots(Map<Integer, OfferRecord> persistedOffers,
		Map<Integer, OfferRecord> currentOffers)
	{
		for (Map.Entry<Integer, OfferRecord> entry : persistedOffers.entrySet())
		{
			int slot = entry.getKey();
			OfferRecord persistedOffer = entry.getValue();

			if (currentOffers.containsKey(slot))
			{
				continue;
			}

			log.debug("Slot {} is now empty (was tracking {} x{}). Checking for offline completions.",
				slot, persistedOffer.getItemName(), persistedOffer.getTotalQuantity());

			if (persistedOffer.isBuy())
			{
				handleEmptyBuySlot(persistedOffer);
			}
			else
			{
				handleEmptySellSlot(persistedOffer);
			}
		}
	}

	/**
	 * Handle an empty slot that was previously a sell order. Backend rejects offline-fill
	 * submissions (#597), so we only dismiss the local flip tracking when inventory is empty.
	 */
	private void handleEmptySellSlot(OfferRecord persistedOffer)
	{
		clientThread.invokeLater(() ->
		{
			int inventoryCount = getInventoryCountForItem(persistedOffer.getItemId());
			if (inventoryCount == 0)
			{
				// Register for History backfill instead of dismissing the local flip.
				// dismissFlip marks the buy is_manually_closed=true and bumps
				// quantity_sold=quantity on the API side, which then prevents the
				// backfill SELL from pairing with the buy. Let the backfill close
				// the flip naturally; the panel will refresh once it posts.
				log.debug("Sell slot empty for {} with no inventory — flagging for History backfill",
					persistedOffer.getItemName());
				geHistoryService.registerOfflineFill(persistedOffer.getItemId());
			}
		});
	}

	/**
	 * Handle an empty slot that was previously a buy order.
	 */
	private void handleEmptyBuySlot(OfferRecord persistedOffer)
	{
		// getItemContainer requires the client thread
		clientThread.invokeLater(() ->
		{
			int inventoryCount = getInventoryCountForItem(persistedOffer.getItemId());
			int trackedFills = persistedOffer.getFilledQuantity();

			if (inventoryCount > 0)
			{
				handleBuyOrderWithInventory(persistedOffer, inventoryCount, trackedFills);
			}
			else if (trackedFills > 0)
			{
				log.debug("No {} found in inventory (had {} fills tracked). Items may have been sold/used offline.",
					persistedOffer.getItemName(), trackedFills);
				geHistoryService.registerOfflineFill(persistedOffer.getItemId());
			}
		});
	}

	/**
	 * Handle a completed buy order that has items in inventory.
	 */
	private void handleBuyOrderWithInventory(OfferRecord persistedOffer, int inventoryCount, int trackedFills)
	{
		int actualFills = calculateActualFills(persistedOffer, inventoryCount, trackedFills);
		session.addCollectedItem(persistedOffer.getItemId(), actualFills);

		if (actualFills > trackedFills)
		{
			syncOfflineCompletedOrder(persistedOffer, inventoryCount, trackedFills, actualFills);
			geHistoryService.registerOfflineFill(persistedOffer.getItemId());
		}
		else
		{
			log.debug("Adding {} to collected tracking (had {} items filled before going offline)",
				persistedOffer.getItemName(), trackedFills);
		}
	}

	/**
	 * Calculate actual fills based on inventory count, tracked fills, and order size.
	 */
	private int calculateActualFills(OfferRecord persistedOffer, int inventoryCount, int trackedFills)
	{
		int actualFills = Math.max(inventoryCount, trackedFills);

		if (inventoryCount >= persistedOffer.getTotalQuantity())
		{
			actualFills = persistedOffer.getTotalQuantity();
		}

		return actualFills;
	}

	/**
	 * Sync an offline-completed order with the backend.
	 */
	private void syncOfflineCompletedOrder(OfferRecord persistedOffer, int inventoryCount, int trackedFills, int actualFills)
	{
		log.debug("Detected offline completion for {} - tracked {} fills but have {} in inventory. Syncing {} items with backend.",
			persistedOffer.getItemName(), trackedFills, inventoryCount, actualFills);

		String rsn = getRsnSafe().orElse(null);
		if (rsn == null)
		{
			return;
		}

		int syncPrice = (persistedOffer.getSpent() > 0 && persistedOffer.getFilledQuantity() > 0)
			? (int)(persistedOffer.getSpent() / persistedOffer.getFilledQuantity())
			: persistedOffer.getPrice();
		apiClient.syncActiveFlipAsync(
			persistedOffer.getItemId(),
			persistedOffer.getItemName(),
			actualFills,
			persistedOffer.getTotalQuantity(),
			syncPrice,
			rsn
		);
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

	/**
	 * Index records by GE slot for the offline-fill reconciliation. Only records
	 * that still carry a slot (live at persist time) participate; terminal/slot-less
	 * records were already collected and have nothing to reconcile against a slot.
	 */
	private static Map<Integer, OfferRecord> recordsToSlotMap(List<OfferRecord> records)
	{
		Map<Integer, OfferRecord> out = new HashMap<>();
		for (OfferRecord r : records)
		{
			if (r.getSlot() == null)
			{
				continue;
			}
			out.put(r.getSlot(), r);
		}
		return out;
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

	// =====================
	// Utility Methods
	// =====================

	private int getInventoryCountForItem(int itemId)
	{
		ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
		if (inventory == null)
		{
			return 0;
		}

		int count = 0;
		Item[] items = inventory.getItems();
		for (Item item : items)
		{
			if (item.getId() == itemId)
			{
				count += item.getQuantity();
			}
		}

		return count;
	}

	private Optional<String> getRsnSafe()
	{
		return session.getRsnSafe();
	}
}
