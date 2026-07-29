package com.flipsmart.domain.flip;

import com.flipsmart.domain.offer.OfferRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Projects the offer store's live sell offers and derived awaiting-sale lots into the display list
 * for the Active Flips tab. Local facts come from the store/lot (single source of truth); the last
 * backend response supplies enrichment (recommended sell price, P&L) merged in by itemId. Never
 * filters or dedups — the inputs are already the authoritative live set.
 */
public final class ActiveFlipProjection
{
    private ActiveFlipProjection() {}

    public static List<ActiveFlip> project(List<OfferRecord> liveSellOffers,
        IntFunction<AwaitingSaleLots.BuyBasis> sellBasisForItem,
        List<AwaitingSaleLot> awaitingSaleLots, Map<Integer, ActiveFlip> enrichmentByItemId)
    {
        List<ActiveFlip> out = new ArrayList<>();
        for (OfferRecord sell : liveSellOffers)
        {
            ActiveFlip f = new ActiveFlip();
            f.setItemId(sell.getItemId());
            f.setItemName(sell.getItemName());
            f.setTotalQuantity(sell.getTotalQuantity());
            f.setOriginalQuantity(sell.getTotalQuantity());
            f.setPhase("sell");
            // Local cost basis so profit/margin/ROI and time-scoping are correct before the backend
            // catches up; absent basis falls through to enrichment below.
            AwaitingSaleLots.BuyBasis basis = sellBasisForItem == null
                ? null : sellBasisForItem.apply(sell.getItemId());
            if (basis != null)
            {
                f.setAverageBuyPrice(basis.avgBuyPrice);
                f.setTotalInvested((long) basis.avgBuyPrice * sell.getTotalQuantity());
                f.setFirstBuyTime(basis.firstBuyTimeIso);
            }
            mergeEnrichment(f, enrichmentByItemId);
            out.add(f);
        }
        for (AwaitingSaleLot lot : awaitingSaleLots)
        {
            ActiveFlip f = new ActiveFlip();
            f.setItemId(lot.itemId);
            f.setItemName(lot.itemName);
            f.setTotalQuantity(lot.quantity);
            f.setOriginalQuantity(lot.quantity);
            f.setAverageBuyPrice(lot.avgBuyPrice);
            f.setTotalInvested(lot.totalInvested);
            f.setFirstBuyTime(lot.firstBuyTime);
            f.setPhase("sell");
            mergeEnrichment(f, enrichmentByItemId);
            out.add(f);
        }
        return out;
    }

    private static void mergeEnrichment(ActiveFlip f, Map<Integer, ActiveFlip> enrichmentByItemId)
    {
        ActiveFlip e = enrichmentByItemId == null ? null : enrichmentByItemId.get(f.getItemId());
        if (e == null)
        {
            return;
        }
        if (e.getRecommendedSellPrice() != null)
        {
            f.setRecommendedSellPrice(e.getRecommendedSellPrice());
        }
        if (f.getAverageBuyPrice() == 0 && e.getAverageBuyPrice() != 0)
        {
            f.setAverageBuyPrice(e.getAverageBuyPrice());
        }
        if (f.getTotalInvested() == 0 && e.getTotalInvested() != 0)
        {
            f.setTotalInvested(e.getTotalInvested());
        }
        if (f.getFirstBuyTime() == null && e.getFirstBuyTime() != null)
        {
            f.setFirstBuyTime(e.getFirstBuyTime());
        }
    }
}
