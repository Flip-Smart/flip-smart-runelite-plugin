package com.flipsmart;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ActiveOfferSnapshotTest
{
	@Test
	public void buildsSellSnapshotWithMarketAndBuyPrice()
	{
		TrackedOffer offer = new TrackedOffer(12345, "Item", false, 10, 1000, 2);
		FlipSmartApiClient.WikiPrice market = new FlipSmartApiClient.WikiPrice(1080, 1020);

		OfferAdviceRequest req = ActiveOfferAdvisorService.buildSnapshot(offer, market, 990, 600000);

		assertEquals(12345, req.getItemId());
		assertEquals("high_vol", req.getPool());
		assertEquals("sell", req.getSide());
		assertEquals("initial", req.getStage());
		assertEquals(1000, req.getListedPrice());
		assertEquals(10, req.getListedQuantity());
		assertEquals(2, req.getFilledQuantity());
		assertEquals(Integer.valueOf(1080), req.getCurrentMarketHigh());
		assertEquals(Integer.valueOf(1020), req.getCurrentMarketLow());
		assertEquals(Integer.valueOf(990), req.getUserAvgBuyPrice());
	}

	@Test
	public void classifiesHighVolFromDailyVolume()
	{
		TrackedOffer offer = new TrackedOffer(1, "Item", true, 1, 5, 0);
		OfferAdviceRequest req = ActiveOfferAdvisorService.buildSnapshot(offer, null, null, 600000);
		assertEquals("high_vol", req.getPool());
		assertEquals("buy", req.getSide());
		assertNull(req.getCurrentMarketHigh());
		assertNull(req.getUserAvgBuyPrice());
	}

	@Test
	public void buyOfferOmitsUserAvgBuyPriceEvenIfProvided()
	{
		TrackedOffer offer = new TrackedOffer(1, "Item", true, 1, 5, 0);
		OfferAdviceRequest req = ActiveOfferAdvisorService.buildSnapshot(offer, null, 990, 100000);
		assertEquals("mid_vol", req.getPool());
		assertNull(req.getUserAvgBuyPrice());
	}
}
