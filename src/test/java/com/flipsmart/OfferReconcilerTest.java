package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferReconciler;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OfferReconcilerTest
{
    private static final long NOW = 5_000L;

    private static OfferSignal live(int slot, int itemId, int sold, int total)
    {
        return new OfferSignal(slot, GrandExchangeOfferState.BUYING, itemId, "i" + itemId, total, 100, sold, (long) sold * 100);
    }

    @Test
    public void liveSlotMatchingPersisted_reattachesSameOfferId()
    {
        OfferRecord persisted = OfferRecord.newOffer(7L, 0, 1234, "i1234", true, 10, 100, NOW - 1000);
        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(persisted),
            Collections.singletonList(live(0, 1234, 3, 10)), NOW);

        assertEquals(1, plan.reattached.size());
        assertEquals(7L, plan.reattached.get(0).getOfferId());
        assertTrue(plan.minted.isEmpty());
    }

    @Test
    public void liveSlotWithNoMatch_mintsWithBaselineFromLiveQuantity()
    {
        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.emptyList(),
            Collections.singletonList(live(2, 5678, 4, 10)), NOW);

        assertEquals(1, plan.minted.size());
        assertEquals("baseline seeded so a total fill isn't logged as new",
            4, plan.minted.get(0).quantitySold);
        assertTrue(plan.reattached.isEmpty());
    }

    @Test
    public void persistedRecordOnEmptySlot_isMarkedForOfflineReconcile()
    {
        OfferRecord persisted = OfferRecord.newOffer(9L, 1, 4321, "i4321", true, 10, 100, NOW - 2000);
        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(persisted),
            Collections.emptyList(), NOW);

        assertEquals(1, plan.offlineCollected.size());
        assertEquals(9L, plan.offlineCollected.get(0).getOfferId());
    }

    @Test
    public void terminalPersistedRecord_isNotOfflineCollected()
    {
        // A COLLECTED record is already-reconciled history from a prior session;
        // it must not be re-flagged as a fresh offline fill on every login.
        OfferRecord collected = OfferRecord.newOffer(12L, 4, 555, "i555", true, 10, 100, NOW - 3000)
            .withFill(10, 1000, OfferState.FILLED, NOW - 2500)
            .withState(OfferState.COLLECTED, NOW - 2000);

        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(collected),
            Collections.emptyList(), NOW);

        assertTrue("terminal history must not drive an offline-fill prompt", plan.offlineCollected.isEmpty());
    }

    @Test
    public void mixedHistory_onlyActiveNowGoneRecordIsOfflineCollected()
    {
        OfferRecord active = OfferRecord.newOffer(20L, 1, 100, "i100", true, 10, 100, NOW - 2000);
        OfferRecord collected = OfferRecord.newOffer(21L, 2, 200, "i200", false, 5, 50, NOW - 3000)
            .withFill(5, 250, OfferState.FILLED, NOW - 2800)
            .withState(OfferState.COLLECTED, NOW - 2700);
        OfferRecord cancelledEmpty = OfferRecord.newOffer(22L, 3, 300, "i300", true, 8, 80, NOW - 3000)
            .withState(OfferState.CANCELLED_EMPTY, NOW - 2600);

        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            java.util.Arrays.asList(active, collected, cancelledEmpty),
            Collections.emptyList(), NOW);

        assertEquals(1, plan.offlineCollected.size());
        assertEquals(20L, plan.offlineCollected.get(0).getOfferId());
    }

    @Test
    public void ageDoesNotDisqualifyAnOfflineFill()
    {
        // #1197: reconcile used to demand that a record's last activity be at or after the
        // previous sync marker. That test can never pass for a real offline fill — last-activity
        // is when the plugin last OBSERVED a change, and an offline fill is by definition a change
        // it did not observe, so the timestamp always trails a marker written on every sync.
        // Observed live: lastActivity=19:45:19 against a marker of 19:45:20, and the prompt fired
        // zero times across nine logins.
        OfferRecord ancient = OfferRecord.newOffer(30L, 1, 400, "i400", false, 3, 100, NOW - 999_999)
            .withActivityAtMillis(NOW - 999_999);

        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(ancient), Collections.emptyList(), NOW);

        assertEquals("an unmatched non-terminal record is an offline fill regardless of age",
            1, plan.offlineCollected.size());
        assertEquals(30L, plan.offlineCollected.get(0).getOfferId());
    }

    @Test
    public void terminalRecordIsNeverOfferedAgain()
    {
        // What replaces the freshness cutoff: offering a record terminalises it, and the
        // reconciler skips terminal records outright. That is what stops a re-prompt each login.
        OfferRecord collected = OfferRecord.newOffer(33L, 1, 700, "i700", false, 3, 100, NOW - 1_000)
            .withState(OfferState.COLLECTED, NOW - 500);

        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(collected), Collections.emptyList(), NOW);

        assertTrue("already-offered record must not be offered again", plan.offlineCollected.isEmpty());
    }

    @Test
    public void liveSlotMatchesDespiteTotalQuantityAndPriceDrift_reattachesNotMints()
    {
        // #1089 D1: the buy order's total_quantity drifted (7500 -> 11584) and its
        // price nudged between persistence and the live read. The old exact-match
        // on total+price then failed to reattach, so a fresh baseline-0 record
        // re-posted the whole cumulative on top of fills already recorded — a ~40%
        // over-count seen across 1,061 prod users. Identity is the live slot's
        // (slot, item, direction), not its mutable total/price.
        OfferRecord persisted = OfferRecord.newOffer(7L, 0, 536, "Dragon bones", true, 7500, 3284, NOW - 1000)
            .withFill(3027, 3027L * 3284, OfferState.PARTIAL_FILL, NOW - 500);
        OfferSignal drifted = new OfferSignal(
            0, GrandExchangeOfferState.BUYING, 536, "Dragon bones", 11584, 3290, 4279, 4279L * 3284);

        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(persisted), Collections.singletonList(drifted), NOW);

        assertEquals("must reattach despite total/price drift", 1, plan.reattached.size());
        assertEquals(7L, plan.reattached.get(0).getOfferId());
        assertEquals("recorded baseline preserved so the next fill logs only the delta",
            3027, plan.reattached.get(0).getFilledQuantity());
        assertTrue("no fresh baseline-0 record minted", plan.minted.isEmpty());
    }

    @Test
    public void terminalRecordSameSlotAndItem_isNotReattached_mintsFresh()
    {
        // Loosening the match must not grab a COLLECTED leftover occupying the same
        // slot+item: that is a reused slot holding a genuinely new offer, so it must
        // mint fresh rather than resurrect finished history.
        OfferRecord collected = OfferRecord.newOffer(9L, 0, 536, "Dragon bones", true, 7500, 3284, NOW - 3000)
            .withFill(7500, 7500L * 3284, OfferState.FILLED, NOW - 2500)
            .withState(OfferState.COLLECTED, NOW - 2000);

        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(collected), Collections.singletonList(live(0, 536, 100, 7500)), NOW);

        assertTrue("terminal record must not reattach to a live slot", plan.reattached.isEmpty());
        assertEquals(1, plan.minted.size());
    }
}
