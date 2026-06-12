package com.flipsmart;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@link AutoRecommendService#buildRefreshedQueue} — the ordering decision
 * applied when Flip Finder refreshes its pool in auto mode (issue #722). The
 * focused item must be dropped when it leaves the refreshed pool and retained
 * (without churn) when it is still present.
 */
public class AutoRecommendRefreshQueueTest
{
	private static FlipRecommendation rec(int itemId, String name, double volumePerHour)
	{
		FlipRecommendation r = new FlipRecommendation();
		r.setItemId(itemId);
		r.setItemName(name);
		r.setVolumePerHour(volumePerHour);
		return r;
	}

	private static List<Integer> itemIds(List<FlipRecommendation> queue)
	{
		return queue.stream().map(FlipRecommendation::getItemId).collect(Collectors.toList());
	}

	private static final Set<Integer> NONE_ACTIVE = Collections.emptySet();

	// AC2 — focused item removed from the refreshed pool is dropped from the queue.
	@Test
	public void dropsFocusedItemWhenNoLongerRecommended()
	{
		FlipRecommendation stale = rec(1001, "Demonic Hood", 50);
		List<FlipRecommendation> refreshedPool = Arrays.asList(
			rec(2002, "Raw Shark", 100),
			rec(3003, "Magic Logs", 200));

		List<FlipRecommendation> queue = AutoRecommendService.buildRefreshedQueue(
			refreshedPool, stale, NONE_ACTIVE);

		assertFalse("stale item must be dropped", itemIds(queue).contains(1001));
		assertEquals(Arrays.asList(2002, 3003), itemIds(queue));
	}

	// AC3 — focused item still in the refreshed pool is kept at index 0 (no churn).
	@Test
	public void retainsFocusedItemAtFrontWhenStillRecommended()
	{
		FlipRecommendation focused = rec(2002, "Raw Shark", 100);
		List<FlipRecommendation> refreshedPool = Arrays.asList(
			rec(3003, "Magic Logs", 200),
			rec(2002, "Raw Shark", 100),
			rec(4004, "Yew Logs", 300));

		List<FlipRecommendation> queue = AutoRecommendService.buildRefreshedQueue(
			refreshedPool, focused, NONE_ACTIVE);

		assertEquals("focused item stays at index 0", 2002, queue.get(0).getItemId());
		assertEquals("no duplication of the focused item", 3, queue.size());
		assertTrue(itemIds(queue).containsAll(Arrays.asList(2002, 3003, 4004)));
	}

	// AC5 — focused item now live on the GE is excluded from the refreshed (filtered)
	// pool and must not be re-prepended; the offer is left untouched.
	@Test
	public void doesNotPrependFocusedItemThatIsLiveOnTheGe()
	{
		FlipRecommendation onGe = rec(2002, "Raw Shark", 100);
		// filtered pool already excludes GE-active items, so onGe is absent here.
		List<FlipRecommendation> refreshedPool = Arrays.asList(
			rec(3003, "Magic Logs", 200));
		Set<Integer> active = new HashSet<>(Collections.singletonList(2002));

		List<FlipRecommendation> queue = AutoRecommendService.buildRefreshedQueue(
			refreshedPool, onGe, active);

		assertFalse("GE-active item not re-queued", itemIds(queue).contains(2002));
		assertEquals(Arrays.asList(3003), itemIds(queue));
	}

	// AC6 — empty refreshed pool clears the queue without error.
	@Test
	public void clearsQueueWhenRefreshedPoolIsEmpty()
	{
		FlipRecommendation stale = rec(1001, "Demonic Hood", 50);

		List<FlipRecommendation> queue = AutoRecommendService.buildRefreshedQueue(
			Collections.emptyList(), stale, NONE_ACTIVE);

		assertTrue("queue cleared when nothing recommended", queue.isEmpty());
	}

	// Nothing focused yet — queue mirrors the refreshed pool.
	@Test
	public void mirrorsPoolWhenNothingFocused()
	{
		List<FlipRecommendation> refreshedPool = Arrays.asList(
			rec(2002, "Raw Shark", 100),
			rec(3003, "Magic Logs", 200));

		List<FlipRecommendation> queue = AutoRecommendService.buildRefreshedQueue(
			refreshedPool, null, NONE_ACTIVE);

		assertEquals(Arrays.asList(2002, 3003), itemIds(queue));
	}
}
