package com.flipsmart.domain.flip;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Re-adds locally-collected flips the backend has not yet reported, so a coalesced backend
 * refresh cannot flicker a just-collected item out of the Active Flips list. A pending flip
 * is appended only while it is absent from the backend list, still backed by live player
 * state, and within the grace window; confirmed, expired, or no-longer-live pending flips
 * are evicted instead.
 */
public final class ActiveFlipReconcileMerge
{
    private ActiveFlipReconcileMerge()
    {
    }

    /** A locally-added flip awaiting backend confirmation. */
    public static final class Pending
    {
        public final int itemId;
        public final long addedAtMillis;
        public final ActiveFlip flip;

        public Pending(int itemId, long addedAtMillis, ActiveFlip flip)
        {
            this.itemId = itemId;
            this.addedAtMillis = addedAtMillis;
            this.flip = flip;
        }
    }

    /** Merged list to display, plus the item ids to drop from the pending map. */
    public static final class Result
    {
        public final List<ActiveFlip> merged;
        public final Set<Integer> evict;

        public Result(List<ActiveFlip> merged, Set<Integer> evict)
        {
            this.merged = merged;
            this.evict = evict;
        }
    }

    public static Result merge(List<ActiveFlip> backendFiltered, Collection<Pending> pending,
        Set<Integer> liveItemIds, long nowMillis, long graceMillis)
    {
        List<ActiveFlip> merged = new ArrayList<>(backendFiltered);
        Set<Integer> backendIds = new HashSet<>();
        for (ActiveFlip f : backendFiltered)
        {
            backendIds.add(f.getItemId());
        }

        Set<Integer> evict = new HashSet<>();
        for (Pending p : pending)
        {
            boolean confirmed = backendIds.contains(p.itemId);
            boolean expired = nowMillis - p.addedAtMillis > graceMillis;
            boolean stillLive = liveItemIds != null && liveItemIds.contains(p.itemId);
            if (confirmed || expired || !stillLive)
            {
                evict.add(p.itemId);
            }
            else
            {
                merged.add(p.flip);
            }
        }
        return new Result(merged, evict);
    }
}
