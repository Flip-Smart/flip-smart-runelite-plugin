package com.flipsmart;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ActiveOfferDispositionCacheTest
{
	@Test
	public void surfacePriceResponseIsCachedForPanel()
	{
		ActiveOfferAdvisorService svc = new ActiveOfferAdvisorService();
		OfferAdviceResponse resp = new OfferAdviceResponse();
		resp.setAction("move_price_down");
		resp.setReason("Move down to 1050");
		resp.setNewPrice(1050);

		svc.applyResponse(12345, resp);

		OfferAdviceResponse cached = svc.getDisposition(12345);
		assertEquals("move_price_down", cached.getAction());
		assertEquals(Integer.valueOf(1050), cached.getNewPrice());
	}

	@Test
	public void waitResponseClearsAnyPriorDisposition()
	{
		ActiveOfferAdvisorService svc = new ActiveOfferAdvisorService();
		OfferAdviceResponse move = new OfferAdviceResponse();
		move.setAction("move_price_down");
		svc.applyResponse(1, move);

		OfferAdviceResponse wait = new OfferAdviceResponse();
		wait.setAction("wait");
		svc.applyResponse(1, wait);

		assertNull(svc.getDisposition(1));
	}

	@Test
	public void handoffInvokesCallbackAndClearsCache()
	{
		ActiveOfferAdvisorService svc = new ActiveOfferAdvisorService();
		final int[] handoffItem = {-1};
		svc.setCallbacks(null, id -> handoffItem[0] = id);

		OfferAdviceResponse move = new OfferAdviceResponse();
		move.setAction("move_price_down");
		svc.applyResponse(7, move);

		OfferAdviceResponse cancel = new OfferAdviceResponse();
		cancel.setAction("cancel_and_relist_other");
		svc.applyResponse(7, cancel);

		assertEquals(7, handoffItem[0]);
		assertNull(svc.getDisposition(7));
	}
}
