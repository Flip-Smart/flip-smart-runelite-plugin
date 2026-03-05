package com.flipsmart;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
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
	private static final String COLLECTED_ITEMS_KEY_PREFIX = "collectedItems_";
	private static final String COLLECTED_QUANTITIES_KEY_PREFIX = "collectedQuantities_";

	private final PlayerSession session;
	private final FlipSmartApiClient apiClient;
	private final ConfigManager configManager;
	private final Gson gson;
	private final Client client;
	private final ActiveFlipTracker activeFlipTracker;

	/** Callback invoked after sync is complete (for scheduling post-sync tasks) */
	private Runnable onSyncComplete;

	@Inject
	public OfflineSyncService(
		PlayerSession session,
		FlipSmartApiClient apiClient,
		ConfigManager configManager,
		Gson gson,
		Client client,
		ActiveFlipTracker activeFlipTracker)
	{
		this.session = session;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.gson = gson;
		this.client = client;
		this.activeFlipTracker = activeFlipTracker;
	}

	public void setOnSyncComplete(Runnable onSyncComplete)
	{
		this.onSyncComplete = onSyncComplete;
	}

	/**
	 * Restore collected item IDs from persisted config.
	 * These are items that were bought but not yet sold when the player logged out.
	 */
	public void restoreCollectedItems()
	{
		String key = getCollectedItemsKey();
		log.info("Attempting to restore collected items for RSN: {} (key: {})", session.getRsn(), key);

		Set<Integer> persisted = loadPersistedCollectedItems();
		if (!persisted.isEmpty())
		{
			Map<Integer, Integer> quantities = loadPersistedCollectedQuantities();
			session.restoreCollectedItems(persisted, quantities);
			log.info("Restored {} collected items from previous session: {} (with {} quantities)",
				persisted.size(), persisted, quantities.size());
		}
		else
		{
			log.info("No collected items found in config for RSN: {}", session.getRsn());
			session.clearCollectedItems();
		}
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
			log.debug("No tracked offers to persist for {}", session.getRsn());
		}
		else
		{
			try
			{
				Map<Integer, TrackedOffer> offersToSave = session.getOffersForPersistence();
				String json = gson.toJson(offersToSave);
				configManager.setConfiguration(CONFIG_GROUP, offersKey, json);
				log.info("Persisted {} tracked offers for {} (offline sync)", offersToSave.size(), session.getRsn());
			}
			catch (Exception e)
			{
				log.error("Failed to persist offer state for {}: {}", session.getRsn(), e.getMessage());
			}
		}

		// Persist collected item IDs (items bought but not yet sold)
		if (session.getCollectedItemIds().isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, collectedKey);
			configManager.unsetConfiguration(CONFIG_GROUP, quantitiesKey);
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

				log.info("Persisted {} collected item IDs for {} (active flips)", session.getCollectedItemIds().size(), session.getRsn());
			}
			catch (Exception e)
			{
				log.error("Failed to persist collected items for {}: {}", session.getRsn(), e.getMessage());
			}
		}
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

		// Load persisted offers from last session to compare against current state
		Map<Integer, TrackedOffer> persistedOffers = loadPersistedOffers();
		log.debug("Loaded {} persisted offers, comparing with {} current offers",
			persistedOffers.size(), session.getTrackedOffers().size());

		// Always sync current offers
		if (!session.getTrackedOffers().isEmpty())
		{
			syncCurrentOffersWithPersisted(persistedOffers);
		}

		// Handle slots that became empty while offline
		if (!persistedOffers.isEmpty())
		{
			handleEmptyPersistedSlots(persistedOffers);
			configManager.unsetConfiguration(CONFIG_GROUP, getPersistedOffersKey());
		}

		log.info("Offline sync completed for {}", session.getRsn());

		if (onSyncComplete != null)
		{
			onSyncComplete.run();
		}
	}

	/**
	 * Sync current GE offers against persisted state to detect offline fills.
	 */
	private void syncCurrentOffersWithPersisted(Map<Integer, TrackedOffer> persistedOffers)
	{
		for (Map.Entry<Integer, TrackedOffer> entry : session.getTrackedOffers().entrySet())
		{
			int slot = entry.getKey();
			TrackedOffer currentOffer = entry.getValue();
			TrackedOffer persistedOffer = persistedOffers.get(slot);

			if (persistedOffer != null && persistedOffer.getItemId() == currentOffer.getItemId())
			{
				// Restore the original timestamp from persisted offer for timer continuity
				currentOffer.setCreatedAtMillis(persistedOffer.getCreatedAtMillis());

				// Have persisted state - check for offline fills
				recordOfflineFillsIfAny(slot, currentOffer, persistedOffer);
			}
			else if (currentOffer.getTotalQuantity() > 0)
			{
				// No persisted state but there's an active order - record it
				log.debug("Recording GE order for {} {} (slot {}): {}/{} items at {} gp",
					currentOffer.isBuy() ? "BUY" : "SELL",
					currentOffer.getItemName(), slot, currentOffer.getPreviousQuantitySold(),
					currentOffer.getTotalQuantity(), currentOffer.getPrice());

				Integer recommendedSellPrice = currentOffer.isBuy() ? session.getRecommendedPrice(currentOffer.getItemId()) : null;

				apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
					.builder(currentOffer.getItemId(), currentOffer.getItemName(), currentOffer.isBuy(),
						currentOffer.getPreviousQuantitySold(), currentOffer.getPrice())
					.geSlot(slot)
					.recommendedSellPrice(recommendedSellPrice)
					.rsn(getRsnSafe().orElse(null))
					.totalQuantity(currentOffer.getTotalQuantity())
					.build());

				// For buy orders with fills, add to collected tracking
				if (currentOffer.isBuy() && currentOffer.getPreviousQuantitySold() > 0)
				{
					session.addCollectedItem(currentOffer.getItemId(), currentOffer.getPreviousQuantitySold());
				}
			}
		}
	}

	/**
	 * Record offline fills if the current offer has more fills than persisted.
	 */
	private void recordOfflineFillsIfAny(int slot, TrackedOffer currentOffer, TrackedOffer persistedOffer)
	{
		int offlineFills = currentOffer.getPreviousQuantitySold() - persistedOffer.getPreviousQuantitySold();
		if (offlineFills <= 0)
		{
			return;
		}

		log.debug("Detected {} offline fills for {} (slot {}): {} -> {} (order size: {})",
			offlineFills, currentOffer.getItemName(), slot,
			persistedOffer.getPreviousQuantitySold(), currentOffer.getPreviousQuantitySold(),
			currentOffer.getTotalQuantity());

		Integer recommendedSellPrice = currentOffer.isBuy() ? session.getRecommendedPrice(currentOffer.getItemId()) : null;

		apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
			.builder(currentOffer.getItemId(), currentOffer.getItemName(), currentOffer.isBuy(),
				offlineFills, currentOffer.getPrice())
			.geSlot(slot)
			.recommendedSellPrice(recommendedSellPrice)
			.rsn(getRsnSafe().orElse(null))
			.totalQuantity(currentOffer.getTotalQuantity())
			.build());
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

			log.info("Slot {} is now empty (was tracking {} x{}). Checking for offline completions.",
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
	 * Handle an empty slot that was previously a sell order.
	 */
	private void handleEmptySellSlot(TrackedOffer persistedOffer)
	{
		int soldQuantity = persistedOffer.getPreviousQuantitySold();

		if (soldQuantity > 0)
		{
			log.info("Detected {} {} sold offline. Recording SELL transaction.",
				soldQuantity, persistedOffer.getItemName());
			recordOfflineSellTransaction(persistedOffer, soldQuantity);
		}
		else
		{
			log.info("Sell order for {} was cancelled or no items sold.", persistedOffer.getItemName());
		}

		int inventoryCount = getInventoryCountForItem(persistedOffer.getItemId());
		if (inventoryCount == 0)
		{
			log.info("No {} in inventory after offline sell - dismissing active flip", persistedOffer.getItemName());
			activeFlipTracker.dismissFlip(persistedOffer.getItemId());
		}
	}

	/**
	 * Record a SELL transaction for items that sold while offline.
	 */
	private void recordOfflineSellTransaction(TrackedOffer persistedOffer, int soldQuantity)
	{
		String rsn = getRsnSafe().orElse(null);
		if (rsn == null)
		{
			log.warn("Cannot record offline sell - no RSN available");
			return;
		}

		apiClient.recordTransactionAsync(
			persistedOffer.getItemId(),
			persistedOffer.getItemName(),
			"SELL",
			soldQuantity,
			persistedOffer.getPrice(),
			rsn
		);
	}

	/**
	 * Handle an empty slot that was previously a buy order.
	 */
	private void handleEmptyBuySlot(TrackedOffer persistedOffer)
	{
		int inventoryCount = getInventoryCountForItem(persistedOffer.getItemId());
		int trackedFills = persistedOffer.getPreviousQuantitySold();

		if (inventoryCount > 0)
		{
			handleBuyOrderWithInventory(persistedOffer, inventoryCount, trackedFills);
		}
		else if (trackedFills > 0)
		{
			log.info("No {} found in inventory (had {} fills tracked). Items may have been sold/used offline.",
				persistedOffer.getItemName(), trackedFills);
		}
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
		}
		else
		{
			log.info("Adding {} to collected tracking (had {} items filled before going offline)",
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
		log.info("Detected offline completion for {} - tracked {} fills but have {} in inventory. Syncing {} items with backend.",
			persistedOffer.getItemName(), trackedFills, inventoryCount, actualFills);

		String rsn = getRsnSafe().orElse(null);
		if (rsn == null)
		{
			return;
		}

		apiClient.syncActiveFlipAsync(
			persistedOffer.getItemId(),
			persistedOffer.getItemName(),
			actualFills,
			persistedOffer.getTotalQuantity(),
			persistedOffer.getPrice(),
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
			log.info("Loaded {} persisted offers for {}", offers != null ? offers.size() : 0, session.getRsn());
			return offers != null ? offers : new HashMap<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted offers for {}: {}", session.getRsn(), e.getMessage());
			return new HashMap<>();
		}
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
