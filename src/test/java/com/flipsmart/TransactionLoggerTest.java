package com.flipsmart;

import com.flipsmart.api.dto.TransactionRequest;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.offer.OfferTransition;
import com.flipsmart.trading.OfferEvent;
import com.flipsmart.trading.OfferEventMapper;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.trading.RoundTripLedger;
import com.flipsmart.trading.TransactionLogger;
import com.flipsmart.trading.TransactionLogger.Type;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TransactionLoggerTest
{
    private static final String RSN = "Zezima";

    private FlipSmartApiClient apiClient;
    private PlayerSession session;
    private RoundTripLedger roundTripLedger;

    @Before
    public void setUp()
    {
        apiClient = mock(FlipSmartApiClient.class);
        session = mock(PlayerSession.class);
        roundTripLedger = new RoundTripLedger();
        when(apiClient.recordTransactionAsync(any(TransactionRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(session.getRecommendedPrice(anyInt())).thenReturn(null);
    }

    private TransactionLogger newLogger(String rsn)
    {
        Supplier<Optional<String>> supplier = () -> Optional.of(rsn);
        return new TransactionLogger(apiClient, session, supplier, roundTripLedger);
    }

    private TransactionRequest capture()
    {
        ArgumentCaptor<TransactionRequest> cap = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(apiClient, times(1)).recordTransactionAsync(cap.capture());
        return cap.getValue();
    }

    private List<TransactionRequest> captureAll()
    {
        ArgumentCaptor<TransactionRequest> cap = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(apiClient, atLeastOnce()).recordTransactionAsync(cap.capture());
        return cap.getAllValues();
    }

    private void verifyNoTransactionRecorded()
    {
        verify(apiClient, never()).recordTransactionAsync(any(TransactionRequest.class));
    }

    @Test
    public void keyIsDeterministicPerRecord()
    {
        OfferRecord r = OfferRecord.newOffer(5, 3, 4151, "Abyssal whip", true, 5, 2_000_000, 1700000000000L)
            .withFill(1, 2_000_000L, OfferState.PARTIAL_FILL, 1700000000500L);
        assertEquals(RSN + ":5:1700000000000:FILL:1", TransactionLogger.idempotencyKey(RSN, r, Type.FILL));
    }

    @Test
    public void placedRecordsPlacementForSell()
    {
        TransactionLogger logger = newLogger(RSN);
        OfferRecord r = OfferRecord.newOffer(7, 2, 1515, "Yew logs", false, 100, 300, 1700000000000L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.PLACED, r, 0, 0));
        TransactionRequest req = capture();
        assertEquals(1515, req.itemId);
        assertFalse(req.isBuy);
        assertEquals(0, req.quantity);
        assertEquals(RSN + ":7:1700000000000:PLACE:0", req.idempotencyKey);
    }

    @Test
    public void filledDeltaRecordsFillWithPricePerItem()
    {
        TransactionLogger logger = newLogger(RSN);
        OfferRecord r = OfferRecord.newOffer(5, 3, 4151, "Abyssal whip", true, 5, 2_000_000, 1700000000000L)
            .withFill(2, 4_000_000L, OfferState.PARTIAL_FILL, 1700000000500L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.FILLED_DELTA, r, 2, 4_000_000L));
        TransactionRequest req = capture();
        assertEquals(2, req.quantity);
        assertEquals(2_000_000, req.pricePerItem);
        assertEquals(RSN + ":5:1700000000000:FILL:2", req.idempotencyKey);
    }

    @Test
    public void offlineCompletedTailInSlotOnLoginRecordsDelta()
    {
        // #759 case 1: a 30,000 sell partially filled to 20,907 online (persisted),
        // then completed offline to 30,000. On next login the offer is still in its
        // slot (OSRS does not auto-collect). With the persisted baseline preloaded
        // into OfferStore, the login signal must record ONLY the 9,093 offline delta
        // — not 0 (the loss this fix targets) and not 30,000 (a double count).
        OfferStore store = new OfferStore();
        TransactionLogger logger = newLogger(RSN);
        store.addListener(logger::onOfferEvent);

        // OfflineSyncService.preloadPersistedOffers seeds the store WITHOUT firing
        // listeners (no spurious record); model that with importRecords.
        OfferRecord persisted = OfferRecord
            .newOffer(42L, 3, 28924, "Sunfire splinters", false, 30000, 384, 1700000000000L)
            .withFill(20907, 20907L * 384, OfferState.PARTIAL_FILL, 1700000000500L);
        store.importRecords(java.util.Collections.singletonList(persisted));

        // Login burst: the offer now reads 30,000 sold (the tail filled offline).
        store.apply(
            OfferEventMapper.toSignal(3, GrandExchangeOfferState.SOLD, 28924,
                "Sunfire splinters", 30000, 384, 30000, 30000L * 384),
            1700000600000L);

        TransactionRequest req = capture();
        assertEquals(9093, req.quantity);
        assertFalse(req.isBuy);
        assertEquals(28924, req.itemId);
        assertEquals(384, req.pricePerItem);
    }

    @Test
    public void fillRecordsOfferIdFromRecord()
    {
        // #759: every recorded fill carries the OfferStore offerId so the backend
        // can reconcile fills of the same offer per-offer (not summed at item level).
        TransactionLogger logger = newLogger(RSN);
        OfferRecord r = OfferRecord.newOffer(77, 3, 4151, "Abyssal whip", true, 5, 2_000_000, 1700000000000L)
            .withFill(2, 4_000_000L, OfferState.PARTIAL_FILL, 1700000000500L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.FILLED_DELTA, r, 2, 4_000_000L));
        TransactionRequest req = capture();
        assertEquals(Long.valueOf(77), req.offerId);
    }

    @Test
    public void collectedAndNoneAndRejectedRecordNothing()
    {
        TransactionLogger logger = newLogger(RSN);
        OfferRecord r = OfferRecord.newOffer(5, 3, 4151, "x", true, 5, 1, 1L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.COLLECTED, r, 0, 0));
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.NONE, r, 0, 0));
        verifyNoTransactionRecorded();
    }

    @Test
    public void placedWithImmediateFillRecordsOnlyTheFill()
    {
        // A PLACED event already carrying a fill is an immediate/offline fill: record the fill
        // only, with no separate qty-0 placement row. This matches the single fill the old
        // recording path produced and avoids a phantom placement row for already-filling offers.
        TransactionLogger logger = newLogger(RSN);
        OfferRecord r = OfferRecord.newOffer(5, 3, 4151, "x", true, 5, 2_000_000, 1700000000000L)
            .withFill(1, 2_000_000L, OfferState.PARTIAL_FILL, 1700000000500L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.PLACED, r, 1, 2_000_000L));
        List<TransactionRequest> reqs = captureAll();
        assertEquals(1, reqs.size());
        assertEquals(1, reqs.get(0).quantity);
        assertTrue(reqs.get(0).idempotencyKey.endsWith("FILL:1"));
    }

    @Test
    public void inFlightGuardSuppressesImmediateRetry()
    {
        TransactionLogger logger = newLogger(RSN);
        OfferRecord r = OfferRecord.newOffer(5, 3, 4151, "x", true, 5, 2_000_000, 1700000000000L)
            .withFill(1, 2_000_000L, OfferState.PARTIAL_FILL, 1700000000500L);
        OfferEvent e = new OfferEvent(OfferTransition.Kind.FILLED_DELTA, r, 1, 2_000_000L);
        logger.onOfferEvent(e);
        logger.onOfferEvent(e);
        assertEquals(1, captureAll().size());
    }

    @Test
    public void collectReDetectionUnderNewOfferIdIsSuppressed()
    {
        // Collect vacates the GE slot; the same physical fill is re-reported under
        // a NEW offerId + createdAt, so the offerId/timestamp-based key churns and
        // the send isn't suppressed (the Cow/Ray over-count). The same logical fill
        // — same item, side, round trip and cumulative — must record only once.
        TransactionLogger logger = newLogger(RSN);
        OfferRecord first = OfferRecord.newOffer(8, 3, 11926, "Odium ward", true, 6, 4_000_000, 1700000000000L)
            .withFill(2, 8_000_000L, OfferState.PARTIAL_FILL, 1700000000500L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.FILLED_DELTA, first, 2, 8_000_000L));

        OfferRecord reDetected = OfferRecord.newOffer(12, 3, 11926, "Odium ward", true, 6, 4_000_000, 1700000200000L)
            .withFill(2, 8_000_000L, OfferState.PARTIAL_FILL, 1700000200500L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.FILLED_DELTA, reDetected, 2, 8_000_000L));

        assertEquals(1, captureAll().size());
    }

    @Test
    public void rejectedRecordsNothing()
    {
        TransactionLogger logger = newLogger(RSN);
        OfferRecord r = OfferRecord.newOffer(5, 3, 4151, "x", true, 5, 1, 1L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.REJECTED, r, 0, 0));
        verifyNoTransactionRecorded();
    }

    @Test
    public void cancelledWithResidualFillRecordsFill()
    {
        TransactionLogger logger = newLogger(RSN);
        OfferRecord r = OfferRecord.newOffer(5, 3, 4151, "Abyssal whip", true, 5, 2_000_000, 1700000000000L)
            .withFill(3, 4_500L, OfferState.CANCELLED_PARTIAL, 1700000000500L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.CANCELLED, r, 3, 4_500L));
        TransactionRequest req = capture();
        assertEquals(3, req.quantity);
        assertEquals(1500, req.pricePerItem);
        assertTrue(req.idempotencyKey.endsWith("FILL:3"));
    }

    // #893: every sent transaction is stamped with the round-trip id from the shared ledger.

    @Test
    public void twoCycleSequence_producesTwoDistinctRoundTripIds()
    {
        TransactionLogger logger = newLogger(RSN);
        int itemId = 4151;

        OfferRecord buyLow = OfferRecord.newOffer(1, 0, itemId, "Abyssal whip", true, 5, 1_000, 1L)
            .withFill(5, 5_000L, OfferState.FILLED, 2L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.COMPLETED, buyLow, 5, 5_000L));

        OfferRecord sellLow = OfferRecord.newOffer(2, 1, itemId, "Abyssal whip", false, 5, 1_200, 3L)
            .withFill(5, 6_000L, OfferState.FILLED, 4L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.COMPLETED, sellLow, 5, 6_000L));

        OfferRecord buyHigh = OfferRecord.newOffer(3, 0, itemId, "Abyssal whip", true, 5, 1_500, 5L)
            .withFill(5, 7_500L, OfferState.FILLED, 6L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.COMPLETED, buyHigh, 5, 7_500L));

        OfferRecord sellHigh = OfferRecord.newOffer(4, 1, itemId, "Abyssal whip", false, 5, 1_700, 7L)
            .withFill(5, 8_500L, OfferState.FILLED, 8L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.COMPLETED, sellHigh, 5, 8_500L));

        List<TransactionRequest> reqs = captureAll();
        assertEquals(4, reqs.size());
        Integer firstCycle = reqs.get(0).roundTripId;
        Integer secondCycle = reqs.get(2).roundTripId;
        assertEquals("buy and sell of the first lot share a round-trip id", firstCycle, reqs.get(1).roundTripId);
        assertEquals("buy and sell of the second lot share a round-trip id", secondCycle, reqs.get(3).roundTripId);
        assertNotEquals("the two round trips are distinct", firstCycle, secondCycle);
    }

    @Test
    public void partialFillsWithinACycle_shareTheSameRoundTripId()
    {
        TransactionLogger logger = newLogger(RSN);
        int itemId = 4151;

        OfferRecord firstFill = OfferRecord.newOffer(1, 0, itemId, "Abyssal whip", true, 5, 1_000, 1L)
            .withFill(2, 2_000L, OfferState.PARTIAL_FILL, 2L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.FILLED_DELTA, firstFill, 2, 2_000L));

        OfferRecord secondFill = firstFill.withFill(5, 5_000L, OfferState.FILLED, 3L);
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.COMPLETED, secondFill, 3, 3_000L));

        List<TransactionRequest> reqs = captureAll();
        assertEquals(2, reqs.size());
        assertEquals("both partial fills of the same buy share a round-trip id",
            reqs.get(0).roundTripId, reqs.get(1).roundTripId);
    }

    @Test
    public void relogMidCycle_preservesRoundTripIdFromPersistedLedgerState()
    {
        int itemId = 4151;

        // First "session": a buy fill opens a cycle on the shared ledger.
        TransactionLogger firstSession = newLogger(RSN);
        OfferRecord buy = OfferRecord.newOffer(1, 0, itemId, "Abyssal whip", true, 5, 1_000, 1L)
            .withFill(5, 5_000L, OfferState.FILLED, 2L);
        firstSession.onOfferEvent(new OfferEvent(OfferTransition.Kind.COMPLETED, buy, 5, 5_000L));
        Integer openCycleId = captureAll().get(0).roundTripId;

        // Simulate a plugin restart: persist + restore the ledger, exactly as
        // OfflineSyncService's persistLedgerState/preloadLedgerState round-trip it.
        java.util.Map<Integer, RoundTripLedger.Entry> persisted = roundTripLedger.export(RSN);
        RoundTripLedger restoredLedger = new RoundTripLedger();
        restoredLedger.importState(RSN, persisted);

        reset(apiClient);
        when(apiClient.recordTransactionAsync(any(TransactionRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        TransactionLogger secondSession = new TransactionLogger(apiClient, session,
            () -> Optional.of(RSN), restoredLedger);

        OfferRecord sell = OfferRecord.newOffer(2, 1, itemId, "Abyssal whip", false, 5, 1_200, 10L)
            .withFill(5, 6_000L, OfferState.FILLED, 11L);
        secondSession.onOfferEvent(new OfferEvent(OfferTransition.Kind.COMPLETED, sell, 5, 6_000L));

        TransactionRequest sellReq = capture();
        assertEquals("the id opened before the relog is preserved after restoring the ledger",
            openCycleId, sellReq.roundTripId);
    }
}
