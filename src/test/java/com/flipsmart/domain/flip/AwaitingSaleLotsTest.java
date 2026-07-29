package com.flipsmart.domain.flip;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AwaitingSaleLotsTest
{
    private static java.util.function.IntFunction<AwaitingSaleLots.BuyBasis> basis(Map<Integer, AwaitingSaleLots.BuyBasis> m)
    {
        return id -> m.get(id);
    }

    @Test
    public void heldItemWithBuyRecordAndNoLiveSellIsAwaitingSale()
    {
        Map<Integer,Integer> inv = new HashMap<>(); inv.put(42, 5);
        Map<Integer, AwaitingSaleLots.BuyBasis> b = new HashMap<>();
        b.put(42, new AwaitingSaleLots.BuyBasis("item42", 1000));

        List<AwaitingSaleLot> lots = AwaitingSaleLots.derive(inv, basis(b), Collections.emptySet());

        assertEquals(1, lots.size());
        AwaitingSaleLot lot = lots.get(0);
        assertEquals(42, lot.itemId);
        assertEquals("item42", lot.itemName);
        assertEquals(5, lot.quantity);
        assertEquals(1000, lot.avgBuyPrice);
        assertEquals(5000L, lot.totalInvested);
    }

    @Test
    public void suppressedWhenLiveSellExists()
    {
        Map<Integer,Integer> inv = new HashMap<>(); inv.put(42, 5);
        Map<Integer, AwaitingSaleLots.BuyBasis> b = new HashMap<>();
        b.put(42, new AwaitingSaleLots.BuyBasis("item42", 1000));

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
        b.put(42, new AwaitingSaleLots.BuyBasis("item42", 1000));
        List<AwaitingSaleLot> lots = AwaitingSaleLots.derive(Collections.emptyMap(), basis(b), Collections.emptySet());
        assertTrue(lots.isEmpty());
    }
}
