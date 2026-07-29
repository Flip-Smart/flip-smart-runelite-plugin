package com.flipsmart.domain.flip;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import org.junit.Test;
import java.util.*;
import java.util.function.IntFunction;
import static org.junit.Assert.*;

public class ActiveFlipProjectionTest
{
    private static final IntFunction<AwaitingSaleLots.BuyBasis> NO_BASIS = id -> null;
    private static final String ITEM_42_NAME = "item42";
    private static final String FIRST_BUY_TIME = "2026-07-28T00:00:00Z";

    private static OfferRecord liveSell(int itemId, int total, int price, int filled, long spent)
    {
        return OfferRecord.newOffer(1L, 3, itemId, "item" + itemId, false, total, price, 1000L)
            .withFill(filled, spent, OfferState.PARTIAL_FILL, 2000L);
    }

    @Test
    public void sellOfferBecomesSellingActiveFlip()
    {
        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.singletonList(liveSell(42, 10, 1200, 3, 3600L)),
            NO_BASIS, Collections.emptyList(), Collections.emptyMap());

        assertEquals(1, out.size());
        ActiveFlip f = out.get(0);
        assertEquals(42, f.getItemId());
        assertEquals("sell", f.getPhase());
        assertEquals(ITEM_42_NAME, f.getItemName());
    }

    @Test
    public void awaitingSaleLotBecomesActiveFlip()
    {
        List<AwaitingSaleLot> lots = Collections.singletonList(
            new AwaitingSaleLot(42, ITEM_42_NAME, 5, 1000, 5000L, FIRST_BUY_TIME));

        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.emptyList(), NO_BASIS, lots, Collections.emptyMap());

        assertEquals(1, out.size());
        ActiveFlip f = out.get(0);
        assertEquals(42, f.getItemId());
        assertEquals(5, f.getTotalQuantity());
        assertEquals(1000, f.getAverageBuyPrice());
        assertEquals(5000L, f.getTotalInvested());
        assertEquals(FIRST_BUY_TIME, f.getFirstBuyTime());
    }

    @Test
    public void sellingPositionGetsCostBasisAndFirstBuyTimeFromLookup()
    {
        IntFunction<AwaitingSaleLots.BuyBasis> basis = id ->
            id == 42 ? new AwaitingSaleLots.BuyBasis(ITEM_42_NAME, 1000, FIRST_BUY_TIME) : null;

        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.singletonList(liveSell(42, 10, 1200, 3, 3600L)),
            basis, Collections.emptyList(), Collections.emptyMap());

        ActiveFlip f = out.get(0);
        assertEquals(1000, f.getAverageBuyPrice());
        assertEquals(10000L, f.getTotalInvested());
        assertEquals(FIRST_BUY_TIME, f.getFirstBuyTime());
    }

    @Test
    public void sellingPositionWithoutBasisKeepsDefaultsForEnrichment()
    {
        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.singletonList(liveSell(42, 10, 1200, 3, 3600L)),
            NO_BASIS, Collections.emptyList(), Collections.emptyMap());

        ActiveFlip f = out.get(0);
        assertEquals(0, f.getAverageBuyPrice());
        assertEquals(0L, f.getTotalInvested());
        assertNull(f.getFirstBuyTime());
    }

    @Test
    public void enrichmentRecommendedSellPriceMergedByItemId()
    {
        ActiveFlip enrich = new ActiveFlip();
        enrich.setItemId(42);
        enrich.setRecommendedSellPrice(1500);
        Map<Integer, ActiveFlip> byId = new HashMap<>();
        byId.put(42, enrich);

        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.singletonList(liveSell(42, 10, 1200, 3, 3600L)),
            NO_BASIS, Collections.emptyList(), byId);

        assertEquals(Integer.valueOf(1500), out.get(0).getRecommendedSellPrice());
    }

    @Test
    public void enrichmentBackfillsFirstBuyTimeWhenProjectedNull()
    {
        ActiveFlip enrich = new ActiveFlip();
        enrich.setItemId(42);
        enrich.setFirstBuyTime("2026-07-27T00:00:00Z");
        Map<Integer, ActiveFlip> byId = new HashMap<>();
        byId.put(42, enrich);

        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.singletonList(liveSell(42, 10, 1200, 3, 3600L)),
            NO_BASIS, Collections.emptyList(), byId);

        assertEquals("2026-07-27T00:00:00Z", out.get(0).getFirstBuyTime());
    }

    @Test
    public void localFirstBuyTimeIsNotClobberedByEnrichment()
    {
        ActiveFlip enrich = new ActiveFlip();
        enrich.setItemId(42);
        enrich.setFirstBuyTime("2026-07-27T00:00:00Z");
        Map<Integer, ActiveFlip> byId = new HashMap<>();
        byId.put(42, enrich);

        IntFunction<AwaitingSaleLots.BuyBasis> basis = id ->
            new AwaitingSaleLots.BuyBasis(ITEM_42_NAME, 1000, "2026-07-28T12:00:00Z");

        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.singletonList(liveSell(42, 10, 1200, 3, 3600L)),
            basis, Collections.emptyList(), byId);

        assertEquals("2026-07-28T12:00:00Z", out.get(0).getFirstBuyTime());
    }

    @Test
    public void sameItemSellingAndAwaitingAreTwoDistinctFlips()
    {
        // Guard: caller must not pass an awaiting lot whose item also has a live sell (suppressed in
        // T1). This asserts the projection does not itself dedup — it trusts its inputs.
        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.singletonList(liveSell(42, 10, 1200, 3, 3600L)),
            NO_BASIS,
            Collections.singletonList(new AwaitingSaleLot(99, "item99", 5, 1000, 5000L, null)),
            Collections.emptyMap());
        assertEquals(2, out.size());
    }
}
