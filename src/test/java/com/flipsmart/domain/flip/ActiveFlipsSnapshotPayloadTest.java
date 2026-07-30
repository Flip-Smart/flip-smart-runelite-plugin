package com.flipsmart.domain.flip;

import com.flipsmart.domain.offer.PendingOrder;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActiveFlipsSnapshotPayloadTest
{
    private static ActiveFlip sellFlip(int itemId)
    {
        ActiveFlip f = new ActiveFlip();
        f.setItemId(itemId);
        f.setPhase("sell");
        return f;
    }

    private static PendingOrder pendingBuy(int itemId, int quantity, int quantityFilled, int pricePerItem)
    {
        return new PendingOrder(itemId, "item" + itemId, quantity, quantityFilled, pricePerItem, null, 3);
    }

    @Test
    public void pendingBuyOrderAppearsInPayloadWithBuyPhase()
    {
        List<ActiveFlip> out = ActiveFlipsSnapshotPayload.combine(
            Collections.emptyList(), List.of(pendingBuy(42, 10, 0, 100)));

        assertEquals(1, out.size());
        assertEquals(42, out.get(0).getItemId());
        assertEquals("buy", out.get(0).getPhase());
    }

    @Test
    public void onlyPendingBuysStillProducesAPayload()
    {
        List<ActiveFlip> out = ActiveFlipsSnapshotPayload.combine(
            Collections.emptyList(), List.of(pendingBuy(42, 10, 5, 100)));

        assertEquals(1, out.size());
        assertEquals(500L, out.get(0).getTotalInvested());
    }

    @Test
    public void sameItemOnBuyAndSellBothAppear()
    {
        List<ActiveFlip> out = ActiveFlipsSnapshotPayload.combine(
            List.of(sellFlip(42)), List.of(pendingBuy(42, 10, 0, 100)));

        assertEquals(2, out.size());
        assertTrue(out.stream().anyMatch(f -> f.getItemId() == 42 && "sell".equals(f.getPhase())));
        assertTrue(out.stream().anyMatch(f -> f.getItemId() == 42 && "buy".equals(f.getPhase())));
    }

    @Test
    public void emptyPayloadWithUnseededOfferStoreIsSkipped()
    {
        assertTrue(ActiveFlipsSnapshotPayload.isUnobservedEmpty(Collections.emptyList(), true));
    }

    @Test
    public void emptyPayloadWithSeededOfferStoreIsNotSkipped()
    {
        assertFalse(ActiveFlipsSnapshotPayload.isUnobservedEmpty(Collections.emptyList(), false));
    }

    @Test
    public void nonEmptyPayloadIsNeverSkippedEvenIfUnseeded()
    {
        assertFalse(ActiveFlipsSnapshotPayload.isUnobservedEmpty(List.of(sellFlip(42)), true));
    }
}
