package com.flipsmart;
import com.flipsmart.domain.offer.OfferAction;
import com.flipsmart.domain.offer.OfferDisposition;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class OfferDispositionTest
{
	@Test
	public void priceActionsSurface()
	{
		assertEquals(OfferDisposition.SURFACE_PRICE, OfferDisposition.route(OfferAction.MOVE_PRICE_DOWN));
		assertEquals(OfferDisposition.SURFACE_PRICE, OfferDisposition.route(OfferAction.EXIT_AT_BREAKEVEN));
		assertEquals(OfferDisposition.SURFACE_PRICE, OfferDisposition.route(OfferAction.EXIT_AT_LOSS));
	}

	@Test
	public void cancelActionsHandOff()
	{
		assertEquals(OfferDisposition.HANDOFF, OfferDisposition.route(OfferAction.CANCEL_AND_RELIST_OTHER));
		assertEquals(OfferDisposition.HANDOFF, OfferDisposition.route(OfferAction.CANCEL_AND_SELL_PARTIAL));
	}

	@Test
	public void waitAndUnknownAreNone()
	{
		assertEquals(OfferDisposition.NONE, OfferDisposition.route(OfferAction.WAIT));
		assertEquals(OfferDisposition.NONE, OfferDisposition.route(null));
	}
}
