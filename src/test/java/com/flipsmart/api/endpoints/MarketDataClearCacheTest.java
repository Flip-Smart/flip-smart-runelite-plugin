package com.flipsmart.api.endpoints;

import com.flipsmart.FlipSmartApiClient;
import com.flipsmart.FlipSmartConfig;
import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.endpoints.Endpoints.MarketDataEndpoints;
import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;
import java.util.function.Function;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An economy switch must not leave the previous economy's prices behind. */
public class MarketDataClearCacheTest
{
	private static final String PRICES = "{\"data\":{\"4151\":{\"high\":2000000,\"low\":1900000}}}";

	@Test
	public void clearCacheDropsWikiPricesAndAllowsAnImmediateRefetch()
	{
		ApiHttpTransport transport = mock(ApiHttpTransport.class);
		when(transport.getGson()).thenReturn(new Gson());
		when(transport.getApiUrl()).thenReturn("http://localhost");
		when(transport.executeAuthenticatedAsync(any(), any(), any())).thenReturn(new CompletableFuture<>());
		MarketDataEndpoints market = new MarketDataEndpoints(transport);
		market.parseWikiPriceResponse(PRICES);
		market.fetchWikiPrices();
		assertNotNull(market.getLastKnownWikiPrice(4151));

		market.clearCache();

		assertNull(market.getLastKnownWikiPrice(4151));
		market.fetchWikiPrices();
		verify(transport, times(2)).executeAuthenticatedAsync(any(), any(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void wikiPricesStillInFlightFromThePreviousEconomyAreDiscarded()
	{
		ApiHttpTransport transport = mock(ApiHttpTransport.class);
		when(transport.getGson()).thenReturn(new Gson());
		when(transport.getApiUrl()).thenReturn("http://localhost");
		when(transport.executeAuthenticatedAsync(any(), any(), any())).thenReturn(new CompletableFuture<>());
		MarketDataEndpoints market = new MarketDataEndpoints(transport);
		market.fetchWikiPrices();
		ArgumentCaptor<Function<String, Object>> handler = ArgumentCaptor.forClass(Function.class);
		verify(transport).executeAuthenticatedAsync(any(), handler.capture(), any());

		market.clearCache();
		handler.getValue().apply(PRICES);

		assertNull(market.getLastKnownWikiPrice(4151));
	}


	@Test
	public void apiClientClearCacheAlsoDropsMarketData() throws Exception
	{
		FlipSmartApiClient apiClient = new FlipSmartApiClient(mock(FlipSmartConfig.class), new Gson(), new OkHttpClient());
		Field f = FlipSmartApiClient.class.getDeclaredField("marketData");
		f.setAccessible(true);
		MarketDataEndpoints market = (MarketDataEndpoints) f.get(apiClient);
		market.parseWikiPriceResponse(PRICES);

		apiClient.clearCache();

		assertNull(market.getLastKnownWikiPrice(4151));
	}
}
