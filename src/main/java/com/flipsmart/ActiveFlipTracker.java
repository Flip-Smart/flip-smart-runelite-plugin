package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tracks the lifecycle of active flips: mark sold, auto-close,
 * stale flip cleanup, and inventory quantity validation.
 */
@Slf4j
@Singleton
public class ActiveFlipTracker
{
	private static final int INVENTORY_CONTAINER_ID = 93;

	private final PlayerSession session;
	private final FlipSmartApiClient apiClient;
	private final Client client;
	private final ClientThread clientThread;

	private Runnable onPanelRefreshNeeded;
	private Runnable onActiveFlipsRefreshNeeded;

	@Inject
	public ActiveFlipTracker(
		PlayerSession session,
		FlipSmartApiClient apiClient,
		Client client,
		ClientThread clientThread)
	{
		this.session = session;
		this.apiClient = apiClient;
		this.client = client;
		this.clientThread = clientThread;
	}

	public void setOnPanelRefreshNeeded(Runnable callback)
	{
		this.onPanelRefreshNeeded = callback;
	}

	public void setOnActiveFlipsRefreshNeeded(Runnable callback)
	{
		this.onActiveFlipsRefreshNeeded = callback;
	}

	/**
	 * Mark an item as sold - removes it from the collected tracking.
	 * Called when a sell transaction is recorded.
	 * Also checks if inventory is empty AND no active GE sell slot for this item,
	 * then auto-closes the active flip.
	 */
	public void markItemSold(int itemId)
	{
		if (session.removeCollectedItem(itemId))
		{
			log.debug("Removed item {} from collected tracking (sold)", itemId);
		}

		if (session.hasActiveSellSlotForItem(itemId))
		{
			log.debug("Item {} still has active sell slot, keeping flip open", itemId);
			return;
		}

		tryAutoCloseFlip(itemId);
	}

	/**
	 * Dismiss an active flip on the backend.
	 * Used by other services when they determine a flip is complete.
	 */
	public void dismissFlip(int itemId)
	{
		apiClient.dismissActiveFlipAsync(itemId, getRsnSafe().orElse(null)).thenAccept(success ->
		{
			if (Boolean.TRUE.equals(success))
			{
				log.info("Dismissed active flip for item {}", itemId);
				if (onPanelRefreshNeeded != null)
				{
					javax.swing.SwingUtilities.invokeLater(onPanelRefreshNeeded);
				}
			}
		});
	}

	/**
	 * Attempt to auto-close a flip if inventory is empty.
	 */
	private void tryAutoCloseFlip(int itemId)
	{
		int inventoryCount = getInventoryCountForItem(itemId);
		if (inventoryCount > 0)
		{
			return;
		}

		log.info("Inventory empty and no active sell slot for item {}, auto-closing active flip", itemId);
		apiClient.dismissActiveFlipAsync(itemId, getRsnSafe().orElse(null)).thenAccept(success ->
		{
			if (Boolean.TRUE.equals(success))
			{
				log.info("Successfully auto-closed active flip for item {} (no items remaining)", itemId);
				if (onPanelRefreshNeeded != null)
				{
					javax.swing.SwingUtilities.invokeLater(onPanelRefreshNeeded);
				}
			}
		});
	}

	/**
	 * Get the count of a specific item in the player's inventory.
	 */
	public int getInventoryCountForItem(int itemId)
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

	/**
	 * Clean up stale active flips on the backend.
	 * Sends the list of item IDs that are "truly active" (in GE slots or inventory)
	 * to the API, which will mark all other active flips as closed.
	 */
	public void cleanupStaleActiveFlips()
	{
		clientThread.invokeLater(this::executeStaleFlipCleanup);
	}

	private void executeStaleFlipCleanup()
	{
		Set<Integer> activeItemIds = collectAllActiveItemIds();

		log.info("Cleaning up stale flips - {} item IDs are truly active", activeItemIds.size());

		apiClient.cleanupStaleFlipsAsync(activeItemIds, getRsnSafe().orElse(null))
			.thenAccept(this::handleCleanupResult);
	}

	private Set<Integer> collectAllActiveItemIds()
	{
		return session.getActiveFlipItemIds();
	}

	private void handleCleanupResult(Boolean success)
	{
		if (Boolean.TRUE.equals(success))
		{
			log.info("Stale flip cleanup completed successfully");
			if (onActiveFlipsRefreshNeeded != null)
			{
				javax.swing.SwingUtilities.invokeLater(onActiveFlipsRefreshNeeded);
			}
		}
	}

	/**
	 * Validate inventory quantities against active flips and sync down if needed.
	 * Must be called on client thread.
	 */
	public void validateInventoryQuantities()
	{
		String rsn = getRsnSafe().orElse(null);
		if (rsn == null)
		{
			return;
		}

		Map<Integer, Integer> totalItemCounts = getTotalItemCounts();

		apiClient.getActiveFlipsAsync(rsn).thenAccept(response -> {
			if (response == null || response.getActiveFlips() == null)
			{
				return;
			}

			for (ActiveFlip flip : response.getActiveFlips())
			{
				validateAndSyncFlipQuantity(flip, totalItemCounts, rsn);
			}
		}).exceptionally(e -> {
			log.debug("Failed to validate inventory quantities: {}", e.getMessage());
			return null;
		});
	}

	private void validateAndSyncFlipQuantity(ActiveFlip flip, Map<Integer, Integer> totalItemCounts, String rsn)
	{
		int itemId = flip.getItemId();
		int activeFlipQty = flip.getTotalQuantity();
		int actualQty = totalItemCounts.getOrDefault(itemId, 0);

		if (isItemInActiveBuySlot(itemId))
		{
			log.debug("Skipping validation for {} - still in buy slot", flip.getItemName());
			return;
		}

		if (actualQty == activeFlipQty)
		{
			return;
		}

		if (actualQty == 0)
		{
			log.info("No items found for {} during validation - dismissing active flip", flip.getItemName());
			dismissFlip(itemId);
			return;
		}

		int difference = Math.abs(activeFlipQty - actualQty);
		boolean significantDifference = difference >= 10 || (activeFlipQty > 0 && difference > activeFlipQty * 0.1);

		if (!significantDifference)
		{
			log.debug("Skipping validation for {} - difference of {} is not significant", flip.getItemName(), difference);
			return;
		}

		String direction = actualQty > activeFlipQty ? "up" : "down";
		log.info("Inventory quantity mismatch for {} - active flip shows {} but have {} (inv + sell slots). Syncing {}.",
			flip.getItemName(), activeFlipQty, actualQty, direction);

		int orderQty = flip.getOrderQuantity() > 0 ? flip.getOrderQuantity() : Math.max(activeFlipQty, actualQty);
		apiClient.syncActiveFlipAsync(
			itemId,
			flip.getItemName(),
			actualQty,
			orderQty,
			flip.getAverageBuyPrice(),
			rsn
		);
	}

	private boolean isItemInActiveBuySlot(int itemId)
	{
		for (TrackedOffer offer : session.getTrackedOffers().values())
		{
			if (offer.getItemId() == itemId && offer.isBuy())
			{
				return true;
			}
		}
		return false;
	}

	private Map<Integer, Integer> getTotalItemCounts()
	{
		Map<Integer, Integer> counts = new HashMap<>();

		ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item.getId() > 0)
				{
					counts.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}

		for (TrackedOffer offer : session.getTrackedOffers().values())
		{
			if (!offer.isBuy() && offer.getItemId() > 0)
			{
				int remainingInSlot = offer.getTotalQuantity() - offer.getPreviousQuantitySold();
				if (remainingInSlot > 0)
				{
					counts.merge(offer.getItemId(), remainingInSlot, Integer::sum);
				}
			}
		}

		return counts;
	}

	private Optional<String> getRsnSafe()
	{
		return session.getRsnSafe();
	}
}
