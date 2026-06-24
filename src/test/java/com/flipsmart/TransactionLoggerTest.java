package com.flipsmart;

import com.flipsmart.api.dto.TransactionRequest;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.offer.OfferTransition;
import com.flipsmart.trading.OfferEvent;
import com.flipsmart.trading.OfferEventMapper;
import com.flipsmart.trading.OfferStore;
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

    @Before
    public void setUp()
    {
        apiClient = mock(FlipSmartApiClient.class);
        session = mock(PlayerSession.class);
        when(apiClient.recordTransactionAsync(any(TransactionRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(session.getRecommendedPrice(anyInt())).thenReturn(null);
    }

    private TransactionLogger newLogger(String rsn)
    {
        Supplier<Optional<String>> supplier = () -> Optional.of(rsn);
        return new TransactionLogger(apiClient, session, supplier);
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
}
