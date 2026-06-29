package com.flipsmart.recommend;

import com.flipsmart.domain.offer.OfferRecord;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class StaleOfferQueueTest
{
	@Test
	public void head_onEmptyQueue_returnsNullInsteadOfThrowing()
	{
		// The queue can be drained between an isEmpty() check and head() (the offer is
		// cancelled/filled on another path), so head() must be null-safe rather than throw
		// ArrayIndexOutOfBoundsException into the RuneLite client thread.
		assertNull("head() on an empty queue must return null, not throw",
			new StaleOfferQueue().head());
	}

	@Test
	public void head_returnsFirstQueuedOffer_whenNonEmpty()
	{
		StaleOfferQueue queue = new StaleOfferQueue();
		queue.addIfAbsent(OfferRecord.newOffer(1L, 0, 4151, "Abyssal whip", false, 1, 100, 1000L));

		OfferRecord head = queue.head();
		assertNotNull(head);
		assertEquals(4151, head.getItemId());
	}
}
