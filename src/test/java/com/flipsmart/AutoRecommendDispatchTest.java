package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.recommend.ActionDecision;
import com.flipsmart.recommend.ActionKind;
import com.flipsmart.recommend.CollectOrigin;
import com.flipsmart.trading.OfferStore;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AutoRecommendDispatchTest {

    @Mock private FlipSmartConfig config;
    @Mock private FlipSmartPlugin plugin;
    @Mock private FlipSmartApiClient apiClient;
    private OfferStore offerStore;
    private AutoRecommendService service;
    private PlayerSession session;

    private static FlipRecommendation rec(int itemId) {
        FlipRecommendation r = new FlipRecommendation();
        r.setItemId(itemId); r.setItemName("item-" + itemId);
        r.setRecommendedBuyPrice(100); r.setRecommendedSellPrice(200);
        r.setRecommendedQuantity(10);
        return r;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        offerStore = new OfferStore();
        session = new PlayerSession();
        when(plugin.getSession()).thenReturn(session);
        when(plugin.getFlipSlotLimit()).thenReturn(8);
        when(plugin.getActiveFlipItemIds()).thenReturn(new HashSet<>());
        when(plugin.getApiClient()).thenReturn(apiClient);
        when(apiClient.isRsnBlocked()).thenReturn(false);
        when(config.priceOffset()).thenReturn(0);
        when(config.minimumProfit()).thenReturn(1);
        service = new AutoRecommendService(config, plugin, offerStore);
        service.start(Arrays.asList(rec(21)));
    }

    @Test
    public void emptySlotWithBuyChoosesS2OverCompletedBuyCollection() {
        // product-confirmed inversion: fill empty slot before collecting a completed buy
        when(plugin.getFilledGESlotCount()).thenReturn(7);
        OfferRecord filledBuy = OfferRecord
            .newOffer(1, 0, 31, "boughtItem", true, 10, 100, 0L)
            .withFill(10, 1000L, OfferState.FILLED, 2000L);
        offerStore.importRecords(Arrays.asList(filledBuy));

        ActionDecision d = service.resolveAndApply(-1);

        assertEquals(ActionKind.S2, d.getKind());
        assertEquals(21, d.getItemId());
    }

    @Test
    public void allSlotsFullWithNothingActionableIsIdle() {
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        ActionDecision d = service.resolveAndApply(-1);
        assertEquals(ActionKind.IDLE, d.getKind());
    }

    @Test
    public void identicalStateTwiceReturnsSameDecision() {
        when(plugin.getFilledGESlotCount()).thenReturn(8);
        // a partial-cancel collected item with a price → stable S1/LIST candidate
        session.addCollectedItem(11, 3, CollectOrigin.PARTIAL_CANCEL, 1L);
        session.setRecommendedPrice(11, 150);

        ActionDecision first = service.resolveAndApply(-1);
        ActionDecision second = service.resolveAndApply(-1);
        assertEquals(first, second);
        assertEquals(ActionKind.S1, first.getKind());
    }
}
