package com.flipsmart;

import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
		activeFlipTracker = mock(ActiveFlipTracker.class);
		service = new OfflineSyncService(
			session,
			mock(FlipSmartApiClient.class),
			mock(ConfigManager.class),
			new Gson(),
			mock(Client.class),
			mock(ClientThread.class),
			activeFlipTracker);
	}

	@Test
	public void prune_removesItemAbsentFromInventoryAndGE()
	{
		int frostDragonBones = 18830;
		session.addCollectedItem(frostDragonBones, 100);
		when(activeFlipTracker.getInventoryCountForItem(frostDragonBones)).thenReturn(0);

		assertEquals(1, service.pruneStaleCollectedItems());
		assertFalse(session.getCollectedItemIds().contains(frostDragonBones));
	}

	@Test
	public void prune_keepsItemPresentInInventory()
	{
		session.addCollectedItem(4151, 1);
		when(activeFlipTracker.getInventoryCountForItem(4151)).thenReturn(1);

		assertEquals(0, service.pruneStaleCollectedItems());
		assertTrue(session.getCollectedItemIds().contains(4151));
	}

	@Test
	public void prune_keepsItemWithActiveBuyOffer()
	{
		session.addCollectedItem(1515, 0);
		session.putTrackedOffer(3, new TrackedOffer(1515, "Yew logs", true, 100, 250, 0));
		when(activeFlipTracker.getInventoryCountForItem(1515)).thenReturn(0);

		assertEquals(0, service.pruneStaleCollectedItems());
		assertTrue(session.getCollectedItemIds().contains(1515));
	}

	@Test
	public void prune_keepsItemWithActiveSellOffer()
	{
		session.addCollectedItem(1515, 0);
		session.putTrackedOffer(2, new TrackedOffer(1515, "Yew logs", false, 100, 260, 0));
		when(activeFlipTracker.getInventoryCountForItem(1515)).thenReturn(0);

		assertEquals(0, service.pruneStaleCollectedItems());
		assertTrue(session.getCollectedItemIds().contains(1515));
	}

	@Test
	public void prune_keepsItemWhenInventoryCheckThrows()
	{
		// Off-thread ItemContainer access raises AssertionError; stay conservative.
		session.addCollectedItem(18830, 100);
		when(activeFlipTracker.getInventoryCountForItem(18830))
			.thenThrow(new AssertionError("not on client thread"));

		assertEquals(0, service.pruneStaleCollectedItems());
		assertTrue(session.getCollectedItemIds().contains(18830));
	}

	@Test
	public void prune_emptySetReturnsZero()
	{
		assertEquals(0, service.pruneStaleCollectedItems());
	}

	@Test
	public void prune_mixedSet_dropsStaleKeepsValid()
	{
		session.addCollectedItem(18830, 100);
		session.addCollectedItem(4151, 1);
		when(activeFlipTracker.getInventoryCountForItem(18830)).thenReturn(0);
		when(activeFlipTracker.getInventoryCountForItem(4151)).thenReturn(1);

		assertEquals(1, service.pruneStaleCollectedItems());
		assertFalse(session.getCollectedItemIds().contains(18830));
		assertTrue(session.getCollectedItemIds().contains(4151));
	}
}
