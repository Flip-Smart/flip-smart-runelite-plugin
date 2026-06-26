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
}
