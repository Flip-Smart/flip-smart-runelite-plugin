package com.flipsmart;
import com.flipsmart.domain.offer.OfferAction;
import com.flipsmart.api.dto.OfferAdviceBatchResponse;
import com.flipsmart.api.dto.OfferAdviceResult;
import com.flipsmart.api.dto.OfferAdviceRequest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OfferActionsBatchTest
{
	private static final String OFFERS_KEY = "offers";

	@Test
	public void bodyWrapsEachOfferUnderOffersArray()
	{
		OfferAdviceRequest a = OfferAdviceRequest.builder()
			.itemId(1).pool("high_vol").side("buy").stage("initial")
			.listedAtMillis(1781035200000L).listedPrice(5).listedQuantity(1).filledQuantity(0).build();
		OfferAdviceRequest b = OfferAdviceRequest.builder()
			.itemId(2).pool("mid_vol").side("sell").stage("initial")
			.listedAtMillis(1781035200000L).listedPrice(50).listedQuantity(3).filledQuantity(1)
			.userAvgBuyPrice(40).build();

		JsonObject body = FlipSmartApiClient.buildOfferActionsBody(Arrays.asList(a, b));

		assertTrue(body.has(OFFERS_KEY));
		assertEquals(2, body.getAsJsonArray(OFFERS_KEY).size());
		assertEquals(1, body.getAsJsonArray(OFFERS_KEY).get(0).getAsJsonObject().get("item_id").getAsInt());
		assertEquals(2, body.getAsJsonArray(OFFERS_KEY).get(1).getAsJsonObject().get("item_id").getAsInt());
	}

	@Test
	public void deserializesBatchResponseWithItemIdAndAdvice()
	{
		String json = "{\"results\":["
			+ "{\"item_id\":111,\"action\":\"move_price_down\",\"reason\":\"r\",\"new_price\":1050,\"net_profit_estimate\":900},"
			+ "{\"item_id\":222,\"action\":\"wait\",\"reason\":\"w\"}]}";

		OfferAdviceBatchResponse resp = new Gson().fromJson(json, OfferAdviceBatchResponse.class);

		assertEquals(2, resp.getResults().size());
		OfferAdviceResult first = resp.getResults().get(0);
		assertEquals(111, first.getItemId());
		assertEquals(OfferAction.MOVE_PRICE_DOWN, first.getActionEnum());
		assertEquals(Integer.valueOf(1050), first.getNewPrice());

		OfferAdviceResult second = resp.getResults().get(1);
		assertEquals(222, second.getItemId());
		assertEquals(OfferAction.WAIT, second.getActionEnum());
		assertNull(second.getNewPrice());
	}
}
