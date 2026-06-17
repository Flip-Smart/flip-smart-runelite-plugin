package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.offer.OfferTransition;
import com.flipsmart.trading.OfferStateMachine;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OfferStateMachineTest
{
    private static final long NOW = 1_000_000L;
    private static final long ID = 42L;

    private static OfferSignal buy(GrandExchangeOfferState s, int sold, int total)
    {
        return new OfferSignal(0, s, 1234, "Item", total, 100, sold, (long) sold * 100);
    }

    @Test
    public void placement_fromNull_isNewOffer()
    {
        OfferTransition t = OfferStateMachine.decide(null, buy(GrandExchangeOfferState.BUYING, 0, 10), ID, NOW);

        assertEquals(OfferTransition.Kind.PLACED, t.kind);
        assertEquals(OfferState.NEW, t.record.getState());
        assertEquals(ID, t.record.getOfferId());
        assertEquals(0, t.record.getFilledQuantity());
    }

    @Test
    public void partialFill_advancesToPartialAndReportsDelta()
    {
        OfferRecord placed = OfferRecord.newOffer(ID, 0, 1234, "Item", true, 10, 100, NOW);

        OfferTransition t = OfferStateMachine.decide(placed, buy(GrandExchangeOfferState.BUYING, 3, 10), ID, NOW);

        assertEquals(OfferTransition.Kind.FILLED_DELTA, t.kind);
        assertEquals(OfferState.PARTIAL_FILL, t.record.getState());
        assertEquals(3, t.record.getFilledQuantity());
        assertEquals(3, t.newlyFilledQuantity);
    }

    @Test
    public void secondPartial_reportsOnlyTheDelta()
    {
        OfferRecord partial = OfferStateMachine
            .decide(OfferRecord.newOffer(ID, 0, 1234, "Item", true, 10, 100, NOW),
                buy(GrandExchangeOfferState.BUYING, 3, 10), ID, NOW).record;

        OfferTransition t = OfferStateMachine.decide(partial, buy(GrandExchangeOfferState.BUYING, 7, 10), ID, NOW);

        assertEquals(4, t.newlyFilledQuantity);
        assertEquals(7, t.record.getFilledQuantity());
        assertEquals(OfferState.PARTIAL_FILL, t.record.getState());
    }

    @Test
    public void complete_whenBought_setsFilledAndCompletedKind()
    {
        OfferRecord partial = OfferRecord.newOffer(ID, 0, 1234, "Item", true, 10, 100, NOW);

        OfferTransition t = OfferStateMachine.decide(partial, buy(GrandExchangeOfferState.BOUGHT, 10, 10), ID, NOW);

        assertEquals(OfferTransition.Kind.COMPLETED, t.kind);
        assertEquals(OfferState.FILLED, t.record.getState());
        assertEquals(10, t.newlyFilledQuantity);
    }

    @Test
    public void collect_fromFilled_isCollectedAndClearsSlot()
    {
        OfferRecord filled = OfferStateMachine
            .decide(OfferRecord.newOffer(ID, 0, 1234, "Item", true, 10, 100, NOW),
                buy(GrandExchangeOfferState.BOUGHT, 10, 10), ID, NOW).record;

        OfferTransition t = OfferStateMachine.decide(filled, buy(GrandExchangeOfferState.EMPTY, 10, 10), ID, NOW);

        assertEquals(OfferTransition.Kind.COLLECTED, t.kind);
        assertEquals(OfferState.COLLECTED, t.record.getState());
        assertNull("collected offer no longer holds a slot", t.record.getSlot());
    }

    @Test
    public void cancelWithNoFills_isCancelledEmpty()
    {
        OfferRecord placed = OfferRecord.newOffer(ID, 0, 1234, "Item", true, 10, 100, NOW);

        OfferTransition t = OfferStateMachine.decide(placed, buy(GrandExchangeOfferState.CANCELLED_BUY, 0, 10), ID, NOW);

        assertEquals(OfferTransition.Kind.CANCELLED, t.kind);
        assertEquals(OfferState.CANCELLED_EMPTY, t.record.getState());
    }

    @Test
    public void cancelWithPartialFills_isCancelledPartial_thenCollect()
    {
        OfferRecord partial = OfferStateMachine
            .decide(OfferRecord.newOffer(ID, 0, 1234, "Item", true, 10, 100, NOW),
                buy(GrandExchangeOfferState.BUYING, 4, 10), ID, NOW).record;

        OfferTransition cancelled = OfferStateMachine.decide(partial, buy(GrandExchangeOfferState.CANCELLED_BUY, 4, 10), ID, NOW);
        assertEquals(OfferState.CANCELLED_PARTIAL, cancelled.record.getState());

        OfferTransition collected = OfferStateMachine.decide(cancelled.record, buy(GrandExchangeOfferState.EMPTY, 4, 10), ID, NOW);
        assertEquals(OfferState.COLLECTED, collected.record.getState());
        assertEquals(OfferTransition.Kind.COLLECTED, collected.kind);
    }

    @Test
    public void illegalTransition_isRejected_recordUnchanged()
    {
        OfferRecord collected = OfferStateMachine
            .decide(OfferStateMachine.decide(
                    OfferRecord.newOffer(ID, 0, 1234, "Item", true, 10, 100, NOW),
                    buy(GrandExchangeOfferState.BOUGHT, 10, 10), ID, NOW).record,
                buy(GrandExchangeOfferState.EMPTY, 10, 10), ID, NOW).record;

        OfferTransition t = OfferStateMachine.decide(collected, buy(GrandExchangeOfferState.BUYING, 3, 10), ID, NOW);

        assertEquals(OfferTransition.Kind.REJECTED, t.kind);
        assertEquals(OfferState.COLLECTED, t.record.getState());
    }

    @Test
    public void duplicateCancellation_onTerminal_isRejectedNoThrow()
    {
        OfferRecord cancelled = OfferStateMachine
            .decide(OfferRecord.newOffer(ID, 0, 1234, "Item", true, 10, 100, NOW),
                buy(GrandExchangeOfferState.CANCELLED_BUY, 0, 10), ID, NOW).record;

        OfferTransition t = OfferStateMachine.decide(cancelled, buy(GrandExchangeOfferState.CANCELLED_BUY, 0, 10), ID, NOW);

        assertEquals(OfferTransition.Kind.REJECTED, t.kind);
    }

    @Test
    public void instantFillOnPlacement_fromNull_isCompleted()
    {
        // OSRS can fill an offer the instant it is placed (large margin) — the very first
        // event we see is BOUGHT with no prior record. It must mint + complete in one step.
        OfferTransition t = OfferStateMachine.decide(null, buy(GrandExchangeOfferState.BOUGHT, 10, 10), ID, NOW);

        assertEquals(OfferTransition.Kind.COMPLETED, t.kind);
        assertEquals(OfferState.FILLED, t.record.getState());
        assertEquals(ID, t.record.getOfferId());
        assertEquals(10, t.record.getFilledQuantity());
        assertEquals(10, t.newlyFilledQuantity);
    }

    @Test
    public void emptyOnNewOffer_withNoFills_isRejected()
    {
        // A slot cleared before any fill (place-then-instant-cancel-collect in one tick)
        // is a bad/no-op signal, not a collection — must be rejected, state untouched.
        OfferRecord placed = OfferRecord.newOffer(ID, 0, 1234, "Item", true, 10, 100, NOW);

        OfferTransition t = OfferStateMachine.decide(placed, buy(GrandExchangeOfferState.EMPTY, 0, 10), ID, NOW);

        assertEquals(OfferTransition.Kind.REJECTED, t.kind);
        assertEquals(OfferState.NEW, t.record.getState());
    }

    private static OfferSignal signal(int slot, int itemId, boolean isBuy, int totalQty, int price,
                                      int qtySold, long spent, GrandExchangeOfferState geState)
    {
        return new OfferSignal(slot, geState, itemId, "Abyssal whip", totalQty, price, qtySold, spent);
    }

    @Test
    public void partialFillReportsNewlySpent()
    {
        OfferRecord placed = OfferRecord.newOffer(1, 3, 4151, "Abyssal whip", true, 5, 2_000_000, 1000L);
        OfferSignal s = signal(3, 4151, true, 5, 2_000_000, 2, 4_000_000, GrandExchangeOfferState.BUYING);
        OfferTransition t = OfferStateMachine.decide(placed, s, 1, 2000L);
        assertEquals(OfferTransition.Kind.FILLED_DELTA, t.kind);
        assertEquals(2, t.newlyFilledQuantity);
        assertEquals(4_000_000L, t.newlySpent);
    }
}
