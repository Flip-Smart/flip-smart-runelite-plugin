package com.flipsmart;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TrackedOfferStageTest
{
	@Test
	public void defaultsToInitial()
	{
		TrackedOffer offer = new TrackedOffer(1, "Item", true, 10, 1000, 0);
		assertEquals("initial", offer.getOfferStage());
	}

	@Test
	public void stageRoundTripsThroughGson()
	{
		TrackedOffer offer = new TrackedOffer(1, "Item", false, 10, 1000, 0);
		offer.setOfferStage("breakeven_relist");
		Gson gson = new Gson();
		TrackedOffer restored = gson.fromJson(gson.toJson(offer), TrackedOffer.class);
		assertEquals("breakeven_relist", restored.getOfferStage());
	}

	@Test
	public void nullStageReadsAsInitial()
	{
		Gson gson = new Gson();
		TrackedOffer legacy = gson.fromJson("{\"itemId\":1,\"isBuy\":true}", TrackedOffer.class);
		assertEquals("initial", legacy.getOfferStage());
	}
}
