package com.flipsmart;
import com.flipsmart.api.dto.SellPriceCheckRequest;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SellPriceCheckBodyTest
{
	@Test
	public void includesAllFieldsForTwelveHour()
	{
		SellPriceCheckRequest req = SellPriceCheckRequest.builder()
			.itemId(561)
			.originalSellPrice(200)
			.currentMarketHigh(190)
			.dailyVolume(5_000_000)
			.timeframe("12h")
			.style("balanced")
			.build();

		JsonObject body = FlipSmartApiClient.buildSellPriceCheckBody(req);

		assertEquals(561, body.get("item_id").getAsInt());
		assertEquals(200, body.get("original_sell_price").getAsInt());
		assertEquals(190, body.get("current_market_high").getAsInt());
		assertEquals(5_000_000, body.get("daily_volume").getAsInt());
		assertEquals("12h", body.get("timeframe").getAsString());
		assertEquals("balanced", body.get("style").getAsString());
	}

	@Test
	public void omitsNullTimeframeAndStyle()
	{
		SellPriceCheckRequest req = SellPriceCheckRequest.builder()
			.itemId(1).originalSellPrice(5).currentMarketHigh(4).dailyVolume(100)
			.build();
		JsonObject body = FlipSmartApiClient.buildSellPriceCheckBody(req);
		assertFalse(body.has("timeframe"));
		assertFalse(body.has("style"));
		assertTrue(body.has("item_id"));
	}
}
