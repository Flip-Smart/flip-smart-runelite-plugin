package com.flipsmart.recommend;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class ActionResolverBuysSuppressedTest
{
	private final ActionResolver resolver = new ActionResolver();

	@Test
	public void suppressedBuysFallThroughToCollect()
	{
		OfferRecord completedSell = OfferRecord.newOffer(1L, 0, 561, "x", false, 10, 100, 1L)
			.withFill(10, 0L, OfferState.FILLED, 2L); // completed sell awaiting collection (S5)
		ResolverInput in = ResolverInput.builder()
			.slotLimit(8)
			.filledSlotCount(1)
			.surfaceableBuy(true, 999) // a buy is available...
			.nowMillis(100L)
			.buysSuppressed(true)      // ...but sell-only mode suppresses it
			.completedAwaitingCollection(Collections.singletonList(completedSell))
			.build();

		ActionDecision d = resolver.resolve(in);
		assertEquals(ActionStep.COLLECT, d.getStep()); // not PLACE_BUY
		assertEquals(561, d.getItemId());
	}

	@Test
	public void unsuppressedBuyIsChosenWhenSoleCandidate()
	{
		ResolverInput in = ResolverInput.builder()
			.slotLimit(8)
			.filledSlotCount(1)
			.surfaceableBuy(true, 999)
			.nowMillis(100L)
			.buysSuppressed(false)
			.build();

		assertEquals(ActionStep.PLACE_BUY, resolver.resolve(in).getStep());
	}
}
