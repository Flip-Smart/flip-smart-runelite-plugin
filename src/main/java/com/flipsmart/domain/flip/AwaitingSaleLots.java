package com.flipsmart.domain.flip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Derives awaiting-sale lots: items held in inventory that we bought via a flip and have not yet
 * relisted for sale. Pure — the panel feeds it inventory counts, a buy-basis lookup backed by the
 * offer store, and the set of items with a live sell, so the result is a projection of the single
 * source of truth rather than a maintained cache.
 */
public final class AwaitingSaleLots
{
    private AwaitingSaleLots() {}

    /** Buy cost basis for an item, from its store buy record. */
    public static final class BuyBasis
    {
        public final String itemName;
        public final int avgBuyPrice;
        /** ISO-8601 time of that buy; null when unknown. Time-scopes the card's realized P&L. */
        public final String firstBuyTimeIso;

        public BuyBasis(String itemName, int avgBuyPrice, String firstBuyTimeIso)
        {
            this.itemName = itemName;
            this.avgBuyPrice = avgBuyPrice;
            this.firstBuyTimeIso = firstBuyTimeIso;
        }
    }

    public static List<AwaitingSaleLot> derive(Map<Integer, Integer> inventoryCounts,
        IntFunction<BuyBasis> buyBasisForItem, Set<Integer> liveSellItemIds)
    {
        List<AwaitingSaleLot> lots = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : inventoryCounts.entrySet())
        {
            int itemId = e.getKey();
            int qty = e.getValue() == null ? 0 : e.getValue();
            if (qty <= 0 || (liveSellItemIds != null && liveSellItemIds.contains(itemId)))
            {
                continue;
            }
            BuyBasis basis = buyBasisForItem.apply(itemId);
            if (basis == null)
            {
                continue;
            }
            lots.add(new AwaitingSaleLot(itemId, basis.itemName, qty, basis.avgBuyPrice,
                (long) basis.avgBuyPrice * qty, basis.firstBuyTimeIso));
        }
        return lots;
    }
}
