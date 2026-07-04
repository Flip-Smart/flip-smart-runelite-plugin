package com.flipsmart.domain.offer;

public enum OfferState
{
    NEW,
    PARTIAL_FILL,
    FILLED,
    COLLECTED,            // terminal
    CANCELLED_EMPTY,      // terminal
    CANCELLED_PARTIAL;    // → COLLECTED

    public boolean isTerminal()
    {
        return this == COLLECTED || this == CANCELLED_EMPTY;
    }
}
