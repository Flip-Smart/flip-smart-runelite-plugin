package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OfferStoreTest
{
    private static final long NOW = 1_000L;

    private static OfferSignal sig(int slot, GrandExchangeOfferState s, int itemId, int sold, int total)
    {
        return new OfferSignal(slot, s, itemId, "i" + itemId, total, 100, sold, (long) sold * 100);
    }

    @Test
    public void newOffer_isStoredAndIndexedBySlotAndItem()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);

        OfferRecord bySlot = store.bySlot(0);
        assertEquals(1234, bySlot.getItemId());
        assertEquals(OfferState.NEW, bySlot.getState());
        assertEquals(1, store.forItem(1234).size());
    }

    @Test
    public void slotReuseWithDifferentItem_doesNotCollide()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 1234, 10, 10), NOW);
        store.apply(sig(0, GrandExchangeOfferState.EMPTY, 1234, 10, 10), NOW); // collected, slot freed
        store.apply(sig(0, GrandExchangeOfferState.SELLING, 5678, 0, 5), NOW); // reuse slot 0

        assertEquals(5678, store.bySlot(0).getItemId());
        assertEquals("two distinct offer lifecycles existed in slot 0",
            2, store.allRecords().size());
        assertNotEquals(store.forItem(1234).get(0).getOfferId(),
            store.forItem(5678).get(0).getOfferId());
    }

    @Test
    public void sameItemInTwoSlots_keepsTwoDistinctOffers()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        store.apply(sig(3, GrandExchangeOfferState.SELLING, 1234, 0, 10), NOW);

        assertEquals(2, store.forItem(1234).size());
    }

    @Test
    public void collected_clearsSlotIndexButRecordRemains()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 1234, 10, 10), NOW);
        store.apply(sig(0, GrandExchangeOfferState.EMPTY, 1234, 10, 10), NOW);

        assertNull("slot freed", store.bySlot(0));
        assertEquals(OfferState.COLLECTED, store.forItem(1234).get(0).getState());
    }

    @Test
    public void snapshotIsolation_heldListDoesNotMutate()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        List<OfferRecord> held = store.allRecords();
        int before = held.size();

        store.apply(sig(1, GrandExchangeOfferState.BUYING, 9999, 0, 10), NOW);

        assertEquals("previously-returned snapshot is immutable", before, held.size());
        assertTrue(store.allRecords().size() > before);
    }

    @Test
    public void hasActiveSellOfferForItem_trueForLiveSell_falseAfterCollect()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.SELLING, 1234, 0, 10), NOW);
        assertTrue(store.hasActiveSellOfferForItem(1234));

        store.apply(sig(0, GrandExchangeOfferState.SOLD, 1234, 10, 10), NOW);
        assertTrue("FILLED sell still occupies the slot", store.hasActiveSellOfferForItem(1234));

        store.apply(sig(0, GrandExchangeOfferState.EMPTY, 1234, 10, 10), NOW); // collected
        assertFalse("collected sell is terminal", store.hasActiveSellOfferForItem(1234));
    }

    @Test
    public void hasActiveSellOfferForItem_ignoresBuyOffers()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        assertFalse(store.hasActiveSellOfferForItem(1234));
    }

    @Test
    public void hasActiveSellOfferForItem_terminalRecordDoesNotMaskLiveSell()
    {
        OfferStore store = new OfferStore();
        // slot 0: complete the buy lifecycle so a terminal COLLECTED record exists for item 1234
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 5), NOW);
        store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 1234, 5, 5), NOW);
        store.apply(sig(0, GrandExchangeOfferState.EMPTY, 1234, 5, 5), NOW); // COLLECTED (terminal)
        // slot 1: open a live sell for the same item
        store.apply(sig(1, GrandExchangeOfferState.SELLING, 1234, 0, 5), NOW); // NEW sell
        assertTrue("live sell must be visible even when a terminal record for the same item exists",
            store.hasActiveSellOfferForItem(1234));
    }

    @Test
    public void hasLiveBuyOfferForItem_coversInFlightAndUncollected()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        assertTrue("in-flight buy", store.hasLiveBuyOfferForItem(1234));

        store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 1234, 10, 10), NOW);
        assertTrue("filled but uncollected buy", store.hasLiveBuyOfferForItem(1234));

        store.apply(sig(0, GrandExchangeOfferState.EMPTY, 1234, 10, 10), NOW); // collected
        assertFalse("collected buy is terminal", store.hasLiveBuyOfferForItem(1234));
    }

    @Test
    public void hasLiveBuyOfferForItem_ignoresSellOffers()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.SELLING, 1234, 0, 10), NOW);
        assertFalse(store.hasLiveBuyOfferForItem(1234));
    }

    @Test
    public void completedAwaitingCollection_listsFilledOnly()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW); // NEW
        store.apply(sig(1, GrandExchangeOfferState.BOUGHT, 5678, 10, 10), NOW); // FILLED
        assertEquals(1, store.completedAwaitingCollection().size());
        assertEquals(5678, store.completedAwaitingCollection().get(0).getItemId());

        store.apply(sig(1, GrandExchangeOfferState.EMPTY, 5678, 10, 10), NOW); // collected
        assertTrue("collected offer drops out of awaiting-collection",
            store.completedAwaitingCollection().isEmpty());
    }

    @Test
    public void completedAwaitingCollection_includesCancelledPartial()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 3, 10), NOW); // PARTIAL_FILL
        store.apply(sig(0, GrandExchangeOfferState.CANCELLED_BUY, 1234, 3, 10), NOW); // CANCELLED_PARTIAL
        assertEquals("cancelled-partial has collectable fills and must trigger collect prompt",
            1, store.completedAwaitingCollection().size());
        assertEquals(1234, store.completedAwaitingCollection().get(0).getItemId());
    }

    @Test
    public void completedAwaitingCollection_excludesInFlightOffers()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW); // NEW
        store.apply(sig(1, GrandExchangeOfferState.BUYING, 5678, 3, 10), NOW); // PARTIAL_FILL
        assertTrue("in-flight offers have nothing to collect yet",
            store.completedAwaitingCollection().isEmpty());
    }

    @Test
    public void liveOffers_excludesTerminalRecords()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW); // NEW (live)
        store.apply(sig(1, GrandExchangeOfferState.SELLING, 5678, 0, 5), NOW); // NEW sell (live)

        assertEquals("both offers are live", 2, store.liveOffers().size());

        // Complete and collect the buy → terminal COLLECTED
        store.apply(sig(0, GrandExchangeOfferState.BOUGHT, 1234, 10, 10), NOW); // FILLED (still live)
        assertEquals("filled-but-uncollected buy is still live", 2, store.liveOffers().size());

        store.apply(sig(0, GrandExchangeOfferState.EMPTY, 1234, 10, 10), NOW); // COLLECTED (terminal)
        List<OfferRecord> live = store.liveOffers();
        assertEquals("collected offer drops out of live view", 1, live.size());
        assertEquals(5678, live.get(0).getItemId());
    }

    @Test
    public void liveOffers_isEmptyWhenNoOffers()
    {
        OfferStore store = new OfferStore();
        assertTrue(store.liveOffers().isEmpty());
    }

    @Test
    public void offerIds_areMonotonicAndUnique()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1, 0, 1), NOW);
        store.apply(sig(1, GrandExchangeOfferState.BUYING, 2, 0, 1), NOW);

        long a = store.bySlot(0).getOfferId();
        long b = store.bySlot(1).getOfferId();
        assertTrue(b > a);
    }

    @Test
    public void exportImport_roundTripsRecordsAndRebuildsSlotIndex()
    {
        OfferStore source = new OfferStore();
        source.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        source.apply(sig(1, GrandExchangeOfferState.BOUGHT, 5678, 10, 10), NOW); // FILLED (live)
        source.apply(sig(2, GrandExchangeOfferState.BUYING, 4321, 0, 10), NOW);
        source.apply(sig(2, GrandExchangeOfferState.CANCELLED_BUY, 4321, 0, 10), NOW); // CANCELLED_EMPTY (terminal, slot freed)

        List<OfferRecord> exported = source.export();

        OfferStore target = new OfferStore();
        target.importRecords(exported);

        assertEquals(source.allRecords().size(), target.allRecords().size());
        assertEquals(1234, target.bySlot(0).getItemId());
        assertEquals(5678, target.bySlot(1).getItemId());
        assertNull("terminal record's slot is not re-indexed", target.bySlot(2));
        assertEquals("terminal record is still retained", 1, target.forItem(4321).size());
    }

    @Test
    public void importRecords_reseedsNextOfferIdAboveMax()
    {
        OfferStore source = new OfferStore();
        source.apply(sig(0, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        source.apply(sig(1, GrandExchangeOfferState.BUYING, 5678, 0, 10), NOW);
        long maxId = source.bySlot(1).getOfferId();

        OfferStore target = new OfferStore();
        target.importRecords(source.export());

        // A fresh offer placed after import must get a non-colliding id above the max.
        target.apply(sig(3, GrandExchangeOfferState.BUYING, 9999, 0, 10), NOW);
        long newId = target.bySlot(3).getOfferId();
        assertTrue("new id (" + newId + ") must exceed imported max (" + maxId + ")", newId > maxId);

        for (OfferRecord r : target.allRecords())
        {
            if (r.getItemId() != 9999)
            {
                assertNotEquals("imported ids are preserved, not reused", newId, r.getOfferId());
            }
        }
    }

    @Test
    public void correctCreatedAt_replacesTimestampPreservingSlotIndex()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(2, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        long offerId = store.bySlot(2).getOfferId();

        store.correctCreatedAt(offerId, 42L);

        OfferRecord corrected = store.bySlot(2);
        assertEquals("createdAt is replaced", 42L, corrected.getCreatedAtMillis());
        assertEquals("same offerId after correction", offerId, corrected.getOfferId());
        assertEquals("slot index preserved", Integer.valueOf(2), corrected.getSlot());
    }

    @Test
    public void correctCreatedAt_unknownOfferId_isNoOp()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(2, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        long before = store.bySlot(2).getCreatedAtMillis();

        store.correctCreatedAt(999_999L, 42L);

        assertEquals("no record changed for unknown id", before, store.bySlot(2).getCreatedAtMillis());
    }

    @Test
    public void correctActivityAt_restoresEffectiveLastActivity()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(2, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        long offerId = store.bySlot(2).getOfferId();
        assertEquals("freshly seen offer anchors activity to now", NOW,
            store.bySlot(2).getEffectiveLastActivityAtMillis());

        store.correctActivityAt(offerId, 42L);

        assertEquals("activity restored to the older value", 42L,
            store.bySlot(2).getEffectiveLastActivityAtMillis());
        assertEquals("slot index preserved", Integer.valueOf(2), store.bySlot(2).getSlot());
    }

    @Test
    public void correctActivityAt_unknownOfferId_isNoOp()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(2, GrandExchangeOfferState.BUYING, 1234, 0, 10), NOW);
        long before = store.bySlot(2).getEffectiveLastActivityAtMillis();

        store.correctActivityAt(999_999L, 42L);

        assertEquals("no record changed for unknown id", before,
            store.bySlot(2).getEffectiveLastActivityAtMillis());
    }
}
