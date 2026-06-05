package com.flipsmart;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TradeStationSlotPushServiceTest
{
	private static final int ABYSSAL_WHIP = 4151;
	private static final int DRAGON_LONGSWORD = 1305;
	private static final String RSN = "TestPlayer";

	private Client client;
	private FlipSmartApiClient apiClient;
	private PlayerSession session;
	private TradeStationSlotPushService service;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		apiClient = mock(FlipSmartApiClient.class);
		session = mock(PlayerSession.class);
		when(session.getRsnSafe()).thenReturn(Optional.of(RSN));
		when(apiClient.pushTradeStationSlotsAsync(any(), any()))
			.thenReturn(CompletableFuture.completedFuture(true));
		service = new TradeStationSlotPushService(client, apiClient, session);
	}

	@Test
	public void readCurrentSlotIdsDedupesAndSkipsEmpty()
	{
		GrandExchangeOffer o1 = offer(ABYSSAL_WHIP, GrandExchangeOfferState.BUYING);
		GrandExchangeOffer o2 = offer(0, GrandExchangeOfferState.EMPTY);
		GrandExchangeOffer o3 = offer(ABYSSAL_WHIP, GrandExchangeOfferState.SELLING);
		GrandExchangeOffer o4 = offer(DRAGON_LONGSWORD, GrandExchangeOfferState.BOUGHT);
		GrandExchangeOffer[] offers = { o1, o2, o3, o4, null };
		when(client.getGrandExchangeOffers()).thenReturn(offers);

		List<Integer> ids = service.readCurrentSlotIds();
		assertEquals(2, ids.size());
		assertEquals(Integer.valueOf(ABYSSAL_WHIP), ids.get(0));
		assertEquals(Integer.valueOf(DRAGON_LONGSWORD), ids.get(1));
	}

	@Test
	public void pushNowSendsItemsToApiClient() throws Exception
	{
		service.pushNow(List.of(ABYSSAL_WHIP, DRAGON_LONGSWORD));
		// Single-threaded executor — block until queued task finishes.
		Thread.sleep(50);
		verify(apiClient, times(1)).pushTradeStationSlotsAsync(eq(RSN),
			eq(List.of(ABYSSAL_WHIP, DRAGON_LONGSWORD)));
	}

	@Test
	public void duplicateSnapshotsAreSkipped() throws Exception
	{
		service.pushNow(List.of(ABYSSAL_WHIP));
		Thread.sleep(50);
		service.pushNow(List.of(ABYSSAL_WHIP));
		Thread.sleep(50);
		// Second push has the same snapshot so the service should short-circuit.
		verify(apiClient, times(1)).pushTradeStationSlotsAsync(any(), any());
	}

	@Test
	public void noPushWhenRsnUnavailable() throws Exception
	{
		when(session.getRsnSafe()).thenReturn(Optional.empty());
		service.pushNow(List.of(ABYSSAL_WHIP));
		Thread.sleep(50);
		verify(apiClient, never()).pushTradeStationSlotsAsync(any(), any());
	}

	@Test
	public void readReturnsEmptyListWhenClientHasNoOffers()
	{
		when(client.getGrandExchangeOffers()).thenReturn(null);
		assertTrue(service.readCurrentSlotIds().isEmpty());
	}

	private static GrandExchangeOffer offer(int itemId, GrandExchangeOfferState state)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getItemId()).thenReturn(itemId);
		when(o.getState()).thenReturn(state);
		return o;
	}
}
