package com.flipsmart.domain.offer;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class OfferRecordStageTest
{
	@Test
	public void defaultsToInitial()
	{
		OfferRecord offer = OfferRecord.newOffer(1L, 0, 1, "Item", true, 10, 1000, 0L);
		assertEquals("initial", offer.getOfferStage());
	}

	@Test
	public void stageRoundTripsThroughGson()
	{
		OfferRecord offer = OfferRecord.newOffer(1L, 0, 1, "Item", false, 10, 1000, 0L)
			.withOfferStage("breakeven_relist");
		Gson gson = new Gson();
		OfferRecord restored = gson.fromJson(gson.toJson(offer), OfferRecord.class);
		assertEquals("breakeven_relist", restored.getOfferStage());
	}
}
