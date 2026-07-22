package com.flipsmart.trading;

import com.flipsmart.FlipSmartApiClient;
import com.flipsmart.PlayerSession;
import com.flipsmart.api.dto.TransactionRequest;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.domain.offer.OfferTransition;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Send-layer behaviour of the round-trip guard: a duplicate/phantom closing fill that the
 * peek-based dedup cannot catch must not be forwarded to the backend (#1054).
 */
public class TransactionLoggerTest
{
    private static final String RSN = "Zezima";
    private static final int ITEM = 4151;

    private FlipSmartApiClient apiClient;
    private TransactionLogger logger;

    @Before
    public void setUp()
    {
        apiClient = mock(FlipSmartApiClient.class);
        when(apiClient.recordTransactionAsync(any(TransactionRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        PlayerSession session = mock(PlayerSession.class);
        when(session.getRecommendedPrice(anyInt())).thenReturn(null);
        RoundTripLedger ledger = new RoundTripLedger();
        logger = new TransactionLogger(apiClient, session, () -> Optional.of(RSN), ledger);
    }

    /** A fully-filled offer whose only fill delivery carries the whole quantity. */
    private OfferRecord completed(long offerId, int slot, boolean buy, int qty, int price, long createdAt)
    {
        return OfferRecord.newOffer(offerId, slot, ITEM, "Abyssal whip", buy, qty, price, createdAt)
            .withFill(qty, (long) qty * price, OfferState.FILLED, createdAt + 1);
    }

    private void deliverFill(OfferRecord r, int newlyFilled, long newlySpent)
    {
        logger.onOfferEvent(new OfferEvent(OfferTransition.Kind.COMPLETED, r, newlyFilled, newlySpent));
    }

    private List<TransactionRequest> sentRequests(int expectedCount)
    {
        ArgumentCaptor<TransactionRequest> cap = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(apiClient, times(expectedCount)).recordTransactionAsync(cap.capture());
        return cap.getAllValues();
    }

    @Test
    public void duplicateClosingFill_underChurnedOfferId_isNotForwarded()
    {
        // Clean lot on slot 0: buy 10 then sell 10 closes the cycle.
        deliverFill(completed(1L, 0, true, 10, 100, 1_000L), 10, 1_000L);
        deliverFill(completed(2L, 0, false, 10, 110, 2_000L), 10, 1_100L);

        // The exact closing sell is re-delivered under a CHURNED offer_id (a Collect re-detect):
        // same slot + cumulative, new offer_id + timestamp. The peek-based dedup can't catch it —
        // the real close already advanced the cycle, so this fill peeks a different round-trip id
        // and its normalized key differs. Only the ledger guard recognises it.
        deliverFill(completed(3L, 0, false, 10, 110, 3_000L), 10, 1_100L);

        // Two sends (buy + sell), not three — the phantom was dropped.
        List<TransactionRequest> sent = sentRequests(2);
        assertEquals("buy and sell of the one lot both forwarded", 2, sent.size());
        assertEquals("buy and sell share the round trip id",
            sent.get(0).roundTripId, sent.get(1).roundTripId);
    }

    @Test
    public void genuineRepeatRoundTrip_forwardsEveryFill()
    {
        // buy -> sell (closes) -> genuine new buy (opens next cycle) -> sell (closes) on the same slot.
        deliverFill(completed(1L, 0, true, 10, 100, 1_000L), 10, 1_000L);
        deliverFill(completed(2L, 0, false, 10, 110, 2_000L), 10, 1_100L);
        deliverFill(completed(3L, 0, true, 10, 100, 3_000L), 10, 1_000L);
        deliverFill(completed(4L, 0, false, 10, 110, 4_000L), 10, 1_100L);

        // All four forwarded — the guard only suppresses an immediate byte-identical re-delivery,
        // never a genuine repeat separated by a real intervening fill.
        List<TransactionRequest> sent = sentRequests(4);
        assertEquals(4, sent.size());
        assertEquals("first lot's buy and sell share a cycle",
            sent.get(0).roundTripId, sent.get(1).roundTripId);
        assertEquals("second lot's buy and sell share a cycle",
            sent.get(2).roundTripId, sent.get(3).roundTripId);
        assertEquals("the two round trips get distinct ids",
            (int) sent.get(0).roundTripId + 1, (int) sent.get(2).roundTripId);
    }

    @Test
    public void concurrentSameItemTwoSlots_forwardsBothClosingFills()
    {
        // Two open buys of the same item in different slots — one round trip across slots.
        deliverFill(completed(1L, 0, true, 10, 100, 1_000L), 10, 1_000L);
        deliverFill(completed(2L, 1, true, 5, 100, 1_500L), 5, 500L);
        // Liquidate both; the guard must not treat the slot-1 close as a duplicate of the slot-0 one.
        deliverFill(completed(3L, 0, false, 10, 110, 2_000L), 10, 1_100L);
        deliverFill(completed(4L, 1, false, 5, 110, 2_500L), 5, 550L);

        List<TransactionRequest> sent = sentRequests(4);
        assertEquals("all four fills across two slots forwarded", 4, sent.size());
    }
}
