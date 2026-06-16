package com.flipsmart.domain.offer;

import lombok.Getter;
import lombok.NoArgsConstructor;

/** One Grand Exchange order's full lifecycle. Immutable: transitions return a new instance. */
@Getter
@NoArgsConstructor // Gson
public final class OfferRecord
{
    private long offerId;          // canonical key, minted at NEW
    private Integer slot;          // 0..7 while live; null once collected/cancelled-empty
    private int itemId;
    private String itemName;
    private boolean buy;
    private int totalQuantity;
    private int price;
    private int filledQuantity;    // cumulative
    private long spent;            // cumulative GP
    private OfferState state;
    private long createdAtMillis;
    private long completedAtMillis;
    private long lastActivityAtMillis;
    private String offerStage;     // "initial" | "breakeven_relist"

    public static final String STAGE_INITIAL = "initial";

    private OfferRecord(long offerId, Integer slot, int itemId, String itemName, boolean buy,
                        int totalQuantity, int price, int filledQuantity, long spent,
                        OfferState state, long createdAtMillis, long completedAtMillis,
                        long lastActivityAtMillis, String offerStage)
    {
        this.offerId = offerId;
        this.slot = slot;
        this.itemId = itemId;
        this.itemName = itemName;
        this.buy = buy;
        this.totalQuantity = totalQuantity;
        this.price = price;
        this.filledQuantity = filledQuantity;
        this.spent = spent;
        this.state = state;
        this.createdAtMillis = createdAtMillis;
        this.completedAtMillis = completedAtMillis;
        this.lastActivityAtMillis = lastActivityAtMillis;
        this.offerStage = offerStage;
    }

    /** A freshly-placed offer (state NEW, zero filled). */
    public static OfferRecord newOffer(long offerId, int slot, int itemId, String itemName,
                                       boolean buy, int totalQuantity, int price, long now)
    {
        return new OfferRecord(offerId, slot, itemId, itemName, buy, totalQuantity, price,
            0, 0L, OfferState.NEW, now, 0L, now, STAGE_INITIAL);
    }

    public OfferRecord withFill(int newFilledQuantity, long newSpent, OfferState newState, long now)
    {
        long completed = (newState == OfferState.FILLED && completedAtMillis == 0) ? now : completedAtMillis;
        return new OfferRecord(offerId, slot, itemId, itemName, buy, totalQuantity, price,
            newFilledQuantity, newSpent, newState, createdAtMillis, completed, now, offerStage);
    }

    public OfferRecord withState(OfferState newState, long now)
    {
        Integer newSlot = newState.isTerminal() ? null : slot;
        return new OfferRecord(offerId, newSlot, itemId, itemName, buy, totalQuantity, price,
            filledQuantity, spent, newState, createdAtMillis, completedAtMillis, now, offerStage);
    }

    public OfferRecord withSlot(Integer newSlot)
    {
        return new OfferRecord(offerId, newSlot, itemId, itemName, buy, totalQuantity, price,
            filledQuantity, spent, state, createdAtMillis, completedAtMillis, lastActivityAtMillis, offerStage);
    }

    public OfferRecord withOfferStage(String newStage)
    {
        return new OfferRecord(offerId, slot, itemId, itemName, buy, totalQuantity, price,
            filledQuantity, spent, state, createdAtMillis, completedAtMillis, lastActivityAtMillis, newStage);
    }

    /** Last activity time, falling back to creation time for records persisted before the field existed. */
    public long getEffectiveLastActivityAtMillis()
    {
        return lastActivityAtMillis > 0 ? lastActivityAtMillis : createdAtMillis;
    }
}
