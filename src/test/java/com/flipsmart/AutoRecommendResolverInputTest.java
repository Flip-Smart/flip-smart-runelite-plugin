package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.recommend.ResolverInput;
import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AutoRecommendResolverInputTest {

    @Mock private FlipSmartConfig config;
    @Mock private FlipSmartPlugin plugin;
    private OfferStore offerStore;
    private AutoRecommendService service;
    private PlayerSession session;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        offerStore = new OfferStore();
        session = new PlayerSession();
        when(plugin.getSession()).thenReturn(session);
        when(plugin.getFlipSlotLimit()).thenReturn(8);
        when(plugin.getFilledGESlotCount()).thenReturn(1);
        when(plugin.getActiveFlipItemIds()).thenReturn(new java.util.HashSet<>());
        when(config.priceOffset()).thenReturn(0);
        when(config.minimumProfit()).thenReturn(1);
        service = new AutoRecommendService(config, plugin, offerStore);
    }

    @Test
    public void completedFilledSellBecomesCompletedAwaitingCollectionInput() {
        OfferRecord filledSell = OfferRecord
            .newOffer(1, 0, 100, "Item", false, 5, 200, 0L)
            .withFill(5, 1000L, OfferState.FILLED, 2000L);
        offerStore.importRecords(java.util.Arrays.asList(filledSell));

        ResolverInput in = service.buildResolverInput(-1);

        assertEquals(1, in.getCompletedAwaitingCollection().size());
        assertEquals(100, in.getCompletedAwaitingCollection().get(0).getItemId());
        assertEquals(8, in.getSlotLimit());
        assertEquals(1, in.getFilledSlotCount());
    }

    @Test
    public void ghostCollectWithNoLiveGeOfferIsFilteredOut() {
        // A FILLED sell record whose offer has already left the GE (collected offline / missed
        // EMPTY): the live slot shows nothing collectable. It must not surface as a collect prompt.
        OfferRecord filledSell = OfferRecord
            .newOffer(1, 0, 100, "Item", false, 5, 200, 0L)
            .withFill(5, 1000L, OfferState.FILLED, 2000L);
        offerStore.importRecords(java.util.Arrays.asList(filledSell));
        when(plugin.geCollectableItemBySlot())
            .thenReturn(new int[] {-1, -1, -1, -1, -1, -1, -1, -1});

        ResolverInput in = service.buildResolverInput(-1);

        assertEquals(0, in.getCompletedAwaitingCollection().size());
    }

    @Test
    public void liveCollectBackedByMatchingGeSlotIsKept() {
        // Same FILLED sell, but the live slot 0 still shows item 100 as collectable: keep it.
        OfferRecord filledSell = OfferRecord
            .newOffer(1, 0, 100, "Item", false, 5, 200, 0L)
            .withFill(5, 1000L, OfferState.FILLED, 2000L);
        offerStore.importRecords(java.util.Arrays.asList(filledSell));
        when(plugin.geCollectableItemBySlot())
            .thenReturn(new int[] {100, -1, -1, -1, -1, -1, -1, -1});

        ResolverInput in = service.buildResolverInput(-1);

        assertEquals(1, in.getCompletedAwaitingCollection().size());
        assertEquals(100, in.getCompletedAwaitingCollection().get(0).getItemId());
    }

    @Test
    public void ghostCollectWhenSlotNowHoldsDifferentItemIsFilteredOut() {
        // The record's slot was reused by a different order: slot 0 now shows item 999, not 100.
        OfferRecord filledSell = OfferRecord
            .newOffer(1, 0, 100, "Item", false, 5, 200, 0L)
            .withFill(5, 1000L, OfferState.FILLED, 2000L);
        offerStore.importRecords(java.util.Arrays.asList(filledSell));
        when(plugin.geCollectableItemBySlot())
            .thenReturn(new int[] {999, -1, -1, -1, -1, -1, -1, -1});

        ResolverInput in = service.buildResolverInput(-1);

        assertEquals(0, in.getCompletedAwaitingCollection().size());
    }

    @Test
    public void slotlessOrphanKeptOnlyWhileItsItemStillCollectableSomewhere() {
        // An evicted (slot-reuse) orphan carries state FILLED but no slot. It is reconciled by
        // item: kept while some live slot still shows item 100, dropped once none does.
        OfferRecord orphan = OfferRecord
            .newOffer(1, 0, 100, "Item", false, 5, 200, 0L)
            .withFill(5, 1000L, OfferState.FILLED, 2000L)
            .withSlot(null);
        offerStore.importRecords(java.util.Arrays.asList(orphan));

        when(plugin.geCollectableItemBySlot())
            .thenReturn(new int[] {-1, -1, 100, -1, -1, -1, -1, -1});
        assertEquals(1, service.buildResolverInput(-1).getCompletedAwaitingCollection().size());

        when(plugin.geCollectableItemBySlot())
            .thenReturn(new int[] {-1, -1, -1, -1, -1, -1, -1, -1});
        assertEquals(0, service.buildResolverInput(-1).getCompletedAwaitingCollection().size());
    }

    @Test
    public void unavailableGeViewFailsOpenAndKeepsTheFeed() {
        // When the live GE view is unavailable (null = off client thread / not yet loaded) the
        // feed is returned unfiltered, so a transient gap never suppresses a genuine collect.
        OfferRecord filledSell = OfferRecord
            .newOffer(1, 0, 100, "Item", false, 5, 200, 0L)
            .withFill(5, 1000L, OfferState.FILLED, 2000L);
        offerStore.importRecords(java.util.Arrays.asList(filledSell));
        when(plugin.geCollectableItemBySlot()).thenReturn(null);

        ResolverInput in = service.buildResolverInput(-1);

        assertEquals(1, in.getCompletedAwaitingCollection().size());
    }

    @Test
    public void unpricedCollectedItemWithinGraceBlocksNewBuy() {
        // Just-collected, in inventory, but no sell price yet (e.g. a wiki-price timeout).
        // It must NOT appear as a listable item, but it MUST flag a pending-sell block so
        // the resolver holds the free slot instead of surfacing a new buy.
        when(plugin.getInventoryCountForItem(777)).thenReturn(3);
        session.addCollectedItem(777, 3,
            com.flipsmart.recommend.CollectOrigin.COMPLETED_BUY, System.currentTimeMillis());
        // no recommended price set → resolveBestSellPrice returns null

        ResolverInput in = service.buildResolverInput(-1);

        assertEquals(0, in.getCollectedAwaitingList().size());
        assertTrue(in.isBlockBuyForPendingSell());
        assertEquals(777, in.getPendingSellItemId());
    }

    @Test
    public void unpricedCollectedItemPastGraceDoesNotBlockNewBuy() {
        // If the price never resolves, the block must lift after the grace window so a
        // stuck price can't wedge trading — auto resumes normal buys.
        when(plugin.getInventoryCountForItem(777)).thenReturn(3);
        session.addCollectedItem(777, 3,
            com.flipsmart.recommend.CollectOrigin.COMPLETED_BUY,
            System.currentTimeMillis() - 180_000L); // collected 3 min ago, past the grace window

        ResolverInput in = service.buildResolverInput(-1);

        assertEquals(0, in.getCollectedAwaitingList().size());
        assertFalse(in.isBlockBuyForPendingSell());
    }

    @Test
    public void collectedItemWithPriceBecomesCollectedAwaitingListInput() {
        when(plugin.getInventoryCountForItem(777)).thenReturn(3);
        session.addCollectedItem(777, 3,
            com.flipsmart.recommend.CollectOrigin.PARTIAL_CANCEL, 1500L);
        session.setRecommendedPrice(777, 250);

        ResolverInput in = service.buildResolverInput(-1);

        assertEquals(1, in.getCollectedAwaitingList().size());
        assertEquals(777, in.getCollectedAwaitingList().get(0).getItemId());
        assertEquals(com.flipsmart.recommend.CollectOrigin.PARTIAL_CANCEL,
            in.getCollectedAwaitingList().get(0).getOrigin());
        assertEquals(true, in.getCollectedAwaitingList().get(0).hasSellPrice());
    }
}
