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
