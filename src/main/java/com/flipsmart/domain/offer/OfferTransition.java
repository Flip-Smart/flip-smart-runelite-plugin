package com.flipsmart.domain.offer;

/** Outcome of OfferStateMachine.decide(): the resulting record plus what observably happened. */
public final class OfferTransition
{
    public enum Kind { NONE, PLACED, FILLED_DELTA, COMPLETED, COLLECTED, CANCELLED, REJECTED }

    public final Kind kind;
    public final OfferRecord record;      // updated record (null only when REJECTED with no prior)
    public final int newlyFilledQuantity; // fill delta for this signal (also nonzero on PLACED with an immediate fill, and on CANCELLED carrying a residual fill)
    public final long newlySpent;         // GP delta for this signal (nonzero on the same transitions as newlyFilledQuantity)

    private OfferTransition(Kind kind, OfferRecord record, int newlyFilledQuantity, long newlySpent)
    {
        this.kind = kind;
        this.record = record;
        this.newlyFilledQuantity = newlyFilledQuantity;
        this.newlySpent = newlySpent;
    }

    public static OfferTransition of(Kind kind, OfferRecord record, int newlyFilledQuantity, long newlySpent)
    {
        return new OfferTransition(kind, record, newlyFilledQuantity, newlySpent);
    }

    /** Convenience overload for transitions where no GP was spent (NONE, PLACED, CANCELLED, COLLECTED). */
    public static OfferTransition of(Kind kind, OfferRecord record, int newlyFilledQuantity)
    {
        return new OfferTransition(kind, record, newlyFilledQuantity, 0L);
    }

    public static OfferTransition rejected(OfferRecord unchanged)
    {
        return new OfferTransition(Kind.REJECTED, unchanged, 0, 0L);
    }
}
