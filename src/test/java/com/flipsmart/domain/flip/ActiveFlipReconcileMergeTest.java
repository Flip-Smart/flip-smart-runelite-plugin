package com.flipsmart.domain.flip;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActiveFlipReconcileMergeTest
{
    private static final long GRACE = 15_000L;

    private static ActiveFlip flip(int itemId)
    {
        ActiveFlip f = new ActiveFlip();
        f.setItemId(itemId);
        return f;
    }

    private static boolean contains(List<ActiveFlip> list, int itemId)
    {
        return list.stream().anyMatch(f -> f.getItemId() == itemId);
    }

    @Test
    public void withinGrace_backendMissing_itemLive_keepsFlip()
    {
        ActiveFlip local = flip(383);
        ActiveFlipReconcileMerge.Pending p = new ActiveFlipReconcileMerge.Pending(383, 1_000L, local);

        ActiveFlipReconcileMerge.Result r = ActiveFlipReconcileMerge.merge(
            new ArrayList<>(), Collections.singletonList(p), new HashSet<>(java.util.Arrays.asList(383)),
            5_000L, GRACE);

        assertTrue("kept while pending, live, within grace", contains(r.merged, 383));
        assertFalse("not evicted", r.evict.contains(383));
    }

    @Test
    public void pastGrace_evictsAndDoesNotAppend()
    {
        ActiveFlipReconcileMerge.Pending p =
            new ActiveFlipReconcileMerge.Pending(383, 1_000L, flip(383));

        ActiveFlipReconcileMerge.Result r = ActiveFlipReconcileMerge.merge(
            new ArrayList<>(), Collections.singletonList(p), new HashSet<>(java.util.Arrays.asList(383)),
            1_000L + GRACE + 1, GRACE);

        assertFalse(contains(r.merged, 383));
        assertTrue(r.evict.contains(383));
    }

    @Test
    public void backendConfirmed_evictsWithoutDuplicate()
    {
        List<ActiveFlip> backend = new ArrayList<>();
        backend.add(flip(383));
        ActiveFlipReconcileMerge.Pending p =
            new ActiveFlipReconcileMerge.Pending(383, 1_000L, flip(383));

        ActiveFlipReconcileMerge.Result r = ActiveFlipReconcileMerge.merge(
            backend, Collections.singletonList(p), new HashSet<>(java.util.Arrays.asList(383)), 2_000L, GRACE);

        assertEquals("no duplicate row", 1, r.merged.size());
        assertTrue(r.evict.contains(383));
    }

    @Test
    public void noLongerLive_evicts()
    {
        ActiveFlipReconcileMerge.Pending p =
            new ActiveFlipReconcileMerge.Pending(383, 1_000L, flip(383));

        Set<Integer> live = Collections.emptySet();
        ActiveFlipReconcileMerge.Result r = ActiveFlipReconcileMerge.merge(
            new ArrayList<>(), Collections.singletonList(p), live, 2_000L, GRACE);

        assertFalse(contains(r.merged, 383));
        assertTrue(r.evict.contains(383));
    }
}
