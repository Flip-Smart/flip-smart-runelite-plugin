package com.flipsmart.trading;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class FillWatermarksTest
{
	@Test
	public void identity_isValueEqual_andSeparatesGenerations()
	{
		OfferIdentity a = OfferIdentity.of(0, 4444, true, 1);
		OfferIdentity b = OfferIdentity.of(0, 4444, true, 1);
		OfferIdentity nextGen = OfferIdentity.of(0, 4444, true, 2);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals("a reused slot holds a different offer", a, nextGen);
	}

	@Test
	public void identity_ignoresDirectionCollisions()
	{
		assertNotEquals("a buy and a sell of one item are different offers",
			OfferIdentity.of(0, 4444, true, 1), OfferIdentity.of(0, 4444, false, 1));
	}

	@Test
	public void observe_returnsOnlyTheIncrement()
	{
		FillWatermarks watermarks = new FillWatermarks();
		OfferIdentity id = OfferIdentity.of(0, 7777, true, 1);

		assertEquals(4, watermarks.observe(id, 4, 400L).quantity);
		assertEquals(3, watermarks.observe(id, 7, 700L).quantity);
		assertEquals(300L, watermarks.observe(id, 10, 1000L).spent);
	}

	@Test
	public void observe_isIdempotentOnReplay()
	{
		FillWatermarks watermarks = new FillWatermarks();
		OfferIdentity id = OfferIdentity.of(0, 7777, true, 1);
		watermarks.observe(id, 7, 700L);

		// A login burst re-feeds the same cumulative; it carries no new information.
		assertEquals(0, watermarks.observe(id, 7, 700L).quantity);
		assertEquals(0L, watermarks.observe(id, 7, 700L).spent);
	}

	@Test
	public void observe_neverRegressesOnALowerCumulative()
	{
		FillWatermarks watermarks = new FillWatermarks();
		OfferIdentity id = OfferIdentity.of(0, 7777, true, 1);
		watermarks.observe(id, 7, 700L);

		assertEquals("a stale lower snapshot reports no fill", 0, watermarks.observe(id, 4, 400L).quantity);
		assertEquals("and does not lower the mark", 0, watermarks.observe(id, 7, 700L).quantity);
	}

	@Test
	public void advanceGeneration_startsAFreshBaselineForAReusedSlot()
	{
		FillWatermarks watermarks = new FillWatermarks();
		OfferIdentity first = OfferIdentity.of(0, 7777, true, watermarks.generationFor(0));
		watermarks.observe(first, 10, 1000L);

		watermarks.advanceGeneration(0);
		OfferIdentity second = OfferIdentity.of(0, 7777, true, watermarks.generationFor(0));

		assertNotEquals(first, second);
		assertEquals("a new order in a reused slot counts from zero",
			5, watermarks.observe(second, 5, 500L).quantity);
	}

	@Test
	public void mergeFrom_neverLowersAWatermark()
	{
		FillWatermarks watermarks = new FillWatermarks();
		OfferIdentity id = OfferIdentity.of(0, 7777, true, 1);
		watermarks.observe(id, 4, 400L);

		// A persisted snapshot written before that fill. Restoring it must not rewind progress,
		// or the next observation re-reports fills already sent.
		Map<String, long[]> stale = new HashMap<>();
		stale.put(id.toString(), new long[]{0L, 0L});
		watermarks.mergeFrom(stale);

		assertEquals("a stale restore cannot rewind the mark", 0, watermarks.observe(id, 4, 400L).quantity);
	}

	@Test
	public void mergeFrom_adoptsMarksTheSessionHasNotSeen()
	{
		FillWatermarks watermarks = new FillWatermarks();
		OfferIdentity id = OfferIdentity.of(0, 7777, true, 1);

		Map<String, long[]> restored = new HashMap<>();
		restored.put(id.toString(), new long[]{6L, 600L});
		watermarks.mergeFrom(restored);

		assertEquals("progress from a previous session is adopted", 1, watermarks.observe(id, 7, 700L).quantity);
	}
}
