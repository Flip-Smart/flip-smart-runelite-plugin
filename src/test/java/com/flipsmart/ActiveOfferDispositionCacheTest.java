package com.flipsmart;
import com.flipsmart.api.dto.OfferAdviceResponse;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ActiveOfferDispositionCacheTest
{
	private static final String MOVE_PRICE_DOWN = "move_price_down";

	@Test
	public void surfacePriceResponseIsCachedForPanel()
	{
		ActiveOfferAdvisorService svc = new ActiveOfferAdvisorService();
		OfferAdviceResponse resp = new OfferAdviceResponse();
		resp.setAction(MOVE_PRICE_DOWN);
		resp.setReason("Move down to 1050");
		resp.setNewPrice(1050);

		svc.applyResponse(12345, resp);

		OfferAdviceResponse cached = svc.getDisposition(12345);
		assertEquals(MOVE_PRICE_DOWN, cached.getAction());
		assertEquals(Integer.valueOf(1050), cached.getNewPrice());
	}

	@Test
	public void waitResponseClearsCacheAndFiresClearCallback()
	{
		ActiveOfferAdvisorService svc = new ActiveOfferAdvisorService();
		final int[] cleared = {-1};
		svc.setCallbacks(null, null, id -> cleared[0] = id);

		OfferAdviceResponse move = new OfferAdviceResponse();
		move.setAction(MOVE_PRICE_DOWN);
		svc.applyResponse(1, move);

		OfferAdviceResponse wait = new OfferAdviceResponse();
		wait.setAction("wait");
		svc.applyResponse(1, wait);

		assertNull(svc.getDisposition(1));
		assertEquals(1, cleared[0]);
	}

	@Test
	public void handoffFiresHandoffAndClearCallbacksAndClearsCache()
	{
		ActiveOfferAdvisorService svc = new ActiveOfferAdvisorService();
		final int[] handoffItem = {-1};
		final int[] cleared = {-1};
		svc.setCallbacks(null, id -> handoffItem[0] = id, id -> cleared[0] = id);

		OfferAdviceResponse move = new OfferAdviceResponse();
		move.setAction(MOVE_PRICE_DOWN);
		svc.applyResponse(7, move);

		OfferAdviceResponse cancel = new OfferAdviceResponse();
		cancel.setAction("cancel_and_relist_other");
		svc.applyResponse(7, cancel);

		assertEquals(7, handoffItem[0]);
		assertEquals(7, cleared[0]);
		assertNull(svc.getDisposition(7));
	}

	@Test
	public void surfacePriceDoesNotFireClearCallback()
	{
		ActiveOfferAdvisorService svc = new ActiveOfferAdvisorService();
		final boolean[] clearFired = {false};
		svc.setCallbacks(null, null, id -> clearFired[0] = true);

		OfferAdviceResponse move = new OfferAdviceResponse();
		move.setAction(MOVE_PRICE_DOWN);
		svc.applyResponse(2, move);

		assertTrue("clear must not fire on surface", !clearFired[0]);
	}
}
