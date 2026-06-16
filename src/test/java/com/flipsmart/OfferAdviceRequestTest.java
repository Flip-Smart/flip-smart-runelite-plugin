package com.flipsmart;
import com.flipsmart.api.dto.OfferAdviceRequest;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OfferAdviceRequestTest
{
	@Test
	public void formatsEpochMillisAsIsoUtc()
	{
		assertEquals("2026-06-09T20:00:00Z", OfferAdviceRequest.toIsoUtc(1781035200000L));
	}

	@Test
	public void zeroOrNegativeMillisIsNull()
	{
		assertNull(OfferAdviceRequest.toIsoUtc(0L));
		assertNull(OfferAdviceRequest.toIsoUtc(-1L));
	}

	@Test
	public void builderCarriesFields()
	{
		OfferAdviceRequest req = OfferAdviceRequest.builder()
			.itemId(1)
			.pool("high_vol")
			.side("sell")
			.stage("initial")
			.listedPrice(1000)
			.listedQuantity(10)
			.filledQuantity(2)
			.build();
		assertEquals(1, req.getItemId());
		assertEquals("high_vol", req.getPool());
		assertEquals("sell", req.getSide());
		assertEquals(2, req.getFilledQuantity());
	}
}
