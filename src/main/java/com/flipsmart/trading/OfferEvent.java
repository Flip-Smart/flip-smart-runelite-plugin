package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferTransition;

public final class OfferEvent
{
    public final OfferTransition.Kind kind;
    public final OfferRecord record;
    public final int newlyFilledQuantity;

    public OfferEvent(OfferTransition.Kind kind, OfferRecord record, int newlyFilledQuantity)
    {
        this.kind = kind;
        this.record = record;
        this.newlyFilledQuantity = newlyFilledQuantity;
    }
}
