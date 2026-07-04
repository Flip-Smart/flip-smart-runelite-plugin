package com.flipsmart.recommend;

import com.flipsmart.domain.flip.FlipRecommendation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for {@link RecommendationQueue}, the buy-queue collaborator
 * extracted from {@code AutoRecommendService} in #733. These pin the current bounded
 * add/replace, cursor-advancement, and lifecycle semantics so the deferred Codacy
 * refactors (unmodifiable {@code view()}, cursor-reset on {@code clear()}, field
 * renames) are made against a guarded baseline rather than blind.
 *
 * <p>Two current behaviors are surprising and pinned deliberately: {@code view()}
 * returns the LIVE backing list (not an unmodifiable copy), and {@code clear()} /
 * {@code clearAll()} do NOT reset the cursor.
 */
public class RecommendationQueueTest
{
	private static FlipRecommendation rec(int itemId, int sellPrice)
	{
		FlipRecommendation r = new FlipRecommendation();
		r.setItemId(itemId);
		r.setItemName("item-" + itemId);
		r.setRecommendedSellPrice(sellPrice);
		return r;
	}

	private static FlipRecommendation recVol(int itemId, double volumePerHour)
	{
		FlipRecommendation r = rec(itemId, 0);
		r.setVolumePerHour(volumePerHour);
		return r;
	}

	// ---- replace / add ----

	@Test
	public void replaceClearsAddsAndResetsCursorToFront()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.setCurrentIndex(3);
		FlipRecommendation a = rec(11, 100);
		q.replace(Arrays.asList(a, rec(12, 100)));

		assertEquals(2, q.size());
		assertEquals(0, q.getCurrentIndex());
		assertSame(a, q.get(0));
	}

	@Test
	public void addFilteredByActiveDropsActiveItemsButStillCachesTheirNames()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.setCurrentIndex(2);
		q.addFilteredByActive(
			Arrays.asList(rec(11, 100), rec(12, 100)),
			Collections.singleton(12));

		assertEquals("active item must be filtered out of the queue", 1, q.size());
		assertEquals(11, q.get(0).getItemId());
		// Name cache survives the active filter (used for prompts even while live on the GE).
		assertEquals("item-12", q.getItemName(12, "fallback"));
		assertEquals("item-11", q.getItemName(11, "fallback"));
		assertEquals("addFilteredByActive must not reset the cursor", 2, q.getCurrentIndex());
	}

	@Test
	public void getItemNameReturnsFallbackWhenUncached()
	{
		assertEquals("fallback", new RecommendationQueue().getItemName(999, "fallback"));
	}

	// ---- snapshot vs view ----

	@Test
	public void snapshotIsADetachedCopy()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(rec(11, 100)));

		List<FlipRecommendation> snap = q.snapshot();
		snap.clear();

		assertEquals("mutating the snapshot must not affect the queue", 1, q.size());
	}

	@Test
	public void viewReflectsTheBackingListForReads()
	{
		// view() is an unmodifiable window over the live backing list — reads still
		// reflect later mutations to the queue.
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(rec(11, 100)));
		List<FlipRecommendation> view = q.view();
		assertEquals(1, view.size());

		q.clear();
		assertEquals("view() must reflect the live backing list for reads", 0, view.size());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void viewRejectsMutation()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(rec(11, 100)));
		q.view().add(rec(12, 100));
	}

	@Test
	public void addAllAppendsWithoutResettingCursor()
	{
		// Used by the offline-sync restore path, which sets a specific cursor afterward
		// (so unlike replace(), addAll must leave the cursor alone).
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(rec(11, 100)));
		q.setCurrentIndex(1);

		q.addAll(Arrays.asList(rec(12, 100), rec(13, 100)));

		assertEquals(3, q.size());
		assertEquals(12, q.get(1).getItemId());
		assertEquals("addAll must not reset the cursor", 1, q.getCurrentIndex());
	}

	@Test
	public void replaceIsSafeWhenAliasedWithItsOwnView()
	{
		// view() is an unmodifiable window over the backing list; replace() must copy its
		// argument before clearing, or replace(view()) would self-empty before re-adding.
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(rec(11, 100), rec(12, 100)));

		q.replace(q.view());

		assertEquals("replace(view()) must not self-clear", 2, q.size());
		assertEquals(11, q.get(0).getItemId());
	}

	// ---- cursor ----

	@Test
	public void getCurrentRecommendationReturnsItemAtCursor()
	{
		RecommendationQueue q = new RecommendationQueue();
		FlipRecommendation b = rec(12, 100);
		q.replace(Arrays.asList(rec(11, 100), b));
		q.setCurrentIndex(1);
		assertSame(b, q.getCurrentRecommendation());
	}

	@Test
	public void getCurrentRecommendationNullWhenCursorOutOfBounds()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(rec(11, 100)));
		q.setCurrentIndex(5);
		assertNull(q.getCurrentRecommendation());
	}

	@Test
	public void cursorBoundsPredicates()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(rec(11, 100)));

		q.setCurrentIndex(0);
		assertTrue(q.cursorWithinBounds());
		assertFalse(q.cursorBeyondEnd());

		q.setCurrentIndex(1);
		assertFalse(q.cursorWithinBounds());
		assertTrue(q.cursorBeyondEnd());
	}

	@Test
	public void skipToNextSurfaceableSkipsActiveAndBelowMinProfit()
	{
		RecommendationQueue q = new RecommendationQueue();
		FlipRecommendation surfaceable = rec(14, 500);
		q.replace(Arrays.asList(rec(11, 500), rec(12, 500), rec(13, 50), surfaceable));
		q.setCurrentIndex(0); // currently on item 11

		// item 12 is live on the GE (skip), item 13 profit 50 < 100 (skip) → stop at 14.
		q.skipToNextSurfaceable(
			Collections.singleton(12),
			FlipRecommendation::getRecommendedSellPrice,
			100);

		assertEquals(3, q.getCurrentIndex());
		assertSame(surfaceable, q.getCurrentRecommendation());
	}

	@Test
	public void skipToNextSurfaceableRunsOffEndWhenNoneQualify()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(rec(11, 500), rec(12, 50)));
		q.setCurrentIndex(0);

		q.skipToNextSurfaceable(
			Collections.emptySet(),
			FlipRecommendation::getRecommendedSellPrice,
			100); // item 12 profit 50 < 100 → run off the end

		assertEquals(2, q.getCurrentIndex());
		assertTrue(q.cursorBeyondEnd());
		assertNull(q.getCurrentRecommendation());
	}

	// ---- lifecycle: clear / clearAll ----

	@Test
	public void clearEmptiesQueueAndResetsCursorToFront()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(rec(11, 100), rec(12, 100)));
		q.setCurrentIndex(2);

		q.clear();

		assertTrue(q.isEmpty());
		assertEquals("clear() resets the cursor to the front", 0, q.getCurrentIndex());
	}

	@Test
	public void clearAllAlsoDropsCachedNamesAndResetsCursorToFront()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.addFilteredByActive(Arrays.asList(rec(11, 100)), Collections.emptySet());
		q.setCurrentIndex(1);

		q.clearAll();

		assertTrue(q.isEmpty());
		assertEquals("clearAll() must drop the name cache", "fallback", q.getItemName(11, "fallback"));
		assertEquals("clearAll() resets the cursor to the front", 0, q.getCurrentIndex());
	}

	@Test
	public void putItemNameIgnoresNull()
	{
		// itemNames is a ConcurrentHashMap, which rejects null values — a null name must
		// simply not be cached (the getter then returns the caller's fallback).
		RecommendationQueue q = new RecommendationQueue();
		q.putItemName(11, null);
		assertEquals("fallback", q.getItemName(11, "fallback"));
	}

	@Test
	public void addFilteredByActiveSkipsCachingNullNames()
	{
		RecommendationQueue q = new RecommendationQueue();
		FlipRecommendation noName = new FlipRecommendation();
		noName.setItemId(11);
		noName.setItemName(null);

		q.addFilteredByActive(Arrays.asList(noName), Collections.emptySet());

		assertEquals("the recommendation is still queued", 1, q.size());
		assertEquals("a null name must not be cached", "fallback", q.getItemName(11, "fallback"));
	}

	// ---- lookups / sorting ----

	@Test
	public void findRecommendationForItemReturnsMatchOrNull()
	{
		RecommendationQueue q = new RecommendationQueue();
		FlipRecommendation b = rec(12, 100);
		q.replace(Arrays.asList(rec(11, 100), b));

		assertSame(b, q.findRecommendationForItem(12));
		assertNull(q.findRecommendationForItem(999));
	}

	@Test
	public void sortByVolumeAscendingOrdersSlowestFillingFirst()
	{
		RecommendationQueue q = new RecommendationQueue();
		q.replace(Arrays.asList(recVol(11, 10.0), recVol(12, 2.0), recVol(13, 5.0)));

		q.sortByVolumeAscending();

		assertEquals(12, q.get(0).getItemId());
		assertEquals(13, q.get(1).getItemId());
		assertEquals(11, q.get(2).getItemId());
	}

	// ---- ancillary state ----

	@Test
	public void refreshTimestampRoundTrips()
	{
		RecommendationQueue q = new RecommendationQueue();
		assertEquals(0L, q.getLastQueueRefreshMillis());
		q.setLastQueueRefreshMillis(123456L);
		assertEquals(123456L, q.getLastQueueRefreshMillis());
	}

	@Test
	public void lockedItemIdRoundTrips()
	{
		RecommendationQueue q = new RecommendationQueue();
		assertNull(q.getLockedItemId());
		q.setLockedItemId(42);
		assertEquals(Integer.valueOf(42), q.getLockedItemId());
		q.setLockedItemId(null);
		assertNull(q.getLockedItemId());
	}
}
