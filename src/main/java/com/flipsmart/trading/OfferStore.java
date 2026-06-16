package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
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

    public synchronized void addListener(Consumer<OfferEvent> listener)
    {
        listeners.add(listener);
    }

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

    public synchronized OfferRecord bySlot(int slot)
    {
        Long id = slotToOfferId.get(slot);
        return id == null ? null : byOfferId.get(id);
    }

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

    public synchronized List<OfferRecord> allRecords()
    {
        return Collections.unmodifiableList(new ArrayList<>(byOfferId.values()));
    }
}
