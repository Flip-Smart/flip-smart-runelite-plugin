package com.flipsmart.api.dto;

import com.flipsmart.api.dto.Dtos.PriceTargetResponse;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PriceTargetResponseTest
{
	private final Gson gson = new Gson();

	@Test
	public void parsesScenarioBWithMid()
	{
		String json = "{\"recommended_buy_price\":100,\"recommended_sell_price\":120,"
			+ "\"listing_sell_price\":115,\"listing_strategy\":\"mid\",\"scenario\":\"B\",\"scenario_b_mid\":114}";
		PriceTargetResponse r = gson.fromJson(json, PriceTargetResponse.class);
		assertEquals(Integer.valueOf(115), r.getListingSellPrice());
		assertEquals("mid", r.getListingStrategy());
		assertEquals("B", r.getScenario());
		assertEquals(Integer.valueOf(114), r.getScenarioBMid());
	}

	@Test
	public void parsesScenarioAWithNullBMid()
	{
		String json = "{\"recommended_buy_price\":100,\"recommended_sell_price\":120,"
			+ "\"listing_sell_price\":118,\"listing_strategy\":\"instant\",\"scenario\":\"A\",\"scenario_b_mid\":null}";
		PriceTargetResponse r = gson.fromJson(json, PriceTargetResponse.class);
		assertEquals("A", r.getScenario());
		assertNull(r.getScenarioBMid());
	}

	@Test
	public void nullFirstListingFieldsWhenNoBuyPrice()
	{
		String json = "{\"recommended_buy_price\":100,\"recommended_sell_price\":120,"
			+ "\"listing_sell_price\":null,\"listing_strategy\":null,\"scenario\":null,\"scenario_b_mid\":null}";
		PriceTargetResponse r = gson.fromJson(json, PriceTargetResponse.class);
		assertEquals(100, r.getRecommendedBuyPrice());
		assertNull(r.getListingSellPrice());
		assertNull(r.getScenario());
		assertNull(r.getScenarioBMid());
	}
}
