package com.flipsmart;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PendingTradeOfferPushServiceTest
{
	private static final int ABYSSAL_WHIP = 4151;
	private static final String RSN = "TestPlayer";
	private static final int SLOT = 2;

	private FlipSmartApiClient apiClient;
	private PlayerSession session;
	private PendingTradeOfferPushService service;

	@Before
	public void setUp()
	{
		apiClient = mock(FlipSmartApiClient.class);
		session = mock(PlayerSession.class);
		when(session.getRsnSafe()).thenReturn(Optional.of(RSN));
		when(apiClient.reportPendingTradeOfferAsync(
				anyString(), anyInt(), anyInt(), anyBoolean(), anyInt(), anyInt(), anyInt(), anyString()))
			.thenReturn(CompletableFuture.completedFuture(true));
		service = new PendingTradeOfferPushService(apiClient, session);
	}

	@Test
	public void buyingOfferReportsPendingState()
	{
		GrandExchangeOffer offer = offer(ABYSSAL_WHIP, GrandExchangeOfferState.BUYING, 3_000_000, 1, 0);
		service.reportOfferChanged(SLOT, offer);
		verify(apiClient, times(1)).reportPendingTradeOfferAsync(
			eq(RSN), eq(SLOT), eq(ABYSSAL_WHIP), eq(true), eq(3_000_000), eq(1), eq(0), eq("pending"));
	}

	@Test
	public void boughtOfferReportsExecutedState()
	{
		GrandExchangeOffer offer = offer(ABYSSAL_WHIP, GrandExchangeOfferState.BOUGHT, 3_000_000, 1, 1);
		service.reportOfferChanged(SLOT, offer);
		verify(apiClient, times(1)).reportPendingTradeOfferAsync(
			eq(RSN), eq(SLOT), eq(ABYSSAL_WHIP), eq(true), eq(3_000_000), eq(1), eq(1), eq("executed"));
	}

	@Test
	public void cancelledBuyReportsCancelledState()
	{
		GrandExchangeOffer offer = offer(ABYSSAL_WHIP, GrandExchangeOfferState.CANCELLED_BUY, 3_000_000, 1, 0);
		service.reportOfferChanged(SLOT, offer);
		verify(apiClient, times(1)).reportPendingTradeOfferAsync(
			eq(RSN), eq(SLOT), eq(ABYSSAL_WHIP), eq(true), eq(3_000_000), eq(1), eq(0), eq("cancelled"));
	}

	@Test
	public void emptyOfferReportsEmptyStateWithZeroItemId()
	{
		GrandExchangeOffer offer = offer(ABYSSAL_WHIP, GrandExchangeOfferState.EMPTY, 0, 0, 0);
		service.reportOfferChanged(SLOT, offer);
		verify(apiClient, times(1)).reportPendingTradeOfferAsync(
			eq(RSN), eq(SLOT), eq(0), anyBoolean(), anyInt(), anyInt(), anyInt(), eq("empty"));
	}

	@Test
	public void sellingOfferIsNotBuySide()
	{
		GrandExchangeOffer offer = offer(ABYSSAL_WHIP, GrandExchangeOfferState.SELLING, 3_000_000, 1, 0);
		service.reportOfferChanged(SLOT, offer);
		verify(apiClient, times(1)).reportPendingTradeOfferAsync(
			eq(RSN), eq(SLOT), eq(ABYSSAL_WHIP), eq(false), eq(3_000_000), eq(1), eq(0), eq("pending"));
	}

	@Test
	public void noReportWhenRsnUnavailable()
	{
		when(session.getRsnSafe()).thenReturn(Optional.empty());
		GrandExchangeOffer offer = offer(ABYSSAL_WHIP, GrandExchangeOfferState.BUYING, 3_000_000, 1, 0);
		service.reportOfferChanged(SLOT, offer);
		verify(apiClient, never()).reportPendingTradeOfferAsync(
			any(), anyInt(), anyInt(), anyBoolean(), anyInt(), anyInt(), anyInt(), any());
	}

	@Test
	public void noReportWhenOfferIsNull()
	{
		service.reportOfferChanged(SLOT, null);
		verify(apiClient, never()).reportPendingTradeOfferAsync(
			any(), anyInt(), anyInt(), anyBoolean(), anyInt(), anyInt(), anyInt(), any());
	}

	private static GrandExchangeOffer offer(int itemId, GrandExchangeOfferState state, int price, int totalQuantity, int quantitySold)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getItemId()).thenReturn(itemId);
		when(o.getState()).thenReturn(state);
		when(o.getPrice()).thenReturn(price);
		when(o.getTotalQuantity()).thenReturn(totalQuantity);
		when(o.getQuantitySold()).thenReturn(quantitySold);
		return o;
	}
}
