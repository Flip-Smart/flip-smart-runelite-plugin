package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.offer.OfferTransition;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;

/** Pure offer lifecycle transitions. The only place offer state is decided. */
@Slf4j
public final class OfferStateMachine
{
    private OfferStateMachine() {}

    public static OfferTransition decide(OfferRecord current, OfferSignal signal, long offerIdForNew, long now)
    {
        final GrandExchangeOfferState ge = signal.geState;

        if (current == null)
        {
            if (ge == GrandExchangeOfferState.EMPTY)
            {
                return OfferTransition.of(OfferTransition.Kind.NONE, null, 0);
            }
            OfferRecord placed = OfferRecord.newOffer(offerIdForNew, signal.slot, signal.itemId,
                signal.itemName, signal.isBuy(), signal.totalQuantity, signal.price, now);
            if (signal.quantitySold > 0 || ge == GrandExchangeOfferState.BOUGHT || ge == GrandExchangeOfferState.SOLD)
            {
                return applyFillOrComplete(placed, signal, now, OfferTransition.Kind.PLACED);
            }
            return OfferTransition.of(OfferTransition.Kind.PLACED, placed, 0);
        }

        if (current.getState().isTerminal())
        {
            return OfferTransition.rejected(current);
        }

        switch (ge)
        {
            case BUYING:
            case SELLING:
            case BOUGHT:
            case SOLD:
                return applyFillOrComplete(current, signal, now, null);

            case CANCELLED_BUY:
            case CANCELLED_SELL:
            {
                OfferState target = current.getFilledQuantity() > 0
                    ? OfferState.CANCELLED_PARTIAL : OfferState.CANCELLED_EMPTY;
                return OfferTransition.of(OfferTransition.Kind.CANCELLED, current.withState(target, now), 0);
            }

            case EMPTY:
                if (current.getState() == OfferState.FILLED
                    || current.getState() == OfferState.PARTIAL_FILL
                    || current.getState() == OfferState.CANCELLED_PARTIAL)
                {
                    return OfferTransition.of(OfferTransition.Kind.COLLECTED,
                        current.withState(OfferState.COLLECTED, now), 0);
                }
                return OfferTransition.rejected(current);

            default:
                log.warn("Unhandled GE state {} for offer {}", ge, current.getOfferId());
                return OfferTransition.rejected(current);
        }
    }

    private static OfferTransition applyFillOrComplete(OfferRecord base, OfferSignal signal, long now,
                                                       OfferTransition.Kind newOverride)
    {
        int delta = signal.quantitySold - base.getFilledQuantity();
        boolean complete = signal.geState == GrandExchangeOfferState.BOUGHT
            || signal.geState == GrandExchangeOfferState.SOLD
            || signal.quantitySold >= signal.totalQuantity;
        OfferState target = complete ? OfferState.FILLED
            : (signal.quantitySold > 0 ? OfferState.PARTIAL_FILL : OfferState.NEW);

        if (delta <= 0 && target == base.getState())
        {
            return OfferTransition.of(OfferTransition.Kind.NONE, base, 0);
        }
        OfferRecord updated = base.withFill(signal.quantitySold, signal.spent, target, now);
        OfferTransition.Kind kind = complete ? OfferTransition.Kind.COMPLETED
            : (newOverride != null ? newOverride : OfferTransition.Kind.FILLED_DELTA);
        return OfferTransition.of(kind, updated, Math.max(delta, 0));
    }
}
