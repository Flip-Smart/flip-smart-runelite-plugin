package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Singleton;

/**
 * Zero-crossing round-trip id ledger, keyed by (rsn, itemId).
 *
 * Tracks the running held quantity the plugin has observed (buys minus sells) and
 * assigns a monotonic id to every fill between two zero-crossings, so the backend can
 * match a sell to buys strictly within one round trip instead of across unrelated
 * same-item positions. A full or over liquidation (held quantity returns to zero or
 * below) closes the current cycle; whatever follows starts a new one.
 *
 * The cycle id is deliberately item-scoped (not slot-scoped) so a round trip that spans
 * several GE slots still matches. Slot is used only as a guard: a duplicate/phantom fill
 * that repeats the exact fill which just closed a cycle is ignored so it cannot prematurely
 * advance the cycle and poison the round-trip id of every later real fill.
 */
@Singleton
public final class RoundTripLedger
{
    private static final int INITIAL_CYCLE_ID = 1;

    /** Per-(rsn, itemId) state: quantity currently held and the active cycle id. */
    public static final class Entry
    {
        public int heldQuantity;
        public int cycleId;

        /**
         * Fingerprint (slot:isBuy:cumulative) of the fill that most recently closed a cycle,
         * or null when the current position is open. Session-local guard state — {@code transient}
         * so persistence keeps the same on-disk shape (held + cycle only) as before this field.
         */
        public transient String lastClosingFingerprint;

        public Entry()
        {
        }

        public Entry(int heldQuantity, int cycleId)
        {
            this.heldQuantity = heldQuantity;
            this.cycleId = cycleId;
        }
    }

    private final Object lock = new Object();
    private final Map<String, Map<Integer, Entry>> byRsn = new HashMap<>();

    /**
     * Slot-unaware overload retained for round-trip accounting that has no GE slot to key on
     * (and for tests exercising pure zero-crossing semantics). Applies no duplicate-fill guard.
     */
    public Integer recordFill(String rsn, int itemId, boolean isBuy, int deltaQuantity)
    {
        return recordFill(rsn, itemId, null, isBuy, deltaQuantity, 0);
    }

    /**
     * Apply a fill of {@code deltaQuantity} for (rsn, itemId) and return the round-trip id it
     * belongs to. Returns {@code null} when {@code rsn} can't be resolved — the caller sends no
     * id in that case rather than guess.
     *
     * The cycle id stays item-scoped so the backend can still match a sell to buys of the same
     * item across whichever GE slots the round trip happened to use. {@code slot} and
     * {@code cumulativeQuantity} add a slot-keyed guard: a fill byte-identical to the one that
     * just closed the current cycle — a duplicate or phantom re-delivery, arriving with no
     * genuine fill in between — is ignored, so it cannot drive held below zero and advance the
     * cycle a second time. That premature zero-cross is what stamps a wrong round-trip id on
     * every subsequent real fill; the server-side watermark can only dedup, it cannot un-poison
     * an already-sent id. Passing a null {@code slot} disables the guard (see the overload).
     */
    public Integer recordFill(String rsn, int itemId, Integer slot, boolean isBuy,
                              int deltaQuantity, int cumulativeQuantity)
    {
        synchronized (lock)
        {
            if (rsn == null || rsn.isEmpty())
            {
                return null;
            }
            Entry e = entryFor(rsn, itemId);

            String fingerprint = slot == null ? null : slot + ":" + isBuy + ":" + cumulativeQuantity;
            if (fingerprint != null && fingerprint.equals(e.lastClosingFingerprint))
            {
                return e.cycleId;
            }

            e.heldQuantity += isBuy ? deltaQuantity : -deltaQuantity;
            int roundTripId = e.cycleId;
            if (e.heldQuantity <= 0)
            {
                e.cycleId++;
                e.heldQuantity = 0;
                e.lastClosingFingerprint = fingerprint;
            }
            else
            {
                e.lastClosingFingerprint = null;
            }
            return roundTripId;
        }
    }

    /**
     * The current round-trip id for (rsn, itemId) without mutating held quantity. Used to
     * stamp zero-fill placement rows so they carry the same id their eventual fills will.
     */
    public Integer peekRoundTripId(String rsn, int itemId)
    {
        synchronized (lock)
        {
            if (rsn == null || rsn.isEmpty())
            {
                return null;
            }
            return entryFor(rsn, itemId).cycleId;
        }
    }

    private Entry entryFor(String rsn, int itemId)
    {
        return byRsn.computeIfAbsent(rsn, k -> new HashMap<>())
            .computeIfAbsent(itemId, k -> new Entry(0, INITIAL_CYCLE_ID));
    }

    /**
     * Snapshot of every itemId -> Entry tracked for {@code rsn}, for persistence. Entries are
     * deep-copied so a later mutation via {@link #recordFill} can't race with serialization of
     * this snapshot on another thread.
     */
    public Map<Integer, Entry> export(String rsn)
    {
        synchronized (lock)
        {
            Map<Integer, Entry> existing = byRsn.get(rsn);
            if (existing == null)
            {
                return Collections.emptyMap();
            }
            Map<Integer, Entry> copy = new HashMap<>();
            for (Map.Entry<Integer, Entry> entry : existing.entrySet())
            {
                Entry source = entry.getValue();
                copy.put(entry.getKey(), new Entry(source.heldQuantity, source.cycleId));
            }
            return copy;
        }
    }

    /** True when no state has ever been recorded for {@code rsn} (cold start / first run). */
    public boolean isEmpty(String rsn)
    {
        synchronized (lock)
        {
            return rsn == null || !byRsn.containsKey(rsn);
        }
    }

    /** Replace all state for {@code rsn} with {@code entries} (e.g. restored from persistence). */
    public void importState(String rsn, Map<Integer, Entry> entries)
    {
        synchronized (lock)
        {
            if (rsn == null || rsn.isEmpty() || entries == null || entries.isEmpty())
            {
                return;
            }
            byRsn.put(rsn, new HashMap<>(entries));
        }
    }

    /**
     * Conservative cold-start seed, applied only when {@code rsn} has no ledger state at
     * all — a genuine first run, e.g. this feature shipping to an account that already has
     * an open GE position. Seeds held quantity per item from currently-live unmatched buy
     * fills so the next sell of that pre-existing position doesn't collide with a fresh
     * cycle that has never seen it.
     */
    public void seedColdStart(String rsn, List<OfferRecord> liveUnmatchedBuys)
    {
        synchronized (lock)
        {
            if (rsn == null || rsn.isEmpty() || !isEmpty(rsn) || liveUnmatchedBuys == null || liveUnmatchedBuys.isEmpty())
            {
                return;
            }
            Map<Integer, Entry> seeded = seedMapFrom(liveUnmatchedBuys);
            if (!seeded.isEmpty())
            {
                byRsn.put(rsn, seeded);
            }
        }
    }

    private static Map<Integer, Entry> seedMapFrom(List<OfferRecord> liveUnmatchedBuys)
    {
        Map<Integer, Entry> seeded = new HashMap<>();
        for (OfferRecord r : liveUnmatchedBuys)
        {
            if (!r.isBuy() || r.getFilledQuantity() <= 0)
            {
                continue;
            }
            seeded.computeIfAbsent(r.getItemId(), k -> new Entry(0, INITIAL_CYCLE_ID)).heldQuantity += r.getFilledQuantity();
        }
        return seeded;
    }
}
