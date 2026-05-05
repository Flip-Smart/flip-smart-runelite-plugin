package com.flipsmart;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

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
		GEHistoryService geHistoryService)
	{
		this.session = session;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.gson = gson;
		this.client = client;
		this.clientThread = clientThread;
		this.activeFlipTracker = activeFlipTracker;
		this.geHistoryService = geHistoryService;
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
		String offersKey = getPersistedOffersKey();
		String collectedKey = getCollectedItemsKey();
		String quantitiesKey = getCollectedQuantitiesKey();

		// Persist tracked offers
		if (session.getTrackedOffers().isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, offersKey);
			configManager.unsetConfiguration(CONFIG_GROUP, PERSISTED_OFFERS_FALLBACK_KEY);
			log.debug("No tracked offers to persist for {}", session.getRsn());
		}
		else
		{
			try
			{
				Map<Integer, TrackedOffer> offersToSave = session.getOffersForPersistence();
				String json = gson.toJson(offersToSave);
				configManager.setConfiguration(CONFIG_GROUP, offersKey, json);
				configManager.setConfiguration(CONFIG_GROUP, PERSISTED_OFFERS_FALLBACK_KEY, json);
				log.debug("Persisted {} tracked offers for {} (offline sync)", offersToSave.size(), session.getRsn());
			}
			catch (Exception e)
			{
				log.error("Failed to persist offer state for {}: {}", session.getRsn(), e.getMessage());
			}
		}

		// Persist collected item IDs (items bought but not yet sold)
		String savedAtKey = getCollectedItemsSavedAtKey();
		if (session.getCollectedItemIds().isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, collectedKey);
			configManager.unsetConfiguration(CONFIG_GROUP, quantitiesKey);
			configManager.unsetConfiguration(CONFIG_GROUP, savedAtKey);
			log.debug("No collected items to persist for {}", session.getRsn());
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
	 * Preload persisted offer state into the session so that timestamps
	 * are available during the login burst (before syncOfflineFills runs).
	 *
	 * This mirrors the Flipping Utilities approach: load persisted data
	 * into memory BEFORE game events fire, so createWithPreservedTimestamps
	 * can find the existing offer with its original timestamp.
	 */
	public void preloadPersistedOffers()
	{
		Map<Integer, TrackedOffer> persisted = loadPersistedOffers();
		if (persisted.isEmpty())
		{
			persisted = loadPersistedOffersByKeyScan();
			if (persisted.isEmpty())
			{
				return;
			}
		}

		for (Map.Entry<Integer, TrackedOffer> entry : persisted.entrySet())
		{
			TrackedOffer offer = entry.getValue();
			if (offer.getCreatedAtMillis() > 0)
			{
				session.putTrackedOffer(entry.getKey(), offer);
			}
		}

		log.debug("Preloaded {} persisted offers into session for timestamp preservation",
			persisted.size());
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

		Map<Integer, TrackedOffer> persistedOffers = loadPersistedOffers();
		log.debug("Loaded {} persisted offers, comparing with {} current offers",
			persistedOffers.size(), session.getTrackedOffers().size());

		// Hand the snapshot to GEHistoryService so fully-completed offline trades
		// (whose live TrackedOffer no longer exists post-sync) can still be
		// matched and backfilled when the user opens the History tab.
		geHistoryService.setRecentlyPersistedOffers(persistedOffers);

		if (!session.getTrackedOffers().isEmpty())
		{
			syncCurrentOffersWithPersisted(persistedOffers);
		}

		if (!persistedOffers.isEmpty())
		{
			handleEmptyPersistedSlots(persistedOffers);
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
		if (session.hasInFlightBuyOfferForItem(itemId)
			|| session.hasUncollectedBuyOfferForItem(itemId)
			|| session.hasActiveSellSlotForItem(itemId))
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

	private void syncCurrentOffersWithPersisted(Map<Integer, TrackedOffer> persistedOffers)
	{
		for (Map.Entry<Integer, TrackedOffer> entry : session.getTrackedOffers().entrySet())
		{
			int slot = entry.getKey();
			TrackedOffer currentOffer = entry.getValue();
			TrackedOffer persistedOffer = persistedOffers.get(slot);

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
				if (currentOffer.isBuy() && currentOffer.getPreviousQuantitySold() > 0)
				{
					// Track inventory locally so a sell can be queued; backend rejects
					// is_offline_fill submissions after #597, so no transaction is recorded.
					session.addCollectedItem(currentOffer.getItemId(), currentOffer.getPreviousQuantitySold());
					geHistoryService.registerOfflineFill(currentOffer.getItemId());
				}
			}
		}
	}

	private void restoreTimestampIfOlder(TrackedOffer current, TrackedOffer persisted)
	{
		if (persisted.getCreatedAtMillis() > 0
			&& persisted.getCreatedAtMillis() < current.getCreatedAtMillis())
		{
			current.setCreatedAtMillis(persisted.getCreatedAtMillis());
		}
	}

	/**
	 * Handle persisted slots that are now empty (offer completed or cancelled offline).
	 */
	private void handleEmptyPersistedSlots(Map<Integer, TrackedOffer> persistedOffers)
	{
		for (Map.Entry<Integer, TrackedOffer> entry : persistedOffers.entrySet())
		{
			int slot = entry.getKey();
			TrackedOffer persistedOffer = entry.getValue();

			if (session.getTrackedOffers().containsKey(slot))
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
	private void handleEmptySellSlot(TrackedOffer persistedOffer)
	{
		clientThread.invokeLater(() ->
		{
			int inventoryCount = getInventoryCountForItem(persistedOffer.getItemId());
			if (inventoryCount == 0)
			{
				log.debug("Sell slot empty for {} and no inventory - dismissing active flip",
					persistedOffer.getItemName());
				activeFlipTracker.dismissFlip(persistedOffer.getItemId());
				geHistoryService.registerOfflineFill(persistedOffer.getItemId());
			}
		});
	}

	/**
	 * Handle an empty slot that was previously a buy order.
	 */
	private void handleEmptyBuySlot(TrackedOffer persistedOffer)
	{
		// getItemContainer requires the client thread
		clientThread.invokeLater(() ->
		{
			int inventoryCount = getInventoryCountForItem(persistedOffer.getItemId());
			int trackedFills = persistedOffer.getPreviousQuantitySold();

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
	private void handleBuyOrderWithInventory(TrackedOffer persistedOffer, int inventoryCount, int trackedFills)
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
	private int calculateActualFills(TrackedOffer persistedOffer, int inventoryCount, int trackedFills)
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
	private void syncOfflineCompletedOrder(TrackedOffer persistedOffer, int inventoryCount, int trackedFills, int actualFills)
	{
		log.debug("Detected offline completion for {} - tracked {} fills but have {} in inventory. Syncing {} items with backend.",
			persistedOffer.getItemName(), trackedFills, inventoryCount, actualFills);

		String rsn = getRsnSafe().orElse(null);
		if (rsn == null)
		{
			return;
		}

		int syncPrice = (persistedOffer.getPreviousSpent() > 0 && persistedOffer.getPreviousQuantitySold() > 0)
			? (int)(persistedOffer.getPreviousSpent() / persistedOffer.getPreviousQuantitySold())
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
	 * Load previously persisted tracked offers from config.
	 */
	private Map<Integer, TrackedOffer> loadPersistedOffers()
	{
		String key = getPersistedOffersKey();

		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, key);
			if (json == null || json.isEmpty())
			{
				log.debug("No persisted offers found for {}", session.getRsn());
				return new HashMap<>();
			}

			Type type = new TypeToken<Map<Integer, TrackedOffer>>(){}.getType();
			Map<Integer, TrackedOffer> offers = gson.fromJson(json, type);
			log.debug("Loaded {} persisted offers for {}", offers != null ? offers.size() : 0, session.getRsn());
			return offers != null ? offers : new HashMap<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted offers for {}: {}", session.getRsn(), e.getMessage());
			return new HashMap<>();
		}
	}

	/**
	 * Scan all config keys matching persistedOffers_* to find offer data
	 * when the RSN-specific key lookup failed (cold start, RSN not yet available).
	 */
	private Map<Integer, TrackedOffer> loadPersistedOffersByKeyScan()
	{
		try
		{
			Map<Integer, TrackedOffer> result = tryLoadOffersFromKey(PERSISTED_OFFERS_FALLBACK_KEY);
			if (!result.isEmpty())
			{
				log.debug("Loaded {} offers from fallback key", result.size());
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
				result = tryLoadOffersFromKey(keyPart);
				if (!result.isEmpty())
				{
					log.debug("Loaded {} offers via key scan (key: {})", result.size(), keyPart);
					return result;
				}
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted offers by key scan: {}", e.getMessage());
		}
		return new HashMap<>();
	}

	private Map<Integer, TrackedOffer> tryLoadOffersFromKey(String key)
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, key);
		if (json == null || json.isEmpty())
		{
			return new HashMap<>();
		}
		Type type = new TypeToken<Map<Integer, TrackedOffer>>(){}.getType();
		Map<Integer, TrackedOffer> offers = gson.fromJson(json, type);
		return (offers != null) ? offers : new HashMap<>();
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
