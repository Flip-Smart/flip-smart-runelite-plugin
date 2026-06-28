package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;

import java.util.ArrayList;
import java.util.List;

/** Pure reconciliation of persisted offers against the live GE slots on login/reload. */
public final class OfferReconciler
{
    private OfferReconciler() {}

    public static final class Plan
    {
        public final List<OfferRecord> reattached = new ArrayList<>();
        public final List<OfferSignal> minted = new ArrayList<>();
        public final List<OfferRecord> offlineCollected = new ArrayList<>();
        // Non-terminal records last active before the freshness cutoff: leftovers from older
        // sessions (already known to the backend). Terminalize them, but never prompt.
        public final List<OfferRecord> staleHistory = new ArrayList<>();
    }

    public static Plan reconcile(List<OfferRecord> persisted, List<OfferSignal> liveSlots, long now)
    {
        // No freshness gate: every non-terminal unmatched record is treated as offline-collected.
        // Used by the preload pass, which terminalizes both buckets regardless.
        return reconcile(persisted, liveSlots, now, Long.MIN_VALUE);
    }

    /**
     * @param freshnessThresholdMillis a non-terminal unmatched record counts as a genuine offline
     *     fill only if its last activity is at or after this cutoff; older records are leftovers
     *     from prior sessions and go to {@link Plan#staleHistory} instead of driving a prompt.
     */
    public static Plan reconcile(List<OfferRecord> persisted, List<OfferSignal> liveSlots, long now,
        long freshnessThresholdMillis)
    {
        Plan plan = new Plan();
        List<OfferRecord> remaining = new ArrayList<>(persisted);

        for (OfferSignal live : liveSlots)
        {
            OfferRecord match = null;
            for (OfferRecord p : remaining)
            {
                if (matches(p, live))
                {
                    match = p;
                    break;
                }
            }
            if (match != null)
            {
                remaining.remove(match);
                plan.reattached.add(match.withSlot(live.slot));
            }
            else
            {
                plan.minted.add(live);
            }
        }
        // Terminal (collected/cancelled-empty) records are already-reconciled history, not
        // offline fills — including them re-flagged the whole backlog every login (false nag).
        for (OfferRecord r : remaining)
        {
            if (r.getState().isTerminal())
            {
                continue;
            }
            if (r.getEffectiveLastActivityAtMillis() < freshnessThresholdMillis)
            {
                plan.staleHistory.add(r);
            }
            else
            {
                plan.offlineCollected.add(r);
            }
        }
        return plan;
    }

    private static boolean matches(OfferRecord p, OfferSignal live)
    {
        return p.getSlot() != null
            && p.getSlot() == live.slot
            && p.getItemId() == live.itemId
            && p.isBuy() == live.isBuy()
            && p.getTotalQuantity() == live.totalQuantity
            && p.getPrice() == live.price;
    }
}
