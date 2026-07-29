package com.flipsmart;

import com.flipsmart.trading.OfferStore;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ActiveFlipTrackerTest
{
	private FlipSmartApiClient apiClient;
	private Client client;
	private ActiveFlipTracker tracker;

	@Before
	public void setUp()
	{
		PlayerSession session = mock(PlayerSession.class);
		apiClient = mock(FlipSmartApiClient.class);
		client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		ItemManager itemManager = mock(ItemManager.class);
		OfferStore offerStore = new OfferStore();

		when(session.getRsn()).thenReturn("Zezima");
		when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
		when(apiClient.dismissActiveFlipAsync(anyInt(), any()))
			.thenReturn(CompletableFuture.completedFuture(true));
		// Inventory empty (no container) so getInventoryCountForItem returns 0.
		when(client.getItemContainer(anyInt())).thenReturn(null);

		tracker = new ActiveFlipTracker(session, apiClient, client, clientThread, itemManager, offerStore);
	}

	private GrandExchangeOffer liveSell(int itemId)
	{
		GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(GrandExchangeOfferState.SELLING);
		when(offer.getItemId()).thenReturn(itemId);
		return offer;
	}

	@Test
	public void markItemSold_itemStillLiveInGeSlot_doesNotDismiss()
	{
		GrandExchangeOffer offer = liveSell(1234);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[]{ offer });

		tracker.markItemSold(1234);

		verify(apiClient, never()).dismissActiveFlipAsync(anyInt(), any());
	}

	@Test
	public void markItemSold_itemGoneEverywhere_dismisses()
	{
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[]{});

		tracker.markItemSold(1234);

		verify(apiClient, times(1)).dismissActiveFlipAsync(anyInt(), any());
	}
}
