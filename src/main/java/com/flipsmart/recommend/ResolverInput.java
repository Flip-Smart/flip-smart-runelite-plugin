package com.flipsmart.recommend;

import com.flipsmart.domain.offer.OfferRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ResolverInput {

    private final int slotLimit;
    private final int filledSlotCount;
    private final boolean hasSurfaceableBuy;
    private final int surfaceableBuyItemId;
    private final long nowMillis;
    private final List<OfferRecord> completedAwaitingCollection;
    private final List<OfferRecord> staleOffers;
    private final List<CollectedItem> collectedAwaitingList;

    private ResolverInput(Builder b) {
        this.slotLimit = b.slotLimit;
        this.filledSlotCount = b.filledSlotCount;
        this.hasSurfaceableBuy = b.hasSurfaceableBuy;
        this.surfaceableBuyItemId = b.surfaceableBuyItemId;
        this.nowMillis = b.nowMillis;
        this.completedAwaitingCollection =
            Collections.unmodifiableList(new ArrayList<>(b.completedAwaitingCollection));
        this.staleOffers =
            Collections.unmodifiableList(new ArrayList<>(b.staleOffers));
        this.collectedAwaitingList =
            Collections.unmodifiableList(new ArrayList<>(b.collectedAwaitingList));
    }

    public int getSlotLimit() { return slotLimit; }
    public int getFilledSlotCount() { return filledSlotCount; }
    public boolean hasSurfaceableBuy() { return hasSurfaceableBuy; }
    public int getSurfaceableBuyItemId() { return surfaceableBuyItemId; }
    public long getNowMillis() { return nowMillis; }
    public List<OfferRecord> getCompletedAwaitingCollection() { return completedAwaitingCollection; }
    public List<OfferRecord> getStaleOffers() { return staleOffers; }
    public List<CollectedItem> getCollectedAwaitingList() { return collectedAwaitingList; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int slotLimit;
        private int filledSlotCount;
        private boolean hasSurfaceableBuy;
        private int surfaceableBuyItemId = -1;
        private long nowMillis;
        private List<OfferRecord> completedAwaitingCollection = new ArrayList<>();
        private List<OfferRecord> staleOffers = new ArrayList<>();
        private List<CollectedItem> collectedAwaitingList = new ArrayList<>();

        public Builder slotLimit(int v) { this.slotLimit = v; return this; }
        public Builder filledSlotCount(int v) { this.filledSlotCount = v; return this; }
        public Builder surfaceableBuy(boolean has, int itemId) {
            this.hasSurfaceableBuy = has; this.surfaceableBuyItemId = itemId; return this;
        }
        public Builder nowMillis(long v) { this.nowMillis = v; return this; }
        public Builder completedAwaitingCollection(List<OfferRecord> v) {
            this.completedAwaitingCollection = v; return this;
        }
        public Builder staleOffers(List<OfferRecord> v) { this.staleOffers = v; return this; }
        public Builder collectedAwaitingList(List<CollectedItem> v) {
            this.collectedAwaitingList = v; return this;
        }
        public ResolverInput build() { return new ResolverInput(this); }
    }
}
