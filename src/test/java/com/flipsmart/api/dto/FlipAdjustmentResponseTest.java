package com.flipsmart.api.dto;

import com.flipsmart.api.dto.Dtos.FlipAdjustmentResponse;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlipAdjustmentResponseTest
{
	private static FlipAdjustmentResponse of(String action, Long price)
	{
		FlipAdjustmentResponse r = new FlipAdjustmentResponse();
		r.setAction(action);
		r.setRecommendedPrice(price);
		return r;
	}

	@Test
	public void readjustToTheListedPriceIsNotAnAction()
	{
		assertFalse(of("readjust_sell", 6L).needsActionOn(6));
		assertFalse(of("readjust_buy", 5L).needsActionOn(5));
	}

	@Test
	public void readjustToADifferentPriceIsAnAction()
	{
		assertTrue(of("readjust_sell", 6L).needsActionOn(9));
		assertTrue(of("readjust_buy", 5L).needsActionOn(4));
	}

	@Test
	public void readjustWithoutAPriceIsNotAnAction()
	{
		assertFalse(of("readjust_sell", null).needsActionOn(6));
	}

	@Test
	public void holdAndCancelKeepTheirMeaning()
	{
		assertFalse(of("hold", 6L).needsActionOn(6));
		assertTrue(of("cancel_and_sell", null).needsActionOn(6));
	}
}
