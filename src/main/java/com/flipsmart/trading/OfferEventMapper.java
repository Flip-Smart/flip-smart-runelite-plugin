package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferSignal;

import net.runelite.api.GrandExchangeOfferState;

/** Reduces a raw GE offer-changed event to the {@link OfferSignal} the trading core consumes. */
public final class OfferEventMapper
{
    private OfferEventMapper() {}

    public static OfferSignal toSignal(
        int slot,
        GrandExchangeOfferState state,
        int itemId,
        String itemName,
        int totalQuantity,
        int price,
        int quantitySold,
        long spent)
    {
        return new OfferSignal(slot, state, itemId, itemName, totalQuantity, price, quantitySold, spent);
    }
}
