package com.flipsmart.domain.flip;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ActiveFlipProjectionTest
{
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
            Collections.emptyList(), Collections.emptyMap());

        assertEquals(1, out.size());
        ActiveFlip f = out.get(0);
        assertEquals(42, f.getItemId());
        assertEquals("sell", f.getPhase());
        assertEquals("item42", f.getItemName());
    }

    @Test
    public void awaitingSaleLotBecomesActiveFlip()
    {
        List<AwaitingSaleLot> lots = Collections.singletonList(
            new AwaitingSaleLot(42, "item42", 5, 1000, 5000L));

        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.emptyList(), lots, Collections.emptyMap());

        assertEquals(1, out.size());
        ActiveFlip f = out.get(0);
        assertEquals(42, f.getItemId());
        assertEquals(5, f.getTotalQuantity());
        assertEquals(1000, f.getAverageBuyPrice());
        assertEquals(5000L, f.getTotalInvested());
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
            Collections.emptyList(), byId);

        assertEquals(Integer.valueOf(1500), out.get(0).getRecommendedSellPrice());
    }

    @Test
    public void sameItemSellingAndAwaitingAreTwoDistinctFlips()
    {
        // Guard: caller must not pass an awaiting lot whose item also has a live sell (suppressed in
        // T1). This asserts the projection does not itself dedup — it trusts its inputs.
        List<ActiveFlip> out = ActiveFlipProjection.project(
            Collections.singletonList(liveSell(42, 10, 1200, 3, 3600L)),
            Collections.singletonList(new AwaitingSaleLot(99, "item99", 5, 1000, 5000L)),
            Collections.emptyMap());
        assertEquals(2, out.size());
    }
}
