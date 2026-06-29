package com.flipsmart.recommend;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ActionResolverTest {

    private final ActionResolver resolver = new ActionResolver();

    // ---- builders ----
    private static OfferRecord filledBuy(int slot, int itemId, long activity) {
        return OfferRecord.newOffer(slot + 1, slot, itemId, "buy" + itemId, true, 10, 100, 0L)
            .withFill(10, 1000L, OfferState.FILLED, activity);
    }
    private static OfferRecord filledSell(int slot, int itemId, long activity) {
        return OfferRecord.newOffer(slot + 1, slot, itemId, "sell" + itemId, false, 10, 200, 0L)
            .withFill(10, 2000L, OfferState.FILLED, activity);
    }
    private static OfferRecord cancelledPartialBuy(int slot, int itemId, long activity) {
        return OfferRecord.newOffer(slot + 1, slot, itemId, "buy" + itemId, true, 10, 100, 0L)
            .withFill(4, 400L, OfferState.CANCELLED_PARTIAL, activity);
    }
    private static OfferRecord staleBuyPartial(int slot, int itemId, long activity) {
        return OfferRecord.newOffer(slot + 1, slot, itemId, "buy" + itemId, true, 10, 100, 0L)
            .withFill(3, 300L, OfferState.PARTIAL_FILL, activity);
    }
    private static OfferRecord staleBuyZeroFill(int slot, int itemId, long activity) {
        return OfferRecord.newOffer(slot + 1, slot, itemId, "buy" + itemId, true, 10, 100, activity);
    }
    private static OfferRecord staleSell(int slot, int itemId, long activity) {
        return OfferRecord.newOffer(slot + 1, slot, itemId, "sell" + itemId, false, 10, 200, 0L)
            .withFill(0, 0L, OfferState.NEW, activity);
    }
    private static ResolverInput.Builder base() {
        return ResolverInput.builder()
            .slotLimit(8).filledSlotCount(8).surfaceableBuy(false, -1).nowMillis(100_000L)
            .completedAwaitingCollection(new ArrayList<>())
            .staleOffers(new ArrayList<>())
            .collectedAwaitingList(new ArrayList<>());
    }

    // ---- each tier in isolation ----
    @Test public void s1_fromStalePartialBuy() {
        ResolverInput in = base().staleOffers(Arrays.asList(staleBuyPartial(0, 11, 5L))).build();
        ActionDecision d = resolver.resolve(in);
        assertEquals(ActionKind.S1, d.getKind());
        assertEquals(ActionStep.CANCEL, d.getStep());
        assertEquals(11, d.getItemId());
    }
    @Test public void s1_fromCancelledPartialAwaitingCollect() {
        ResolverInput in = base()
            .completedAwaitingCollection(Arrays.asList(cancelledPartialBuy(0, 12, 5L))).build();
        ActionDecision d = resolver.resolve(in);
        assertEquals(ActionKind.S1, d.getKind());
        assertEquals(ActionStep.COLLECT, d.getStep());
    }
    @Test public void sellWaiting_fromCollectedPartialAwaitingList() {
        ResolverInput in = base().filledSlotCount(7).collectedAwaitingList(Arrays.asList(
            new CollectedItem(13, CollectOrigin.PARTIAL_CANCEL, true, 5L))).build();
        ActionDecision d = resolver.resolve(in);
        assertEquals(ActionKind.SELL_WAITING, d.getKind());
        assertEquals(ActionStep.LIST, d.getStep());
    }
    @Test public void s2_fromEmptySlotWithSurfaceableBuy() {
        ResolverInput in = base().filledSlotCount(7).surfaceableBuy(true, 21).build();
        ActionDecision d = resolver.resolve(in);
        assertEquals(ActionKind.S2, d.getKind());
        assertEquals(ActionStep.PLACE_BUY, d.getStep());
        assertEquals(21, d.getItemId());
    }
    @Test public void s3_fromFilledBuy() {
        ResolverInput in = base().completedAwaitingCollection(
            Arrays.asList(filledBuy(0, 31, 5L))).build();
        assertEquals(ActionKind.S3, resolver.resolve(in).getKind());
        assertEquals(ActionStep.COLLECT, resolver.resolve(in).getStep());
    }
    @Test public void sellWaiting_fromCollectedCompletedBuyAwaitingList() {
        ResolverInput in = base().filledSlotCount(7).collectedAwaitingList(Arrays.asList(
            new CollectedItem(32, CollectOrigin.COMPLETED_BUY, true, 5L))).build();
        ActionDecision d = resolver.resolve(in);
        assertEquals(ActionKind.SELL_WAITING, d.getKind());
        assertEquals(ActionStep.LIST, d.getStep());
    }
    @Test public void s4_fromStaleSell() {
        ResolverInput in = base().staleOffers(Arrays.asList(staleSell(0, 41, 5L))).build();
        assertEquals(ActionKind.S4, resolver.resolve(in).getKind());
        assertEquals(ActionStep.REPRICE, resolver.resolve(in).getStep());
    }
    @Test public void s5_fromFilledSell() {
        ResolverInput in = base().completedAwaitingCollection(
            Arrays.asList(filledSell(0, 51, 5L))).build();
        assertEquals(ActionKind.S5, resolver.resolve(in).getKind());
        assertEquals(ActionStep.COLLECT, resolver.resolve(in).getStep());
    }
    @Test public void s6_fromStaleZeroFillBuy() {
        ResolverInput in = base().staleOffers(Arrays.asList(staleBuyZeroFill(0, 61, 5L))).build();
        assertEquals(ActionKind.S6, resolver.resolve(in).getKind());
        assertEquals(ActionStep.CANCEL, resolver.resolve(in).getStep());
    }

    // ---- adjacent-tier preemption ----
    @Test public void s1_preempts_s2() {
        ResolverInput in = base().filledSlotCount(7).surfaceableBuy(true, 99)
            .staleOffers(Arrays.asList(staleBuyPartial(0, 11, 5L))).build();
        assertEquals(ActionKind.S1, resolver.resolve(in).getKind());
    }
    @Test public void s2_preempts_s3() {  // product-confirmed: fill empty slot before collecting buy
        ResolverInput in = base().filledSlotCount(7).surfaceableBuy(true, 99)
            .completedAwaitingCollection(Arrays.asList(filledBuy(0, 31, 5L))).build();
        assertEquals(ActionKind.S2, resolver.resolve(in).getKind());
    }
    @Test public void s3_preempts_s4() {
        ResolverInput in = base()
            .completedAwaitingCollection(Arrays.asList(filledBuy(0, 31, 5L)))
            .staleOffers(Arrays.asList(staleSell(1, 41, 5L))).build();
        assertEquals(ActionKind.S3, resolver.resolve(in).getKind());
    }
    @Test public void s4_preempts_s5() {
        ResolverInput in = base()
            .staleOffers(Arrays.asList(staleSell(0, 41, 5L)))
            .completedAwaitingCollection(Arrays.asList(filledSell(1, 51, 5L))).build();
        assertEquals(ActionKind.S4, resolver.resolve(in).getKind());
    }
    @Test public void s5_preempts_s6() {
        ResolverInput in = base()
            .completedAwaitingCollection(Arrays.asList(filledSell(0, 51, 5L)))
            .staleOffers(Arrays.asList(staleBuyZeroFill(1, 61, 5L))).build();
        assertEquals(ActionKind.S5, resolver.resolve(in).getKind());
    }

    // ---- FIFO within a tier ----
    @Test public void fifoWithinTierEarliestDetectedWins() {
        ResolverInput in = base().completedAwaitingCollection(Arrays.asList(
            filledSell(0, 501, 9000L),   // later activity
            filledSell(1, 502, 1000L))).build();  // earlier activity → should win
        assertEquals(502, resolver.resolve(in).getItemId());
    }
    @Test public void tierBeatsTimestamp() {
        // a very old S5 must still lose to a fresh S3
        ResolverInput in = base().completedAwaitingCollection(Arrays.asList(
            filledSell(0, 501, 1L),      // ancient S5
            filledBuy(1, 301, 9_999L))). // fresh S3
            build();
        assertEquals(ActionKind.S3, resolver.resolve(in).getKind());
    }
    @Test public void sameTierSameTimestampSmallestSlotWins() {
        ResolverInput in = base().completedAwaitingCollection(Arrays.asList(
            filledSell(5, 505, 1000L),
            filledSell(2, 502, 1000L))).build();
        assertEquals(502, resolver.resolve(in).getItemId());
    }
    @Test public void s1CancelOutranksWaitingSell() {
        // A partially-filled stale buy (S1 CANCEL) is more urgent than listing a held item.
        ResolverInput in = base().filledSlotCount(7)
            .staleOffers(Arrays.asList(staleBuyPartial(3, 303, 1000L)))
            .collectedAwaitingList(Arrays.asList(
                new CollectedItem(909, CollectOrigin.PARTIAL_CANCEL, true, 1000L)))
            .build();
        ActionDecision d = resolver.resolve(in);
        assertEquals(303, d.getItemId());
        assertEquals(ActionStep.CANCEL, d.getStep());
    }

    // ---- S2 gating ----
    @Test public void noS2WhenAllSlotsFull() {
        ResolverInput in = base().filledSlotCount(8).surfaceableBuy(true, 21).build();
        assertEquals(ActionDecision.IDLE, resolver.resolve(in));
    }
    @Test public void noS2WhenNoSurfaceableBuy() {
        ResolverInput in = base().filledSlotCount(5).surfaceableBuy(false, -1).build();
        assertEquals(ActionDecision.IDLE, resolver.resolve(in));
    }

    // ---- list-step requires a sell price (no empty label) ----
    @Test public void collectedItemWithoutSellPriceIsNotSurfaced() {
        ResolverInput in = base().filledSlotCount(7).collectedAwaitingList(Arrays.asList(
            new CollectedItem(13, CollectOrigin.PARTIAL_CANCEL, false, 5L))).build();
        assertEquals(ActionDecision.IDLE, resolver.resolve(in));
    }

    // ---- #814 AC1-4: sell-priority around GE slot availability ----
    @Test public void ac1_noSellPromptWhenAllSlotsFull() {
        // 8/8 slots filled → a held item awaiting list must NOT surface (no slot to act on).
        ResolverInput in = base().filledSlotCount(8).collectedAwaitingList(Arrays.asList(
            new CollectedItem(70, CollectOrigin.COMPLETED_BUY, true, 5L))).build();
        assertEquals(ActionDecision.IDLE, resolver.resolve(in));
    }
    @Test public void ac2_waitingSellBeatsNewBuyWhenSlotFree() {
        // A freed slot with both a surfaceable buy and a held item → the waiting sell wins.
        ResolverInput in = base().filledSlotCount(7).surfaceableBuy(true, 99)
            .collectedAwaitingList(Arrays.asList(
                new CollectedItem(71, CollectOrigin.COMPLETED_BUY, true, 5L))).build();
        ActionDecision d = resolver.resolve(in);
        assertEquals(ActionKind.SELL_WAITING, d.getKind());
        assertEquals(ActionStep.LIST, d.getStep());
        assertEquals(71, d.getItemId());
    }
    @Test public void ac4_oldestWaitingSellTakesPriority() {
        // Two held items awaiting list → the one waiting longest (oldest) surfaces first.
        ResolverInput in = base().filledSlotCount(7).collectedAwaitingList(Arrays.asList(
            new CollectedItem(72, CollectOrigin.COMPLETED_BUY, true, 9000L),   // newer
            new CollectedItem(73, CollectOrigin.PARTIAL_CANCEL, true, 1000L)))  // older → wins
            .build();
        assertEquals(73, resolver.resolve(in).getItemId());
    }

    // ---- idle ----
    @Test public void idleWhenNothingActionable() {
        assertEquals(ActionDecision.IDLE, resolver.resolve(base().build()));
    }

    // ---- determinism (anti-flap) ----
    @Test public void identicalInputYieldsIdenticalDecision() {
        ResolverInput in = base()
            .staleOffers(Arrays.asList(staleBuyPartial(0, 11, 5L))).build();
        assertEquals(resolver.resolve(in), resolver.resolve(in));
    }
}
