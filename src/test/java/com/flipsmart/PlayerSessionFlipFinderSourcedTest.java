package com.flipsmart;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Flip Finder-sourced item tracking (free-tier 2-item cap). Only items whose flip was started
 * from a Flip Finder recommendation count. Membership is pruned against the active-flip set,
 * with a grace window so a just-placed buy whose GE offer is still propagating into the store
 * isn't dropped before it lands (Auto re-resolves immediately, so without the grace it would
 * under-count and surface a 3rd buy).
 */
public class PlayerSessionFlipFinderSourcedTest
{
	// Mirrors PlayerSession.FLIP_FINDER_SOURCED_GRACE_MS.
	private static final long GRACE_MS = 8000;
	private static final long T0 = 1_000_000L;

	private Set<Integer> active(Integer... ids)
	{
		return new HashSet<>(java.util.Arrays.asList(ids));
	}

	@Test
	public void marksAndCountsSourcedItemsThatAreStillActive()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100, T0);
		session.markFlipFinderSourced(200, T0);

		assertEquals(2, session.retainAndCountFlipFinderActive(active(100, 200), T0));
		assertTrue(session.getFlipFinderSourcedItems().contains(100));
	}

	@Test
	public void overlappingBuyAndSellOfOneItemCountsAsASingleFlip()
	{
		// #1116 AC1/AC3: collecting part of a buy and relisting it to sell before the buy
		// completes must not consume two of a free user's slots. Both legs are the same item, and
		// counting is keyed by item id, so the pair collapses to one flip.
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(4151, T0);

		// Two GE slots (the original buy and the early resale) plus the collected lot the player
		// still holds — the shape FlipSmartPlugin.getActiveFlipItemIds() produces for an overlap.
		Set<Integer> ids = com.flipsmart.domain.flip.ActiveFlipItemIds.derive(
			active(4151), active(4151), Collections.singletonMap(4151, 5), true);

		assertEquals("an overlapping buy+sell of one item is one flip", 1, ids.size());
		assertEquals(1, session.retainAndCountFlipFinderActive(ids, T0));
	}

	@Test
	public void prunesSourcedItemsNoLongerActive()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100, T0);
		session.markFlipFinderSourced(200, T0);

		// Past grace: 200's flip resolved (not active) → dropped, count falls to 1.
		assertEquals(1, session.retainAndCountFlipFinderActive(active(100), T0 + GRACE_MS + 1));
		assertFalse(session.getFlipFinderSourcedItems().contains(200));
	}

	@Test
	public void justMarkedItemCountsWhileOfferStillPropagating()
	{
		// The race fix: a buy was just marked but its offer isn't in the store yet (empty
		// active set). Within grace it must still count, so Auto's immediate re-resolve
		// doesn't under-count and surface a 3rd buy.
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100, T0);
		session.markFlipFinderSourced(200, T0);

		assertEquals(2, session.retainAndCountFlipFinderActive(Collections.emptySet(), T0 + 500));
		assertTrue(session.getFlipFinderSourcedItems().contains(200));
	}

	@Test
	public void pastGraceNonActiveItemIsPruned()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100, T0);
		assertEquals(0, session.retainAndCountFlipFinderActive(Collections.emptySet(), T0 + GRACE_MS + 1));
		assertFalse(session.getFlipFinderSourcedItems().contains(100));
	}

	@Test
	public void manualItemsAreNeverCounted()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100, T0); // Flip Finder buy

		// Item 300 is an active flip but was never marked (manual listing) → not counted.
		assertEquals(1, session.retainAndCountFlipFinderActive(active(100, 300), T0));
	}

	@Test
	public void markingIsIdempotent()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100, T0);
		session.markFlipFinderSourced(100, T0);
		assertEquals(1, session.retainAndCountFlipFinderActive(active(100), T0 + GRACE_MS + 1));
	}

	@Test
	public void clearResetsSourcedItems()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100, T0);
		session.clear();
		assertEquals(0, session.retainAndCountFlipFinderActive(active(100), T0));
	}

	@Test
	public void restoreReplacesSourcedItemsAndReStampsGrace()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(999, T0); // stale in-memory value
		session.restoreFlipFinderSourced(active(100, 200), T0 + 100_000L);
		assertFalse(session.getFlipFinderSourcedItems().contains(999));
		// Restored items are within their fresh grace even before offers reload (empty active).
		assertEquals(2, session.retainAndCountFlipFinderActive(Collections.emptySet(), T0 + 100_000L));
	}
}
