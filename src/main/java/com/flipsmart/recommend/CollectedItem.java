package com.flipsmart.recommend;

public final class CollectedItem {

    private final int itemId;
    private final CollectOrigin origin;
    private final boolean hasSellPrice;
    private final long detectedAtMillis;

    public CollectedItem(int itemId, CollectOrigin origin, boolean hasSellPrice, long detectedAtMillis) {
        this.itemId = itemId;
        this.origin = origin;
        this.hasSellPrice = hasSellPrice;
        this.detectedAtMillis = detectedAtMillis;
    }

    public int getItemId() { return itemId; }
    public CollectOrigin getOrigin() { return origin; }
    public boolean hasSellPrice() { return hasSellPrice; }
    public long getDetectedAtMillis() { return detectedAtMillis; }
}
