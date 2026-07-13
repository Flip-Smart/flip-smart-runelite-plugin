package com.flipsmart;
import com.flipsmart.api.dto.OfferAdviceRequest;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OfferActionBodyTest
{
	@Test
	public void includesRequiredFieldsAndOmitsNulls()
	{
		OfferAdviceRequest req = OfferAdviceRequest.builder()
			.itemId(12345)
			.pool("high_vol")
			.side("sell")
			.stage("initial")
			.listedAtMillis(1781035200000L)
			.listedPrice(1000)
			.listedQuantity(10)
			.filledQuantity(0)
			.currentMarketHigh(1080)
			.currentMarketLow(1020)
			.userAvgBuyPrice(990)
			.build();

		JsonObject body = FlipSmartApiClient.buildOfferActionBody(req);

		assertEquals(12345, body.get("item_id").getAsInt());
		assertEquals("high_vol", body.get("pool").getAsString());
		assertEquals("sell", body.get("side").getAsString());
		assertEquals("initial", body.get("stage").getAsString());
		assertEquals("2026-06-09T20:00:00Z", body.get("listed_at").getAsString());
		assertEquals(1000, body.get("listed_price").getAsInt());
		assertEquals(10, body.get("listed_quantity").getAsInt());
		assertEquals(0, body.get("filled_quantity").getAsInt());
		assertEquals(1080, body.get("current_market_high").getAsInt());
		assertEquals(1020, body.get("current_market_low").getAsInt());
		assertEquals(990, body.get("user_avg_buy_price").getAsInt());
		assertFalse("null last_fill omitted", body.has("last_fill_at"));
	}

	private OfferAdviceRequest.OfferAdviceRequestBuilder createBaseRequestBuilder()
	{
		return OfferAdviceRequest.builder()
			.itemId(1).pool("mid_vol").side("buy").stage("initial")
			.listedAtMillis(1781035200000L).listedPrice(5).listedQuantity(1).filledQuantity(0);
	}

	@Test
	public void omitsNullMarketAndBuyPrice()
	{
		OfferAdviceRequest req = createBaseRequestBuilder().build();
		JsonObject body = FlipSmartApiClient.buildOfferActionBody(req);
		assertFalse(body.has("current_market_high"));
		assertFalse(body.has("current_market_low"));
		assertFalse(body.has("user_avg_buy_price"));
		assertTrue(body.has("item_id"));
	}

	@Test
	public void includesCourierStateFields()
	{
		OfferAdviceRequest req = OfferAdviceRequest.builder()
			.itemId(4151).pool("mid_vol").side("buy").stage("initial")
			.listedAtMillis(1781035200000L).listedPrice(100000).listedQuantity(10).filledQuantity(5)
			.originalMargin(10000).previousPositionMargin(9000)
			.consecutiveMarginDecreases(1).cumulativeMarginReductionPct(0.1)
			.build();
		JsonObject body = FlipSmartApiClient.buildOfferActionBody(req);
		assertEquals(10000, body.get("original_margin").getAsInt());
		assertEquals(9000, body.get("previous_position_margin").getAsInt());
		assertEquals(1, body.get("consecutive_margin_decreases").getAsInt());
		assertEquals(0.1, body.get("cumulative_margin_reduction_pct").getAsDouble(), 1e-9);
	}

	@Test
	public void omitsNullCourierMarginsButKeepsCounters()
	{
		OfferAdviceRequest req = createBaseRequestBuilder().build();
		JsonObject body = FlipSmartApiClient.buildOfferActionBody(req);
		assertFalse(body.has("original_margin"));
		assertFalse(body.has("previous_position_margin"));
		assertEquals(0, body.get("consecutive_margin_decreases").getAsInt());
		assertEquals(0.0, body.get("cumulative_margin_reduction_pct").getAsDouble(), 1e-9);
	}
}
