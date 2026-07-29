package com.flipsmart.domain.flip;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AwaitingSaleLotsTest
{
    private static final String FIRST_BUY_TIME = "2026-07-28T00:00:00Z";
    private static final String ITEM_42_NAME = "item42";

    private static java.util.function.IntFunction<AwaitingSaleLots.BuyBasis> basis(Map<Integer, AwaitingSaleLots.BuyBasis> m)
    {
        return id -> m.get(id);
    }

    @Test
    public void heldItemWithBuyRecordAndNoLiveSellIsAwaitingSale()
    {
        Map<Integer,Integer> inv = new HashMap<>(); inv.put(42, 5);
        Map<Integer, AwaitingSaleLots.BuyBasis> b = new HashMap<>();
        b.put(42, new AwaitingSaleLots.BuyBasis(ITEM_42_NAME, 1000, FIRST_BUY_TIME));

        List<AwaitingSaleLot> lots = AwaitingSaleLots.derive(inv, basis(b), Collections.emptySet());

        assertEquals(1, lots.size());
        AwaitingSaleLot lot = lots.get(0);
        assertEquals(42, lot.itemId);
        assertEquals(ITEM_42_NAME, lot.itemName);
        assertEquals(5, lot.quantity);
        assertEquals(1000, lot.avgBuyPrice);
        assertEquals(5000L, lot.totalInvested);
        assertEquals(FIRST_BUY_TIME, lot.firstBuyTime);
    }

    @Test
    public void suppressedWhenLiveSellExists()
    {
        Map<Integer,Integer> inv = new HashMap<>(); inv.put(42, 5);
        Map<Integer, AwaitingSaleLots.BuyBasis> b = new HashMap<>();
        b.put(42, new AwaitingSaleLots.BuyBasis(ITEM_42_NAME, 1000, FIRST_BUY_TIME));

        List<AwaitingSaleLot> lots = AwaitingSaleLots.derive(inv, basis(b),
            new HashSet<>(Collections.singletonList(42)));

        assertTrue(lots.isEmpty());
    }

    @Test
    public void heldItemWithoutBuyRecordIsNotAwaitingSale()
    {
        Map<Integer,Integer> inv = new HashMap<>(); inv.put(42, 5);
        List<AwaitingSaleLot> lots = AwaitingSaleLots.derive(inv, basis(new HashMap<>()), Collections.emptySet());
        assertTrue(lots.isEmpty());
    }

    @Test
    public void notInInventoryIsNotAwaitingSale()
    {
        Map<Integer, AwaitingSaleLots.BuyBasis> b = new HashMap<>();
        b.put(42, new AwaitingSaleLots.BuyBasis(ITEM_42_NAME, 1000, FIRST_BUY_TIME));
        List<AwaitingSaleLot> lots = AwaitingSaleLots.derive(Collections.emptyMap(), basis(b), Collections.emptySet());
        assertTrue(lots.isEmpty());
    }

    @Test
    public void nullFirstBuyTimeIsCarriedThrough()
    {
        Map<Integer,Integer> inv = new HashMap<>(); inv.put(42, 5);
        Map<Integer, AwaitingSaleLots.BuyBasis> b = new HashMap<>();
        b.put(42, new AwaitingSaleLots.BuyBasis(ITEM_42_NAME, 1000, null));

        List<AwaitingSaleLot> lots = AwaitingSaleLots.derive(inv, basis(b), Collections.emptySet());

        assertEquals(1, lots.size());
        assertNull(lots.get(0).firstBuyTime);
    }
}
