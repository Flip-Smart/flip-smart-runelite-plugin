package com.flipsmart;
import com.flipsmart.domain.offer.OfferAction;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OfferActionTest
{
	@Test
	public void parsesEveryWireValue()
	{
		assertEquals(OfferAction.WAIT, OfferAction.fromWire("wait"));
		assertEquals(OfferAction.CANCEL_AND_RELIST_OTHER, OfferAction.fromWire("cancel_and_relist_other"));
		assertEquals(OfferAction.CANCEL_AND_SELL_PARTIAL, OfferAction.fromWire("cancel_and_sell_partial"));
		assertEquals(OfferAction.MOVE_PRICE_DOWN, OfferAction.fromWire("move_price_down"));
		assertEquals(OfferAction.EXIT_AT_BREAKEVEN, OfferAction.fromWire("exit_at_breakeven"));
		assertEquals(OfferAction.EXIT_AT_LOSS, OfferAction.fromWire("exit_at_loss"));
	}

	@Test
	public void unknownOrNullWireValueIsNull()
	{
		assertNull(OfferAction.fromWire("bogus"));
		assertNull(OfferAction.fromWire(null));
	}
}
