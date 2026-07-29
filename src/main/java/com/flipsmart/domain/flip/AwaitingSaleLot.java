package com.flipsmart.domain.flip;

/** A lot bought and held in inventory, awaiting a sell listing. */
public final class AwaitingSaleLot
{
    public final int itemId;
    public final String itemName;
    public final int quantity;
    public final int avgBuyPrice;
    public final long totalInvested;
    /** ISO-8601 time of the buy this lot came from; null when unknown (time-scopes realized P&L). */
    public final String firstBuyTime;

    public AwaitingSaleLot(int itemId, String itemName, int quantity, int avgBuyPrice, long totalInvested,
        String firstBuyTime)
    {
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantity = quantity;
        this.avgBuyPrice = avgBuyPrice;
        this.totalInvested = totalInvested;
        this.firstBuyTime = firstBuyTime;
    }
}
