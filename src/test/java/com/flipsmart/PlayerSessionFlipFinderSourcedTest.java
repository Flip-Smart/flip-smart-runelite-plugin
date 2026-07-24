package com.flipsmart;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Flip Finder-sourced item tracking (free-tier 2-item cap). Only items whose flip
 * was started from a Flip Finder recommendation count toward the cap; membership is
 * pruned against the active-flip set so an item releases once its flip resolves.
 */
public class PlayerSessionFlipFinderSourcedTest
{
	private Set<Integer> active(Integer... ids)
	{
		return new HashSet<>(java.util.Arrays.asList(ids));
	}

	@Test
	public void marksAndCountsSourcedItemsThatAreStillActive()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100);
		session.markFlipFinderSourced(200);

		// Both are still active flips → both count.
		assertEquals(2, session.retainAndCountFlipFinderActive(active(100, 200)));
		assertTrue(session.getFlipFinderSourcedItems().contains(100));
	}

	@Test
	public void prunesSourcedItemsNoLongerActive()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100);
		session.markFlipFinderSourced(200);

		// 200's flip resolved (not in active set) → dropped, count falls to 1.
		assertEquals(1, session.retainAndCountFlipFinderActive(active(100)));
		assertFalse(session.getFlipFinderSourcedItems().contains(200));
	}

	@Test
	public void manualItemsAreNeverCounted()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100); // Flip Finder buy

		// Item 300 is an active flip but was never marked (manual listing) → not counted.
		assertEquals(1, session.retainAndCountFlipFinderActive(active(100, 300)));
	}

	@Test
	public void countIsZeroWhenNoActiveFlips()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100);
		assertEquals(0, session.retainAndCountFlipFinderActive(Collections.emptySet()));
	}

	@Test
	public void markingIsIdempotent()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100);
		session.markFlipFinderSourced(100);
		assertEquals(1, session.retainAndCountFlipFinderActive(active(100)));
	}

	@Test
	public void clearResetsSourcedItems()
	{
		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(100);
		session.clear();
		assertEquals(0, session.retainAndCountFlipFinderActive(active(100)));
	}
}
