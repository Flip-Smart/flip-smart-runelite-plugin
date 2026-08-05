package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferTransition;

public final class OfferEvent
{
    public final OfferTransition.Kind kind;
    public final OfferRecord record;
    public final int newlyFilledQuantity;
    public final long newlySpent;
    /** Generation of the slot when this delta was measured. Carried on the event
     *  rather than looked up by listeners because a terminal offer advances its
     *  slot's generation before they run, which would stamp an order's last fill
     *  with its successor's identity. */
    public final int slotGeneration;

    public OfferEvent(OfferTransition.Kind kind, OfferRecord record, int newlyFilledQuantity, long newlySpent)
    {
        this(kind, record, newlyFilledQuantity, newlySpent, 0);
    }

    public OfferEvent(OfferTransition.Kind kind, OfferRecord record, int newlyFilledQuantity, long newlySpent,
        int slotGeneration)
    {
        this.kind = kind;
        this.record = record;
        this.newlyFilledQuantity = newlyFilledQuantity;
        this.newlySpent = newlySpent;
        this.slotGeneration = slotGeneration;
    }
}
