package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.offer.OfferTransition;

import net.runelite.api.GrandExchangeOfferState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.inject.Singleton;


/**
 * Single source of truth for offer state. Keyed by a monotonic offerId; indexed by slot
 * (event resolution) and item (consumer queries). The sole writer of offer state.
 * Thread-safe: every compound mutation runs under this monitor; reads return snapshots.
 * Listeners are notified after the monitor is released so a slow listener cannot stall readers.
 */
@Singleton
public final class OfferStore
{
    private final Map<Long, OfferRecord> byOfferId = new HashMap<>();
    private final Map<Integer, Long> slotToOfferId = new HashMap<>();   // 0..7 -> offerId (live only)
    private long nextOfferId = 1;
    private final List<Consumer<OfferEvent>> listeners = new ArrayList<>();
    private final FillWatermarks fillWatermarks = new FillWatermarks();

    /** The high-water fill marks backing every reported delta. Persisted alongside the records. */
    public FillWatermarks watermarks()
    {
        return fillWatermarks;
    }

    /** The id the next minted offer will take. Persisted so pruning cannot recycle ids. */
    public synchronized long nextOfferId()
    {
        return nextOfferId;
    }

    /**
     * Raise the counter to at least {@code candidate}, e.g. from a persisted high-water mark.
     * Never lowers it, so restoring a stale value cannot reintroduce a collision.
     */
    public synchronized void raiseNextOfferId(long candidate)
    {
        nextOfferId = Math.max(nextOfferId, candidate);
    }

    /** Register a listener to receive an {@link OfferEvent} after each successful state change. */
    public synchronized void addListener(Consumer<OfferEvent> listener)
    {
        listeners.add(listener);
    }

    /**
     * Apply {@code signal} to the current offer for its slot, delegating to
     * {@link OfferStateMachine#decide decide()} for the transition. Notifies listeners after
     * the monitor is released. Returns the transition (including REJECTED/NONE) for the caller.
     */
    public OfferTransition apply(OfferSignal signal, long now)
    {
        OfferTransition t;
        OfferEvent event;
        List<Consumer<OfferEvent>> snapshot;
        synchronized (this)
        {
            Long currentId = slotToOfferId.get(signal.slot);
            OfferRecord current = currentId == null ? null : byOfferId.get(currentId);

            // A live buy/sell signal only belongs to the slot's record if it is the SAME order:
            // same item and same direction. A different item or side means the slot turned over
            // without us observing the collect (an offline/mobile collect, or a missed EMPTY),
            // so the record still sitting in the slot is stale. Feeding the signal into it would
            // stamp the new offer's fills — and its price — onto the old item. Evict the stale
            // occupant here so the transition below mints a fresh record for the new order.
            // Only live directional states trigger this: EMPTY and CANCELLED legitimately target
            // the existing record (collect terminalises it; a cancel finalises its residual).
            boolean staleOccupant = current != null
                && !current.getState().isTerminal()
                && isLiveDirectional(signal.geState)
                && (current.getItemId() != signal.itemId || current.isBuy() != signal.isBuy());

            if (staleOccupant)
            {
                byOfferId.put(current.getOfferId(), current.withSlot(null));
                slotToOfferId.remove(signal.slot);
            }

            OfferRecord effectiveCurrent = staleOccupant ? null : current;

            long idForNew = effectiveCurrent == null ? nextOfferId : effectiveCurrent.getOfferId();
            t = OfferStateMachine.decide(effectiveCurrent, signal, idForNew, now);

            if (t.kind == OfferTransition.Kind.REJECTED || t.kind == OfferTransition.Kind.NONE || t.record == null)
            {
                return t;
            }

            if (effectiveCurrent == null)
            {
                nextOfferId = Math.max(nextOfferId, t.record.getOfferId() + 1);
            }

            byOfferId.put(t.record.getOfferId(), t.record);
            if (t.record.getSlot() == null)
            {
                slotToOfferId.remove(signal.slot);
            }
            else
            {
                slotToOfferId.put(signal.slot, t.record.getOfferId());
            }

            // What the signal newly revealed, measured against the highest cumulative ever seen
            // for this order rather than against the record. The record can be rewound by a
            // reconcile against a stale snapshot; the mark cannot. Direction comes from the
            // record because a collect arrives as EMPTY, which reads as neither buy nor sell.
            int generation = fillWatermarks.generationFor(signal.slot);
            OfferIdentity identity = OfferIdentity.of(
                signal.slot, t.record.getItemId(), t.record.isBuy(), generation);
            FillWatermarks.Delta delta = fillWatermarks.observe(identity, signal.quantitySold, signal.spent);

            if (t.record.getState().isTerminal())
            {
                fillWatermarks.advanceGeneration(signal.slot);
            }

            event = new OfferEvent(t.kind, t.record, delta.quantity, delta.spent, generation);
            snapshot = new ArrayList<>(listeners);
        }

        for (Consumer<OfferEvent> l : snapshot)
        {
            l.accept(event);
        }
        return t;
    }

    /**
     * A GE state that reports a live, directional offer (a placement or a fill), as opposed to
     * EMPTY (collected/absent) or a cancellation. Only these carry an item and side that must
     * match the slot's tracked order.
     */
    private static boolean isLiveDirectional(GrandExchangeOfferState state)
    {
        return state == GrandExchangeOfferState.BUYING
            || state == GrandExchangeOfferState.SELLING
            || state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.SOLD;
    }

    /** Live offer currently occupying {@code slot} (0–7), or {@code null} if the slot is empty. */
    public synchronized OfferRecord bySlot(int slot)
    {
        Long id = slotToOfferId.get(slot);
        return id == null ? null : byOfferId.get(id);
    }

    /** All records (live and terminal) for {@code itemId}, as an unmodifiable snapshot. */
    public synchronized List<OfferRecord> forItem(int itemId)
    {
        List<OfferRecord> out = new ArrayList<>();
        for (OfferRecord r : byOfferId.values())
        {
            if (r.getItemId() == itemId)
            {
                out.add(r);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** All records known to the store (live and terminal), as an unmodifiable snapshot. */
    public synchronized List<OfferRecord> allRecords()
    {
        return Collections.unmodifiableList(new ArrayList<>(byOfferId.values()));
    }

    /** Snapshot of every record for persistence. */
    public synchronized List<OfferRecord> export()
    {
        return new ArrayList<>(byOfferId.values());
    }

    /**
     * Replace all state with {@code records} (e.g. restored from persistence),
     * rebuilding the slot index for live records and raising the id counter
     * above the largest imported offerId so subsequent offers cannot collide.
     * The counter only ever moves forward: an import is routinely lossy — the
     * login reconcile drops terminal records past the retention window, and it
     * runs on every world hop, not just login — so assigning from the surviving
     * maximum would let the counter regress and hand a fresh offer an ID the backend
     * still holds fills under.
     */
    public synchronized void importRecords(List<OfferRecord> records)
    {
        byOfferId.clear();
        slotToOfferId.clear();
        long maxId = 0;
        for (OfferRecord r : records)
        {
            byOfferId.put(r.getOfferId(), r);
            if (r.getSlot() != null && !r.getState().isTerminal())
            {
                slotToOfferId.put(r.getSlot(), r.getOfferId());
            }
            maxId = Math.max(maxId, r.getOfferId());
        }
        nextOfferId = Math.max(nextOfferId, maxId + 1);
        // A record entering the store carries progress that has already been reported. Raising the
        // marks to match keeps the next observation measuring the increment rather than re-reporting
        // the whole cumulative.
        fillWatermarks.seedFrom(records);
    }

    /**
     * Records that still occupy a GE slot (state is non-terminal). Terminal
     * records (collected / cancelled-empty) are retained by the store but
     * excluded here, matching the live-only view the session offer map exposed.
     */
    public synchronized List<OfferRecord> liveOffers()
    {
        List<OfferRecord> out = new ArrayList<>();
        for (OfferRecord r : byOfferId.values())
        {
            if (!r.getState().isTerminal())
            {
                out.add(r);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * A sell offer for {@code itemId} is currently live (occupies a GE slot).
     * Terminal records (collected / cancelled-empty) are ignored so a finished
     * lifecycle never reports as an active slot.
     */
    public synchronized boolean hasActiveSellOfferForItem(int itemId)
    {
        for (OfferRecord r : byOfferId.values())
        {
            if (r.getItemId() == itemId && !r.isBuy() && !r.getState().isTerminal())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * A buy offer for {@code itemId} is live — either still filling or filled but
     * not yet collected. Mirrors the union of the session's in-flight and
     * uncollected buy predicates: any non-terminal buy record qualifies, since a
     * collected buy is terminal and a freshly-collected slot is freed.
     */
    public synchronized boolean hasLiveBuyOfferForItem(int itemId)
    {
        for (OfferRecord r : byOfferId.values())
        {
            if (r.getItemId() == itemId && r.isBuy() && !r.getState().isTerminal())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Quantity already sold across live (non-terminal) sell offers for {@code itemId}.
     * Lets a consumer value only the unsold remainder of a partially-filled sell; terminal
     * records are excluded because their fills have left the open position entirely.
     */
    public int liveSellFilledQuantity(int itemId)
    {
        synchronized (this)
        {
            int sum = 0;
            for (OfferRecord r : byOfferId.values())
            {
                if (r.getItemId() == itemId && !r.isBuy() && !r.getState().isTerminal())
                {
                    sum += r.getFilledQuantity();
                }
            }
            return sum;
        }
    }

    /**
     * Replace the createdAt timestamp of the record with {@code offerId}, preserving
     * its slot index. No-op when the offerId is unknown. Used to backfill a missing
     * placement time from an authoritative external source (e.g. backend active flips).
     */
    public synchronized void correctCreatedAt(long offerId, long millis)
    {
        OfferRecord current = byOfferId.get(offerId);
        if (current == null)
        {
            return;
        }
        byOfferId.put(offerId, current.withCreatedAtMillis(millis));
    }

    /**
     * Replace the last-activity timestamp of the record with {@code offerId}. No-op when
     * the offerId is unknown. Used on relog to restore an offer's true age after the live
     * record was re-anchored to login time.
     */
    public synchronized void correctActivityAt(long offerId, long millis)
    {
        OfferRecord current = byOfferId.get(offerId);
        if (current == null)
        {
            return;
        }
        byOfferId.put(offerId, current.withActivityAtMillis(millis));
    }

    /**
     * Records with collectable fills awaiting a GE collect action: fully filled
     * (FILLED) or partially-cancelled (CANCELLED_PARTIAL). Matches the prior
     * getCompletedOffers behaviour so the "collect profit" prompt fires for both.
     */
    public synchronized List<OfferRecord> completedAwaitingCollection()
    {
        List<OfferRecord> out = new ArrayList<>();
        for (OfferRecord r : byOfferId.values())
        {
            if (r.getState() == OfferState.FILLED || r.getState() == OfferState.CANCELLED_PARTIAL)
            {
                out.add(r);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
