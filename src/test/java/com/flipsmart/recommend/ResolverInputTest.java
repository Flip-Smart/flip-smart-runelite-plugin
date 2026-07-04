package com.flipsmart.recommend;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResolverInputTest {

    @Test
    public void builderRetainsScalarsAndLists() {
        OfferRecord filledSell = OfferRecord
            .newOffer(1, 0, 100, "Item", false, 5, 200, 1000L)
            .withFill(5, 1000L, OfferState.FILLED, 2000L);

        ResolverInput in = ResolverInput.builder()
            .slotLimit(8)
            .filledSlotCount(3)
            .surfaceableBuy(true, 555)
            .nowMillis(5000L)
            .completedAwaitingCollection(List.of(filledSell))
            .staleOffers(new ArrayList<>())
            .collectedAwaitingList(List.of(
                new CollectedItem(777, CollectOrigin.PARTIAL_CANCEL, true, 4000L)))
            .build();

        assertEquals(8, in.getSlotLimit());
        assertEquals(3, in.getFilledSlotCount());
        assertTrue(in.hasSurfaceableBuy());
        assertEquals(555, in.getSurfaceableBuyItemId());
        assertEquals(5000L, in.getNowMillis());
        assertEquals(1, in.getCompletedAwaitingCollection().size());
        assertEquals(1, in.getCollectedAwaitingList().size());
        assertEquals(CollectOrigin.PARTIAL_CANCEL,
            in.getCollectedAwaitingList().get(0).getOrigin());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void completedAwaitingCollectionGetterIsUnmodifiable() {
        ResolverInput in = ResolverInput.builder()
            .slotLimit(8).filledSlotCount(0).surfaceableBuy(false, -1).nowMillis(0L)
            .completedAwaitingCollection(new ArrayList<>())
            .staleOffers(new ArrayList<>())
            .collectedAwaitingList(new ArrayList<>())
            .build();
        in.getCompletedAwaitingCollection().add(
            OfferRecord.newOffer(2, 1, 1, "x", true, 1, 1, 0L));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void staleOffersGetterIsUnmodifiable() {
        ResolverInput in = ResolverInput.builder()
            .slotLimit(8).filledSlotCount(0).surfaceableBuy(false, -1).nowMillis(0L)
            .completedAwaitingCollection(new ArrayList<>())
            .staleOffers(new ArrayList<>())
            .collectedAwaitingList(new ArrayList<>())
            .build();
        in.getStaleOffers().add(
            OfferRecord.newOffer(2, 1, 1, "x", true, 1, 1, 0L));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void collectedAwaitingListGetterIsUnmodifiable() {
        ResolverInput in = ResolverInput.builder()
            .slotLimit(8).filledSlotCount(0).surfaceableBuy(false, -1).nowMillis(0L)
            .completedAwaitingCollection(new ArrayList<>())
            .staleOffers(new ArrayList<>())
            .collectedAwaitingList(new ArrayList<>())
            .build();
        in.getCollectedAwaitingList().add(
            new CollectedItem(1, CollectOrigin.COMPLETED_BUY, true, 0L));
    }

    @Test
    public void collectedItemHasSellPriceFlagPreserved() {
        CollectedItem c = new CollectedItem(1, CollectOrigin.COMPLETED_BUY, false, 10L);
        assertFalse(c.hasSellPrice());
        assertEquals(CollectOrigin.COMPLETED_BUY, c.getOrigin());
        assertEquals(10L, c.getDetectedAtMillis());
    }
}
