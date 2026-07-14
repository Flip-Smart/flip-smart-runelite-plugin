package com.flipsmart;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The FlipAssist sell quantity must never exceed what the player physically
 * holds. Prod (#975): the API over-counted a partial fill (buy of 6, only 2
 * filled) and setFocusForSell used Math.max(api, inventory), so it prompted the
 * player to sell 6 while holding 2. Inventory is a hard ceiling for a sell.
 */
public class GrandExchangeTrackerSellQuantityTest
{
	@Test
	public void clampsToInventoryWhenApiOvercounts()
	{
		// Cow: API 6, holds 2 -> never suggest more than held
		assertEquals(2, GrandExchangeTracker.resolveSellQuantity(6, 2));
	}

	@Test
	public void usesApiQuantityWhenInventoryUnknown()
	{
		assertEquals(3, GrandExchangeTracker.resolveSellQuantity(3, 0));
	}

	@Test
	public void fallsBackToInventoryWhenNoTrackedPosition()
	{
		assertEquals(2, GrandExchangeTracker.resolveSellQuantity(0, 2));
	}

	@Test
	public void keepsQuantityWhenApiAndInventoryAgree()
	{
		assertEquals(2, GrandExchangeTracker.resolveSellQuantity(2, 2));
	}
}
