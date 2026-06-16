package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
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
    public void offerIds_areMonotonicAndUnique()
    {
        OfferStore store = new OfferStore();
        store.apply(sig(0, GrandExchangeOfferState.BUYING, 1, 0, 1), NOW);
        store.apply(sig(1, GrandExchangeOfferState.BUYING, 2, 0, 1), NOW);

        long a = store.bySlot(0).getOfferId();
        long b = store.bySlot(1).getOfferId();
        assertTrue(b > a);
    }
}
