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
    public void staleNonTerminalRecord_olderThanFreshness_isHistoryNotOfflineCollected()
    {
        // A non-terminal record last active before the freshness cutoff is a leftover from an
        // older session (already known to the backend). It must not drive an offline-fill prompt;
        // it belongs to staleHistory so the caller can terminalize it without nagging.
        OfferRecord stale = OfferRecord.newOffer(30L, 1, 400, "i400", false, 3, 100, NOW - 10_000)
            .withActivityAtMillis(NOW - 10_000);
        long freshnessThreshold = NOW - 5_000;

        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(stale), Collections.emptyList(), NOW, freshnessThreshold);

        assertTrue("stale leftover must not be an offline fill", plan.offlineCollected.isEmpty());
        assertEquals(1, plan.staleHistory.size());
        assertEquals(30L, plan.staleHistory.get(0).getOfferId());
    }

    @Test
    public void recentNonTerminalRecord_newerThanFreshness_isOfflineCollected()
    {
        OfferRecord fresh = OfferRecord.newOffer(31L, 2, 500, "i500", false, 3, 100, NOW - 2_000)
            .withActivityAtMillis(NOW - 2_000);
        long freshnessThreshold = NOW - 5_000;

        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(fresh), Collections.emptyList(), NOW, freshnessThreshold);

        assertEquals(1, plan.offlineCollected.size());
        assertEquals(31L, plan.offlineCollected.get(0).getOfferId());
        assertTrue(plan.staleHistory.isEmpty());
    }

    @Test
    public void threeArgReconcile_appliesNoFreshnessGate()
    {
        // Back-compat: the 3-arg form (used by the preload pass, which terminalizes everything
        // anyway) keeps treating every non-terminal unmatched record as offline-collected.
        OfferRecord old = OfferRecord.newOffer(32L, 1, 600, "i600", false, 3, 100, NOW - 999_999)
            .withActivityAtMillis(NOW - 999_999);

        OfferReconciler.Plan plan = OfferReconciler.reconcile(
            Collections.singletonList(old), Collections.emptyList(), NOW);

        assertEquals(1, plan.offlineCollected.size());
        assertTrue(plan.staleHistory.isEmpty());
    }
}
