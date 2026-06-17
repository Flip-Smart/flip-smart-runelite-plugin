package com.flipsmart.domain.offer;

import net.runelite.api.GrandExchangeOfferState;

/** A GrandExchangeOfferChanged event reduced to the fields the state machine needs. */
public final class OfferSignal
{
    public final int slot;
    public final GrandExchangeOfferState geState;
    public final int itemId;
    public final String itemName;
    public final int totalQuantity;
    public final int price;
    public final int quantitySold;   // cumulative, as reported by the client
    public final long spent;         // cumulative, as reported by the client

    public OfferSignal(int slot, GrandExchangeOfferState geState, int itemId, String itemName,
                       int totalQuantity, int price, int quantitySold, long spent)
    {
        this.slot = slot;
        this.geState = geState;
        this.itemId = itemId;
        this.itemName = itemName;
        this.totalQuantity = totalQuantity;
        this.price = price;
        this.quantitySold = quantitySold;
        this.spent = spent;
    }

    public boolean isBuy()
    {
        return isBuyState(geState);
    }

    /** Whether a GE offer state is a buy-side state. */
    public static boolean isBuyState(GrandExchangeOfferState state)
    {
        return state == GrandExchangeOfferState.BUYING
            || state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.CANCELLED_BUY;
    }
}
