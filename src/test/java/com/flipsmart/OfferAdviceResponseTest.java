package com.flipsmart;
import com.flipsmart.domain.offer.OfferAction;
import com.flipsmart.api.dto.OfferAdviceResponse;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OfferAdviceResponseTest
{
	private final Gson gson = new Gson();

	@Test
	public void deserializesMoveDownResponse()
	{
		String json = "{\"action\":\"move_price_down\",\"reason\":\"Move down\","
			+ "\"new_price\":1050,\"net_profit_estimate\":12500}";
		OfferAdviceResponse r = gson.fromJson(json, OfferAdviceResponse.class);
		assertEquals("move_price_down", r.getAction());
		assertEquals(OfferAction.MOVE_PRICE_DOWN, r.getActionEnum());
		assertEquals("Move down", r.getReason());
		assertEquals(Integer.valueOf(1050), r.getNewPrice());
		assertEquals(Integer.valueOf(12500), r.getNetProfitEstimate());
	}

	@Test
	public void deserializesWaitResponseWithNulls()
	{
		String json = "{\"action\":\"wait\",\"reason\":\"waiting\"}";
		OfferAdviceResponse r = gson.fromJson(json, OfferAdviceResponse.class);
		assertEquals(OfferAction.WAIT, r.getActionEnum());
		assertNull(r.getNewPrice());
		assertNull(r.getNetProfitEstimate());
	}

	@Test
	public void deserializesCourierStateFields()
	{
		String json = "{\"action\":\"wait\",\"reason\":\"monitoring\",\"position_margin\":3100,"
			+ "\"consecutive_margin_decreases\":2,\"cumulative_margin_reduction_pct\":0.15}";
		OfferAdviceResponse r = gson.fromJson(json, OfferAdviceResponse.class);
		assertEquals(Integer.valueOf(3100), r.getPositionMargin());
		assertEquals(2, r.getConsecutiveMarginDecreases());
		assertEquals(0.15, r.getCumulativeMarginReductionPct(), 1e-9);
	}

	@Test
	public void defaultsCourierCountersWhenAbsent()
	{
		String json = "{\"action\":\"wait\",\"reason\":\"x\"}";
		OfferAdviceResponse r = gson.fromJson(json, OfferAdviceResponse.class);
		assertNull(r.getPositionMargin());
		assertEquals(0, r.getConsecutiveMarginDecreases());
		assertEquals(0.0, r.getCumulativeMarginReductionPct(), 1e-9);
	}
}
