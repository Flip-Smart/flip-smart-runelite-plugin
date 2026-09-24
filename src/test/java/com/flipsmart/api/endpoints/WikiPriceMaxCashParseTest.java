package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.Dtos.WikiPrice;
import com.flipsmart.api.endpoints.Endpoints.MarketDataEndpoints;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WikiPriceMaxCashParseTest
{
	@Test
	public void wikiPricesAboveInt32ParseInsteadOfDroppingTheItem()
	{
		ApiHttpTransport transport = mock(ApiHttpTransport.class);
		when(transport.getGson()).thenReturn(new Gson());
		MarketDataEndpoints endpoints = new MarketDataEndpoints(transport);

		endpoints.parseWikiPriceResponse(
			"{\"data\":{\"4151\":{\"high\":3100000000,\"low\":3000000000},\"995\":{\"high\":1,\"low\":null}}}");

		WikiPrice p = endpoints.getWikiPrice(4151);
		assertEquals(3_100_000_000L, p.instaBuy);
		assertEquals(3_000_000_000L, p.instaSell);
		assertEquals(3_050_000_000L, p.midPrice());
		assertEquals(1L, endpoints.getWikiPrice(995).instaBuy);
	}
}
