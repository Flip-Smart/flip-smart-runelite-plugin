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

    /** Per-(rsn, itemId) state: quantity currently held, the active cycle id, and the cumulative
     *  already absorbed for the current offer on each GE slot. */
    public static final class Entry
    {
        public int heldQuantity;
        public int cycleId;

        /**
         * Buy-side quantity and outlay accumulated for the CURRENT cycle, zeroed when it closes.
         * Scoping them to the cycle is what keeps a cost basis from averaging across positions the
         * player has already fully liquidated.
         */
        public int boughtQuantity;
        public long boughtSpent;

        /**
         * Cumulative filled quantity already folded into {@code heldQuantity} for the offer currently
         * on each (slot, direction), keyed by {@code slot * 2 + (isBuy ? 1 : 0)}. PERSISTED: on relog
         * the login reconciliation re-feeds an open offer's full current cumulative, and matching it
         * against the persisted absorbed cumulative lets the ledger recognise it as already-absorbed
         * and apply a zero delta instead of double-counting. Deliberately offer-id-AGNOSTIC — a relog
         * mints a fresh offer_id for the same slot, so keying on offer_id would miss the re-feed.
         * Direction is part of the key because a buy and a sell reuse the same slot with independent
         * cumulatives. A slot's entry is reset when that slot's own offer completes (not on cycle
         * close), so a still-filling sibling slot keeps its baseline. Lazily created so old persisted
         * blobs deserialize.
         */
        public Map<Integer, Integer> absorbedBySlotDir;

        /**
         * Fingerprint (slot:isBuy:cumulative) of the fill that most recently closed a cycle, or null
         * when the current position is open. Session-local ({@code transient}): it catches a
         * re-delivery of the closing fill even after that slot's {@link #absorbedBySlotDir} baseline
         * was reset on offer completion — a Collect re-detect. Direction keeps a genuine new lot, which
         * opens the next cycle in the opposite direction, from being falsely suppressed.
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

    /** Outcome of a guarded fill: the round-trip id, and whether the fill was a suppressed
     *  duplicate the caller should not forward. */
    public static final class FillResult
    {
        public final Integer roundTripId;
        public final boolean duplicateSuppressed;

        /**
         * Quantity actually folded into held, which is not always what the caller passed — a
         * slot-keyed fill rebaselines against what that slot has already absorbed. Cost basis
         * must follow this number, or the money would describe a different number of items.
         */
        public final int foldedQuantity;

        public FillResult(Integer roundTripId, boolean duplicateSuppressed, int foldedQuantity)
        {
            this.roundTripId = roundTripId;
            this.duplicateSuppressed = duplicateSuppressed;
            this.foldedQuantity = foldedQuantity;
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
        return recordFill(rsn, itemId, null, isBuy, deltaQuantity, 0, false);
    }

    /**
     * Apply a fill for (rsn, itemId) and return the round-trip id it belongs to. Returns {@code null}
     * when {@code rsn} can't be resolved — the caller sends no id in that case rather than guess.
     *
     * The cycle id stays item-scoped so the backend can still match a sell to buys of the same item
     * across whichever GE slots the round trip happened to use. For a slot-keyed fill the quantity
     * folded into held is derived from {@code cumulativeQuantity} against what's already absorbed for
     * that {@code (slot, direction)}, so a re-fed or duplicate cumulative is idempotent (see
     * {@link #recordFillGuarded}). Passing a null {@code slot} keeps the legacy delta-based path.
     */
    public Integer recordFill(String rsn, int itemId, Integer slot, boolean isBuy,
                              int deltaQuantity, int cumulativeQuantity, boolean offerComplete)
    {
        return recordFillGuarded(rsn, itemId, slot, isBuy, deltaQuantity, cumulativeQuantity, offerComplete).roundTripId;
    }

    /**
     * As {@link #recordFill(String, int, Integer, boolean, int, int, boolean)} but also reports whether the
     * fill was recognised as an already-absorbed re-delivery and suppressed.
     *
     * <p>For a slot-keyed fill the delta folded into {@code heldQuantity} is derived from the offer's
     * {@code cumulativeQuantity} against the cumulative already absorbed for that {@code (slot,
     * direction)} — NOT from the caller's {@code deltaQuantity}. This makes held idempotent to a
     * re-fed cumulative: on relog the login reconciliation re-feeds an open offer's full current
     * cumulative (the rebuilt OfferStore has no baseline, so the GE signal looks like one big fresh
     * fill), and without this the ledger would add it on top of the already-restored held, permanently
     * biasing held so it never returns to zero, freezing the round-trip cycle and consolidating
     * unrelated same-item positions. A cumulative equal to what's already absorbed is a re-feed and is
     * suppressed; a lower cumulative is a fresh offer reusing the slot and rebaselines to it.
     *
     * <p>Passing a null {@code slot} keeps the legacy delta-based path (no cumulative tracking) for
     * round-trip accounting that has no GE slot to key on and for tests exercising pure zero-crossing.
     */
    public FillResult recordFillGuarded(String rsn, int itemId, Integer slot, boolean isBuy,
                                        int deltaQuantity, int cumulativeQuantity, boolean offerComplete)
    {
        synchronized (lock)
        {
            if (rsn == null || rsn.isEmpty())
            {
                return new FillResult(null, false, 0);
            }
            Entry e = entryFor(rsn, itemId);

            // Guard for a re-delivery of the fill that just CLOSED the cycle after the absorbed map was
            // cleared on close — a Collect re-detect. Direction keeps a genuine opposite-side new lot
            // from being suppressed.
            String fingerprint = slot == null ? null : slot + ":" + isBuy + ":" + cumulativeQuantity;
            if (fingerprint != null && fingerprint.equals(e.lastClosingFingerprint))
            {
                return new FillResult(e.cycleId, true, 0);
            }

            int effectiveDelta;
            if (slot == null)
            {
                effectiveDelta = deltaQuantity;
            }
            else
            {
                if (e.absorbedBySlotDir == null)
                {
                    e.absorbedBySlotDir = new HashMap<>();
                }
                int key = slot * 2 + (isBuy ? 1 : 0);
                int absorbed = e.absorbedBySlotDir.getOrDefault(key, 0);
                if (cumulativeQuantity == absorbed)
                {
                    // Exactly the cumulative already folded in for this (slot, direction): a relog
                    // re-feed or a duplicate re-delivery. Fold nothing; tell the caller not to forward.
                    return new FillResult(e.cycleId, true, 0);
                }
                // A lower cumulative means a fresh offer reused the slot (fills restart at 0), so its
                // whole cumulative is new; a higher one is genuine incremental progress on this offer.
                effectiveDelta = cumulativeQuantity > absorbed ? cumulativeQuantity - absorbed : cumulativeQuantity;
                e.absorbedBySlotDir.put(key, cumulativeQuantity);
            }

            int heldBefore = e.heldQuantity;
            e.heldQuantity += isBuy ? effectiveDelta : -effectiveDelta;
            int roundTripId = e.cycleId;
            // Advance the cycle only on a genuine positive->zero crossing. If held was already zero — a
            // fully-liquidated position still receiving sell fills (overselling externally-sourced stock,
            // or a ledger drifted low) — don't re-close on every fill and run the cycle away.
            boolean closed = heldBefore > 0 && e.heldQuantity <= 0;
            if (e.heldQuantity <= 0)
            {
                e.heldQuantity = 0;
            }
            if (closed)
            {
                e.cycleId++;
                // The position is gone, so its cost basis goes with it — otherwise the next cycle
                // would inherit the average of stock that has already been sold.
                e.boughtQuantity = 0;
                e.boughtSpent = 0L;
                e.lastClosingFingerprint = fingerprint;
            }
            else
            {
                e.lastClosingFingerprint = null;
            }
            // Reset THIS slot's baselines once its own offer is done filling (COMPLETED / CANCELLED),
            // so the next offer reusing the slot rebaselines from zero even at a coincidentally-equal
            // cumulative. Sibling slots are deliberately left intact: a same-item offer still filling in
            // another slot must keep its baseline, or its next fill would re-count its whole cumulative
            // instead of just the increment when held momentarily crosses zero. Resetting on offer
            // completion rather than on cycle close is what distinguishes a finished slot from a
            // still-open sibling — the ledger has no per-slot open/closed view otherwise.
            if (offerComplete && slot != null && e.absorbedBySlotDir != null)
            {
                e.absorbedBySlotDir.remove(slot * 2);
                e.absorbedBySlotDir.remove(slot * 2 + 1);
            }
            return new FillResult(roundTripId, false, effectiveDelta);
        }
    }

    /**
     * Fold a buy's quantity and outlay into the current cycle's cost basis.
     *
     * <p>Takes a per-item price rather than a total so the money always corresponds to the
     * quantity actually folded in: {@link #recordFillGuarded} may rebaseline a fill's quantity
     * against what a slot has already absorbed, and a separately-supplied total would then
     * describe a different number of items than the one recorded.</p>
     *
     * <p>A non-positive price contributes nothing — a placement row carries no price, and folding
     * it in would drag the average toward zero.</p>
     */
    public void recordBuyBasis(String rsn, int itemId, int quantity, int pricePerItem)
    {
        if (rsn == null || rsn.isEmpty() || quantity <= 0 || pricePerItem <= 0)
        {
            return;
        }
        synchronized (lock)
        {
            Entry e = entryFor(rsn, itemId);
            e.boughtQuantity += quantity;
            e.boughtSpent += (long) quantity * pricePerItem;
        }
    }

    /**
     * Quantity-weighted average buy price for the OPEN cycle, or {@code null} when it has taken
     * no priced buys. Because a closed cycle's basis is cleared, this can never average across a
     * position the player has already liquidated.
     */
    public Integer currentBasis(String rsn, int itemId)
    {
        if (rsn == null || rsn.isEmpty())
        {
            return null;
        }
        synchronized (lock)
        {
            Entry e = entryFor(rsn, itemId);
            if (e.boughtQuantity <= 0)
            {
                return null;
            }
            return (int) Math.round(e.boughtSpent / (double) e.boughtQuantity);
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
                Entry clone = new Entry(source.heldQuantity, source.cycleId);
                if (source.absorbedBySlotDir != null)
                {
                    clone.absorbedBySlotDir = new HashMap<>(source.absorbedBySlotDir);
                }
                copy.put(entry.getKey(), clone);
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
