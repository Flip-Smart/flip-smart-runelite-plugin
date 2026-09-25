package com.flipsmart.v9;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class V9SellArbitrationTest
{
	private V9FlipState tracked(long listingTimestampMs)
	{
		V9FlipState s = new V9FlipState();
		s.setItemId(42);
		s.setTimeframe("30m");
		s.setBuyPrice(1000L);
		s.setOriginalTarget(1100L);
		s.setListingTimestampMs(listingTimestampMs);
		return s;
	}

	@Test
	public void ownsSellAsSoonAsFlipIsTracked_beforeFirstListingArms()
	{
		// Regression guard: legacy must stand down the moment V9 tracks the flip, even before the
		// first-listing prompt has fired (listingTimestampMs == 0). Requiring an armed ladder here
		// let the legacy path grab the sell and re-price a winner to a loss.
		assertTrue(V9SellArbitration.v9OwnsSell(true, tracked(0L)));
		assertTrue(V9SellArbitration.v9OwnsSell(true, tracked(1_000_000L)));
	}

	@Test
	public void doesNotOwnSellWhenDisabledOrUntracked()
	{
		assertFalse(V9SellArbitration.v9OwnsSell(false, tracked(1_000_000L)));
		assertFalse(V9SellArbitration.v9OwnsSell(true, null));
		assertFalse(V9SellArbitration.v9OwnsSell(false, null));
	}

	@Test
	public void firstListingTargetPrefersLiveSessionRecommendation()
	{
		assertEquals(1200L, V9SellArbitration.resolveFirstListingTarget(1200L, tracked(0L)));
	}

	@Test
	public void firstListingTargetFallsBackToSnapshottedTargetWhenSessionRecMissing()
	{
		// The session rec can be absent at sell time (cleared/never seeded for a manual follow, or
		// captured before the rec was stored); fall back to the target snapshotted on the flip
		// state at buy time so first listing still arms.
		assertEquals(1100L, V9SellArbitration.resolveFirstListingTarget(null, tracked(0L)));
		assertEquals(1100L, V9SellArbitration.resolveFirstListingTarget(0L, tracked(0L)));
	}

	@Test
	public void firstListingTargetIsZeroWhenNeitherAvailable()
	{
		V9FlipState noTarget = tracked(0L);
		noTarget.setOriginalTarget(0L);
		assertEquals(0L, V9SellArbitration.resolveFirstListingTarget(null, noTarget));
		assertEquals(0L, V9SellArbitration.resolveFirstListingTarget(null, null));
	}
}
