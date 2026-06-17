package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.offer.OfferTransition;

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

            long idForNew = current == null ? nextOfferId : current.getOfferId();
            t = OfferStateMachine.decide(current, signal, idForNew, now);

            if (t.kind == OfferTransition.Kind.REJECTED || t.kind == OfferTransition.Kind.NONE || t.record == null)
            {
                return t;
            }

            if (current == null)
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

            event = new OfferEvent(t.kind, t.record, t.newlyFilledQuantity);
            snapshot = new ArrayList<>(listeners);
        }

        for (Consumer<OfferEvent> l : snapshot)
        {
            l.accept(event);
        }
        return t;
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
     * rebuilding the slot index for live records and re-seeding the id counter
     * above the largest imported offerId so subsequent offers cannot collide.
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
        nextOfferId = maxId + 1;
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
