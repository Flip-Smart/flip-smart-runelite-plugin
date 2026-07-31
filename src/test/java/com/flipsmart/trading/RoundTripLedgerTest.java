package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RoundTripLedgerTest
{
    private static final String RSN = "Zezima";
    private static final int ITEM = 4151;

    private RoundTripLedger ledger;

    @Before
    public void setUp()
    {
        ledger = new RoundTripLedger();
    }

    @Test
    public void basis_isWeightedAcrossTheOpenCycle()
    {
        ledger.recordBuyBasis(RSN, ITEM, 10, 100);
        ledger.recordBuyBasis(RSN, ITEM, 10, 140);

        assertEquals(Integer.valueOf(120), ledger.currentBasis(RSN, ITEM));
    }

    @Test
    public void basis_isAbsentBeforeAnyBuy()
    {
        assertNull("an untouched item has no basis", ledger.currentBasis(RSN, ITEM));
    }

    @Test
    public void basis_resetsWhenTheCycleCloses()
    {
        ledger.recordBuyBasis(RSN, ITEM, 10, 100);
        ledger.recordFill(RSN, ITEM, true, 10);
        ledger.recordFill(RSN, ITEM, false, 10);   // fully liquidated: cycle closes

        assertNull("a closed cycle carries no basis", ledger.currentBasis(RSN, ITEM));

        ledger.recordBuyBasis(RSN, ITEM, 5, 200);
        assertEquals("the next cycle starts clean",
            Integer.valueOf(200), ledger.currentBasis(RSN, ITEM));
    }

    @Test
    public void basis_survivesAPartialLiquidation()
    {
        ledger.recordBuyBasis(RSN, ITEM, 10, 100);
        ledger.recordFill(RSN, ITEM, true, 10);
        ledger.recordFill(RSN, ITEM, false, 3);    // holdings still 7, cycle stays open

        assertEquals("a partial sell does not disturb the buy-side basis",
            Integer.valueOf(100), ledger.currentBasis(RSN, ITEM));
    }

    @Test
    public void basis_ignoresAZeroPrice()
    {
        // A placement row carries no price; folding it in would drag the average toward zero.
        ledger.recordBuyBasis(RSN, ITEM, 10, 0);

        assertNull("a priceless fill contributes no basis", ledger.currentBasis(RSN, ITEM));
    }

    @Test
    public void cleanTwoCycleSequence_producesTwoDistinctIds()
    {
        int buyLow = ledger.recordFill(RSN, ITEM, true, 10);
        int sellLow = ledger.recordFill(RSN, ITEM, false, 10);
        int buyHigh = ledger.recordFill(RSN, ITEM, true, 10);
        int sellHigh = ledger.recordFill(RSN, ITEM, false, 10);

        assertEquals("buy and sell of the same lot share the cycle", buyLow, sellLow);
        assertEquals("buy and sell of the second lot share the cycle", buyHigh, sellHigh);
        assertNotEquals("liquidating the first lot starts a new cycle for the second",
            sellLow, buyHigh);
    }

    @Test
    public void partialFillsWithinACycle_shareTheSameId()
    {
        int fill1 = ledger.recordFill(RSN, ITEM, true, 3);
        int fill2 = ledger.recordFill(RSN, ITEM, true, 2);
        int sellPartial = ledger.recordFill(RSN, ITEM, false, 4);
        int sellRest = ledger.recordFill(RSN, ITEM, false, 1);

        assertEquals(fill1, fill2);
        assertEquals(fill1, sellPartial);
        assertEquals(fill1, sellRest);
    }

    @Test
    public void overSell_isTreatedAsLiquidationAndStartsNewCycle()
    {
        int buy = ledger.recordFill(RSN, ITEM, true, 5);
        // Sells more than the ledger has recorded as held (e.g. externally-sourced stock) —
        // still closes the cycle rather than leaving heldQuantity permanently negative.
        int oversell = ledger.recordFill(RSN, ITEM, false, 8);
        assertEquals(buy, oversell);

        int nextBuy = ledger.recordFill(RSN, ITEM, true, 1);
        assertNotEquals("the cycle after an over-sell is a fresh one", oversell, nextBuy);
    }

    @Test
    public void oversellPastZeroDoesNotRepeatedlyReclose()
    {
        // A fully-liquidated position (held at zero) that keeps receiving sell fills — overselling
        // externally-sourced stock, or a ledger drifted low — must not re-close the cycle on every
        // fill and run the round-trip id away.
        ledger.recordFill(RSN, ITEM, 0, true, 5, 5, true);
        int sell = ledger.recordFill(RSN, ITEM, 0, false, 5, 5, true);   // held 5 -> 0: one genuine close
        int afterFirstClose = ledger.peekRoundTripId(RSN, ITEM);
        assertNotEquals("the genuine liquidation closes the cycle once", sell, afterFirstClose);

        // Extra sell fills arriving while held is already zero must NOT advance the cycle again.
        ledger.recordFill(RSN, ITEM, 1, false, 100, 100, false);
        ledger.recordFill(RSN, ITEM, 1, false, 100, 200, false);
        assertEquals("overselling past zero does not repeatedly re-close the cycle",
            afterFirstClose, (int) ledger.peekRoundTripId(RSN, ITEM));
    }

    @Test
    public void duplicateSellFill_cannotPrematurelyAdvanceCycle()
    {
        // One clean lot on slot 0: buy 10, sell 10 — held returns to zero, cycle closes.
        int buy = ledger.recordFill(RSN, ITEM, 0, true, 10, 10, true);
        int sell = ledger.recordFill(RSN, ITEM, 0, false, 10, 10, true);
        assertEquals("buy and sell of one lot share the round trip", buy, sell);

        int afterLiquidation = ledger.peekRoundTripId(RSN, ITEM);
        assertNotEquals("a clean liquidation opens the next cycle", sell, afterLiquidation);

        // A phantom re-delivery of the exact closing sell (same slot + direction + cumulative) reaches
        // the ledger before client-side suppression catches it. It must NOT drive held negative and
        // advance the cycle a second time.
        int phantom = ledger.recordFill(RSN, ITEM, 0, false, 10, 10, true);
        assertEquals("the duplicate is ignored and returns the still-open cycle id",
            afterLiquidation, phantom);
        assertEquals("the duplicate did not advance the cycle",
            afterLiquidation, (int) ledger.peekRoundTripId(RSN, ITEM));

        // The next genuine buy opens the un-poisoned cycle the phantom tried to skip past.
        int nextBuy = ledger.recordFill(RSN, ITEM, 0, true, 10, 10, true);
        assertEquals("the round trip after a phantom is the one the phantom tried to skip",
            afterLiquidation, nextBuy);
    }

    @Test
    public void concurrentSameItemInTwoSlots_shareCycleAndAreNotFalselyDeduped()
    {
        int buyA = ledger.recordFill(RSN, ITEM, 0, true, 10, 10, true);
        int buyB = ledger.recordFill(RSN, ITEM, 1, true, 5, 5, true);
        assertEquals("two open buys of the same item are one round trip", buyA, buyB);

        int sellA = ledger.recordFill(RSN, ITEM, 0, false, 10, 10, true);
        assertEquals("selling one slot while the other is still open stays in the cycle",
            buyA, sellA);

        int sellB = ledger.recordFill(RSN, ITEM, 1, false, 5, 5, true);
        assertEquals("only the final zero-cross closes it — still the same round trip",
            buyA, sellB);

        int next = ledger.peekRoundTripId(RSN, ITEM);
        assertNotEquals("the fully liquidated position opens the next cycle", sellB, next);
    }

    @Test
    public void identicalFillInASubsequentCycle_isNotFalselySuppressed()
    {
        ledger.recordFill(RSN, ITEM, 0, true, 10, 10, true);
        int firstSell = ledger.recordFill(RSN, ITEM, 0, false, 10, 10, true);

        // A genuine new position reusing the same slot and quantity builds held again: completing the
        // first lot reset slot 0's baselines, so the new cycle's fills are absorbed from zero —
        // suppression only ever catches a re-delivery of an already-absorbed cumulative within an
        // open offer.
        int secondBuy = ledger.recordFill(RSN, ITEM, 0, true, 10, 10, true);
        assertNotEquals("the new cycle is distinct from the closed one", firstSell, secondBuy);

        int secondSell = ledger.recordFill(RSN, ITEM, 0, false, 10, 10, true);
        assertEquals("the genuine repeat sell is applied within its own cycle",
            secondBuy, secondSell);

        int third = ledger.peekRoundTripId(RSN, ITEM);
        assertNotEquals("the genuine repeat still closes its cycle normally", secondSell, third);
    }

    @Test
    public void closingOneSlotDoesNotWipeAStillFillingSiblingSlot()
    {
        // Same item in two slots: slot 0 buys 100 (offer complete), slot 1 buys 100 but is STILL
        // FILLING (offer not complete), so slot 1's baseline must be retained.
        ledger.recordFill(RSN, ITEM, 0, true, 100, 100, true);
        ledger.recordFill(RSN, ITEM, 1, true, 100, 100, false);

        // A 200-unit sell on slot 0 liquidates the position: held hits zero and the cycle closes while
        // slot 1's buy is still open. Resetting every slot on close (rather than only completed offers'
        // slots) would wipe slot 1's baseline, so its next fill would re-count its whole cumulative.
        ledger.recordFill(RSN, ITEM, 0, false, 200, 200, true);

        // slot 1's buy fills 50 more (cumulative 100 -> 150): only 50 new units should enter held.
        ledger.recordFill(RSN, ITEM, 1, true, 50, 150, true);

        // If the sibling baseline survived, held is exactly 50, so selling 50 closes the next cycle.
        // If it was wiped, held would be 150 and this sell would leave 100 open — no close.
        int sellRt = ledger.recordFill(RSN, ITEM, 1, false, 50, 50, true);
        int after = ledger.peekRoundTripId(RSN, ITEM);
        assertNotEquals("a still-filling sibling slot keeps its baseline through another slot's close",
            sellRt, after);
    }

    @Test
    public void relogDoesNotDoubleCountAnOpenBuy()
    {
        // Session 1: a still-open buy fills to cumulative 1324 on slot 4 — held becomes 1324. Not
        // complete, so its baseline is retained for the re-feed to match against.
        ledger.recordFill(RSN, ITEM, 4, true, 1324, 1324, false);

        // Persist + restart (relog / rebuild): held, cycle, and per-slot absorbed cumulative restore.
        Map<Integer, RoundTripLedger.Entry> exported = ledger.export(RSN);
        RoundTripLedger restarted = new RoundTripLedger();
        restarted.importState(RSN, exported);

        // Login reconciliation re-feeds the still-open offer's full current cumulative, because the
        // rebuilt OfferStore has no baseline yet and the GE signal looks like a fresh 1324 fill. A
        // relog mints a fresh offer_id for the slot, so the guard must match on (slot, cumulative)
        // alone — never offer_id — or this is added on top of the restored held (the double-count).
        restarted.recordFill(RSN, ITEM, 4, true, 1324, 1324, false);

        // Proof the true held is still 1324, not 2648: selling exactly 1324 (completing) closes the cycle.
        int cycleBefore = restarted.peekRoundTripId(RSN, ITEM);
        restarted.recordFill(RSN, ITEM, 4, false, 1324, 1324, true);
        int cycleAfter = restarted.peekRoundTripId(RSN, ITEM);
        assertNotEquals("selling the true held must close the round trip — no relog double-count",
            cycleBefore, cycleAfter);
    }

    @Test
    public void differentItems_areTrackedIndependently()
    {
        int itemABuy = ledger.recordFill(RSN, ITEM, true, 5);
        int itemBBuy = ledger.recordFill(RSN, 1305, true, 5);
        assertEquals("a fresh item starts at the same initial cycle id as any other fresh item",
            itemABuy, itemBBuy);

        ledger.recordFill(RSN, ITEM, false, 5);
        int itemBStillOpen = ledger.recordFill(RSN, 1305, true, 1);
        assertEquals("liquidating item A must not roll item B's still-open cycle forward",
            itemBBuy, itemBStillOpen);
    }

    @Test
    public void nullOrEmptyRsn_returnsNullAndDoesNotThrow()
    {
        assertNull(ledger.recordFill(null, ITEM, true, 5));
        assertNull(ledger.recordFill("", ITEM, true, 5));
        assertNull(ledger.peekRoundTripId(null, ITEM));
    }

    @Test
    public void peekRoundTripId_doesNotMutateHeldQuantityOrCycle()
    {
        int buy = ledger.recordFill(RSN, ITEM, true, 5);
        Integer peeked = ledger.peekRoundTripId(RSN, ITEM);
        assertEquals(Integer.valueOf(buy), peeked);

        // Peeking repeatedly must never itself close the cycle.
        ledger.peekRoundTripId(RSN, ITEM);
        ledger.peekRoundTripId(RSN, ITEM);
        int sell = ledger.recordFill(RSN, ITEM, false, 5);
        assertEquals("cycle unaffected by peeks", buy, sell);
    }

    @Test
    public void relogMidCycle_importedStatePreservesTheOpenId()
    {
        int buy = ledger.recordFill(RSN, ITEM, true, 7);

        // Simulate a plugin restart: persist the entry, build a fresh ledger, restore it.
        Map<Integer, RoundTripLedger.Entry> exported = ledger.export(RSN);
        assertFalse(exported.isEmpty());

        RoundTripLedger restarted = new RoundTripLedger();
        restarted.importState(RSN, exported);

        int sellAfterRelog = restarted.recordFill(RSN, ITEM, false, 7);
        assertEquals("the id from before the relog is preserved on the imported ledger",
            buy, sellAfterRelog);

        int nextCycleBuy = restarted.recordFill(RSN, ITEM, true, 1);
        assertNotEquals("a new cycle still starts correctly after the restored one closes",
            sellAfterRelog, nextCycleBuy);
    }

    @Test
    public void importState_withNullOrEmptyRsnOrEntries_isNoOp()
    {
        ledger.importState(null, Collections.singletonMap(ITEM, new RoundTripLedger.Entry(5, 1)));
        ledger.importState(RSN, null);
        ledger.importState(RSN, Collections.emptyMap());
        assertTrue("no state materialized for RSN from any no-op import call", ledger.isEmpty(RSN));
    }

    @Test
    public void seedColdStart_onlyAppliesWhenNoStateExistsYet()
    {
        OfferRecord liveBuy = OfferRecord.newOffer(1L, 0, ITEM, "Abyssal whip", true, 50, 1_000, 1_000L)
            .withFill(30, 30_000L, OfferState.PARTIAL_FILL, 1_500L);

        ledger.seedColdStart(RSN, Collections.singletonList(liveBuy));
        assertFalse(ledger.isEmpty(RSN));

        // A sell of exactly the seeded quantity closes the cycle immediately — proving the
        // seed set heldQuantity to 30, not 0.
        int seededCycle = ledger.peekRoundTripId(RSN, ITEM);
        int sell = ledger.recordFill(RSN, ITEM, false, 30);
        assertEquals(seededCycle, sell);

        int afterSeededCycle = ledger.peekRoundTripId(RSN, ITEM);
        assertNotEquals("selling the seeded amount closed the seeded cycle", seededCycle, afterSeededCycle);
    }

    @Test
    public void seedColdStart_isNoOpWhenLedgerAlreadyHasState()
    {
        ledger.recordFill(RSN, ITEM, true, 5);
        Map<Integer, RoundTripLedger.Entry> before = ledger.export(RSN);

        OfferRecord liveBuy = OfferRecord.newOffer(1L, 0, ITEM, "Abyssal whip", true, 999, 1_000, 1_000L)
            .withFill(999, 999_000L, OfferState.PARTIAL_FILL, 1_500L);
        ledger.seedColdStart(RSN, Collections.singletonList(liveBuy));

        assertEquals("an already-populated ledger is never overwritten by cold-start seeding",
            before.get(ITEM).heldQuantity, ledger.export(RSN).get(ITEM).heldQuantity);
    }

    @Test
    public void seedColdStart_ignoresSellRecordsAndZeroFillBuys()
    {
        OfferRecord sellRecord = OfferRecord.newOffer(2L, 1, ITEM, "Abyssal whip", false, 10, 1_000, 1_000L)
            .withFill(10, 10_000L, OfferState.FILLED, 1_500L);
        OfferRecord zeroFillBuy = OfferRecord.newOffer(3L, 2, 1305, "Dragon longsword", true, 10, 1_000, 1_000L);

        ledger.seedColdStart(RSN, java.util.Arrays.asList(sellRecord, zeroFillBuy));
        assertTrue("neither a sell nor an unfilled buy contributes held quantity",
            ledger.isEmpty(RSN));
    }
}
