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
    }

    public static Plan reconcile(List<OfferRecord> persisted, List<OfferSignal> liveSlots, long now)
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
            if (!r.getState().isTerminal())
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
