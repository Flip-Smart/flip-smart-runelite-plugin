package com.flipsmart.domain.flip;

import com.flipsmart.domain.offer.OfferRecord;

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

    /**
     * Cost basis for the position currently held in {@code buys}.
     *
     * <p>{@code cycleBasis} — the round-trip ledger's quantity-weighted average for the cycle still
     * open on the item — prices the lot whenever it knows one. The store cannot do that job: it
     * never evicts, so its records outlive the round trip they belong to and the most recent one is
     * no proof of which position the player is holding.</p>
     *
     * <p>The records supply identity, and a price only from a fill that really happened. An offer
     * that bought nothing carries a price the player asked for, never one they paid, so it leaves
     * the basis unknown for callers to resolve elsewhere.</p>
     */
    public static BuyBasis resolveBuyBasis(List<OfferRecord> buys, Integer cycleBasis)
    {
        if (buys == null || buys.isEmpty())
        {
            return null;
        }
        OfferRecord bestFilled = mostRecentFilledBuy(buys);
        OfferRecord identity = bestFilled != null ? bestFilled : mostRecentBuy(buys);
        if (identity == null)
        {
            return null;
        }
        int price = cycleBasis != null && cycleBasis > 0 ? cycleBasis : avgBuyPrice(bestFilled);
        return new BuyBasis(identity.getItemName(), price, firstBuyTimeIso(identity));
    }

    private static OfferRecord mostRecentBuy(List<OfferRecord> buys)
    {
        OfferRecord best = null;
        for (OfferRecord r : buys)
        {
            if (best == null || r.getEffectiveLastActivityAtMillis() > best.getEffectiveLastActivityAtMillis())
            {
                best = r;
            }
        }
        return best;
    }

    private static OfferRecord mostRecentFilledBuy(List<OfferRecord> buys)
    {
        OfferRecord best = null;
        for (OfferRecord r : buys)
        {
            if (r.getFilledQuantity() <= 0)
            {
                continue;
            }
            if (best == null || r.getEffectiveLastActivityAtMillis() > best.getEffectiveLastActivityAtMillis())
            {
                best = r;
            }
        }
        return best;
    }

    private static int avgBuyPrice(OfferRecord filled)
    {
        return filled != null && filled.getSpent() > 0 && filled.getFilledQuantity() > 0
            ? (int) (filled.getSpent() / filled.getFilledQuantity())
            : 0;
    }

    private static String firstBuyTimeIso(OfferRecord best)
    {
        long firstBuyMillis = best.getCreatedAtMillis() > 0
            ? best.getCreatedAtMillis()
            : best.getEffectiveLastActivityAtMillis();
        return firstBuyMillis > 0 ? java.time.Instant.ofEpochMilli(firstBuyMillis).toString() : null;
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
