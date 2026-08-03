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

    /**
     * A non-terminal record no live slot claims is an offer that completed while we were not
     * watching. There is deliberately no timestamp gate on that: a record's last-activity is when
     * the plugin last <em>observed</em> a change, and an offline fill is by definition a change it
     * did not observe, so the timestamp can only ever say "older than the last sync". Whether a
     * record has already been offered for backfill is answered by terminalising it once offered,
     * not by comparing clocks.
     */
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
            if (r.getState().isTerminal())
            {
                continue;
            }
            plan.offlineCollected.add(r);
        }
        return plan;
    }

    private static boolean matches(OfferRecord p, OfferSignal live)
    {
        // Identity is the live slot's (slot, item, direction) — NOT its mutable
        // totalQuantity/price. The order's total and price legitimately drift
        // (partial buys re-read a different total, a relist changes price), and
        // requiring them to be equal made a still-live record fail to reattach:
        // a fresh baseline-0 record then re-posted the whole cumulative on top of
        // fills already recorded, a ~40% buy over-count. Terminal
        // records are finished history — a live slot showing their item is a
        // reused slot holding a new offer, so they never reattach.
        return !p.getState().isTerminal()
            && p.getSlot() != null
            && p.getSlot() == live.slot
            && p.getItemId() == live.itemId
            && p.isBuy() == live.isBuy();
    }
}
