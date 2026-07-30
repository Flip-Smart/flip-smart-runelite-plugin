package com.flipsmart.domain.flip;

import com.flipsmart.domain.offer.PendingOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Combines the canonical Active Flips projection (sell phase) with live pending buy
 * orders into the single payload pushed to the backend. Pure merge of two existing
 * outputs — never recomputes either one, so the canonical derivations stay untouched.
 */
public final class ActiveFlipsSnapshotPayload
{
    private ActiveFlipsSnapshotPayload() {}

    public static List<ActiveFlip> combine(List<ActiveFlip> projectedActiveFlips,
        List<PendingOrder> pendingBuyOrders)
    {
        List<ActiveFlip> payload = new ArrayList<>(projectedActiveFlips.size() + pendingBuyOrders.size());
        payload.addAll(projectedActiveFlips);
        for (PendingOrder order : pendingBuyOrders)
        {
            payload.add(toBuyPhaseFlip(order));
        }
        return payload;
    }

    private static ActiveFlip toBuyPhaseFlip(PendingOrder order)
    {
        ActiveFlip f = new ActiveFlip();
        f.setItemId(order.itemId);
        f.setItemName(order.itemName);
        f.setTotalQuantity(order.quantity);
        f.setOriginalQuantity(order.quantity);
        f.setAverageBuyPrice(order.pricePerItem);
        f.setTotalInvested((long) order.pricePerItem * order.quantityFilled);
        f.setRecommendedSellPrice(order.recommendedSellPrice);
        f.setPhase("buy");
        return f;
    }

    /**
     * True when an empty payload should NOT be pushed because emptiness was defaulted
     * rather than observed — the offer store has not yet been seeded from real GE state
     * (login burst or persisted-offer preload), e.g. the plugin was just enabled with no
     * persisted offer blob while live offers still exist in-game. A genuinely empty
     * payload from a seeded store is always safe to push (that's what clears a stale
     * dashboard).
     */
    public static boolean isUnobservedEmpty(List<ActiveFlip> payload, boolean offerStoreUnseeded)
    {
        return payload.isEmpty() && offerStoreUnseeded;
    }
}
