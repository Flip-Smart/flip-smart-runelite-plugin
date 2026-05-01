package com.flipsmart;

import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OfflineSyncService#pruneStaleCollectedItems()} — guards against
 * persisted collectedItems entries surviving past the actual buy/sell that put them
 * there (issue #451: stuck "sell X" prompts for items the player no longer holds).
 */
public class OfflineSyncServiceTest
{
	private PlayerSession session;
	private ActiveFlipTracker activeFlipTracker;
	private OfflineSyncService service;

	@Before
	public void setUp()
	{
		session = new PlayerSession();
		session.setRsn("TestPlayer");

		FlipSmartApiClient apiClient = mock(FlipSmartApiClient.class);
		ConfigManager configManager = mock(ConfigManager.class);
		Gson gson = new Gson();
		Client client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		activeFlipTracker = mock(ActiveFlipTracker.class);

		service = new OfflineSyncService(
			session, apiClient, configManager, gson, client, clientThread, activeFlipTracker);
	}

	@Test
	public void pruneStaleCollectedItems_removesItemAbsentFromInventoryAndGE()
	{
		int frostDragonBones = 18830;
		session.addCollectedItem(frostDragonBones, 100);
		when(activeFlipTracker.getInventoryCountForItem(frostDragonBones)).thenReturn(0);

		int removed = service.pruneStaleCollectedItems();

		assertEquals(
			"Item with no inventory and no GE offer must be pruned — "
				+ "this is the stuck-sell-prompt failure mode in issue #451.",
			1, removed);
		assertFalse(session.getCollectedItemIds().contains(frostDragonBones));
	}

	@Test
	public void pruneStaleCollectedItems_keepsItemPresentInInventory()
	{
		int itemId = 4151;  // abyssal whip
		session.addCollectedItem(itemId, 1);
		when(activeFlipTracker.getInventoryCountForItem(itemId)).thenReturn(1);

		int removed = service.pruneStaleCollectedItems();

		assertEquals(0, removed);
		assertTrue(session.getCollectedItemIds().contains(itemId));
	}

	@Test
	public void pruneStaleCollectedItems_keepsItemWithActiveBuyOffer()
	{
		int itemId = 1515;  // yew log
		session.addCollectedItem(itemId, 0);
		// In-flight buy offer in slot 3 — still BUYING, items not yet collectable.
		session.putTrackedOffer(3, new TrackedOffer(
			itemId, "Yew logs", true, 100, 250, 0));
		when(activeFlipTracker.getInventoryCountForItem(itemId)).thenReturn(0);

		int removed = service.pruneStaleCollectedItems();

		assertEquals(
			"In-flight buy offer is evidence the item is still being acquired; do not prune.",
			0, removed);
		assertTrue(session.getCollectedItemIds().contains(itemId));
	}

	@Test
	public void pruneStaleCollectedItems_keepsItemWithActiveSellOffer()
	{
		int itemId = 1515;
		session.addCollectedItem(itemId, 0);
		// Active sell slot — pruning would lose the entry needed to detect sell completion.
		session.putTrackedOffer(2, new TrackedOffer(
			itemId, "Yew logs", false, 100, 260, 0));
		when(activeFlipTracker.getInventoryCountForItem(itemId)).thenReturn(0);

		int removed = service.pruneStaleCollectedItems();

		assertEquals(0, removed);
		assertTrue(session.getCollectedItemIds().contains(itemId));
	}

	@Test
	public void pruneStaleCollectedItems_isConservativeWhenInventoryThrows()
	{
		int itemId = 18830;
		session.addCollectedItem(itemId, 100);
		// Off-thread access to ItemContainer raises AssertionError in RuneLite.
		when(activeFlipTracker.getInventoryCountForItem(itemId))
			.thenThrow(new AssertionError("not on client thread"));

		int removed = service.pruneStaleCollectedItems();

		assertEquals(
			"When inventory state is unavailable we must keep the entry — pruning a "
				+ "legitimate sell candidate is worse than letting one stale entry survive "
				+ "to the next sync cycle.",
			0, removed);
		assertTrue(session.getCollectedItemIds().contains(itemId));
	}

	@Test
	public void pruneStaleCollectedItems_handlesEmptySetCheaply()
	{
		assertEquals(0, service.pruneStaleCollectedItems());
	}

	@Test
	public void pruneStaleCollectedItems_prunesStaleAndKeepsValidInSamePass()
	{
		int stale = 18830;
		int valid = 4151;
		session.addCollectedItem(stale, 100);
		session.addCollectedItem(valid, 1);
		when(activeFlipTracker.getInventoryCountForItem(stale)).thenReturn(0);
		when(activeFlipTracker.getInventoryCountForItem(valid)).thenReturn(1);

		int removed = service.pruneStaleCollectedItems();

		assertEquals(1, removed);
		assertFalse(session.getCollectedItemIds().contains(stale));
		assertTrue(session.getCollectedItemIds().contains(valid));
	}
}
