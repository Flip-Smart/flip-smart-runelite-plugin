package com.flipsmart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** A sell price suggested in one economy is never offered in the other. */
public class HardcoreSessionIsolationTest
{
	private static final int ITEM = 4151;

	@Test
	public void aMainSellPriceDoesNotFollowThePlayerOntoHardcore()
	{
		PlayerSession session = new PlayerSession();
		session.setRecommendedPrice(ITEM, 2_000_000L);

		session.setHardcoreWorld(true);
		assertNull(session.getRecommendedPrice(ITEM));

		session.setRecommendedPrice(ITEM, 900_000L);
		assertEquals(Long.valueOf(900_000L), session.getRecommendedPrice(ITEM));

		session.setHardcoreWorld(false);
		assertEquals("hopping back keeps the main price", Long.valueOf(2_000_000L), session.getRecommendedPrice(ITEM));
	}

	@Test
	public void removingOnHardcoreLeavesTheMainPrice()
	{
		PlayerSession session = new PlayerSession();
		session.setRecommendedPrice(ITEM, 2_000_000L);
		session.setHardcoreWorld(true);
		session.setRecommendedPrice(ITEM, 900_000L);
		session.removeRecommendedPrice(ITEM);

		session.setHardcoreWorld(false);
		assertEquals(Long.valueOf(2_000_000L), session.getRecommendedPrice(ITEM));
	}

	@Test
	public void theEconomyEpochMovesOnlyOnARealSwitch()
	{
		PlayerSession session = new PlayerSession();
		long start = session.getEconomyEpoch();
		session.setHardcoreWorld(false);
		assertEquals(start, session.getEconomyEpoch());
		session.setHardcoreWorld(true);
		assertEquals(start + 1, session.getEconomyEpoch());
		session.setHardcoreWorld(true);
		session.setHardcoreWorld(false);
		assertEquals(start + 2, session.getEconomyEpoch());
	}

	@Test
	public void originalMarginsAreKeptPerWorld()
	{
		PlayerSession session = new PlayerSession();
		session.setOriginalMargin(ITEM, 50_000L);
		session.setHardcoreWorld(true);
		assertNull(session.getOriginalMargin(ITEM));
		session.setOriginalMargin(ITEM, 900L);
		session.setHardcoreWorld(false);
		assertEquals(Long.valueOf(50_000L), session.getOriginalMargin(ITEM));
	}
}
