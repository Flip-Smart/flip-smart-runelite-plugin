package com.flipsmart;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.api.dto.OfferAdviceRequest;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ActiveOfferSnapshotTest
{
	private static OfferRecord sellOffer(int itemId, int totalQty, int price, int filled, long now)
	{
		OfferRecord offer = OfferRecord.newOffer(1, 0, itemId, "Item", false, totalQty, price, now);
		return filled > 0 ? offer.withFill(filled, 0L, OfferState.PARTIAL_FILL, now) : offer;
	}

	private static OfferRecord buyOffer(int itemId, int totalQty, int price, long now)
	{
		return OfferRecord.newOffer(1, 0, itemId, "Item", true, totalQty, price, now);
	}

	@Test
	public void buildsSellSnapshotWithMarketAndBuyPrice()
	{
		OfferRecord offer = sellOffer(12345, 10, 1000, 2, System.currentTimeMillis());
		WikiPrice market = new WikiPrice(1080, 1020);

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
		OfferRecord offer = buyOffer(1, 1, 5, System.currentTimeMillis());
		OfferAdviceRequest req = ActiveOfferAdvisorService.buildSnapshot(offer, null, null, 600000);
		assertEquals("high_vol", req.getPool());
		assertEquals("buy", req.getSide());
		assertNull(req.getCurrentMarketHigh());
		assertNull(req.getUserAvgBuyPrice());
	}

	@Test
	public void buyOfferOmitsUserAvgBuyPriceEvenIfProvided()
	{
		OfferRecord offer = buyOffer(1, 1, 5, System.currentTimeMillis());
		OfferAdviceRequest req = ActiveOfferAdvisorService.buildSnapshot(offer, null, 990, 100000);
		assertEquals("mid_vol", req.getPool());
		assertNull(req.getUserAvgBuyPrice());
	}
}
