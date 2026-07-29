package com.flipsmart.domain.flip;

/** A lot bought and held in inventory, awaiting a sell listing. */
public final class AwaitingSaleLot
{
    public final int itemId;
    public final String itemName;
    public final int quantity;
    public final int avgBuyPrice;
    public final long totalInvested;

    public AwaitingSaleLot(int itemId, String itemName, int quantity, int avgBuyPrice, long totalInvested)
    {
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantity = quantity;
        this.avgBuyPrice = avgBuyPrice;
        this.totalInvested = totalInvested;
    }
}
