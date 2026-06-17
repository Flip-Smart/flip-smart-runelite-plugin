package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.recommend.ResolverInput;
import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
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
