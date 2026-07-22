package com.flipsmart.trading;

import com.flipsmart.FlipSmartApiClient;
import com.flipsmart.PlayerSession;
import com.flipsmart.api.dto.TransactionRequest;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Single recording point for GE transactions, driven by OfferEvents from OfferStore.
 * Generates a deterministic per-event idempotency key and delegates to the API client.
 * The backend partial-unique index is the durable backstop; this class adds a
 * bounded client-side guard to suppress immediate duplicate sends within one session.
 */
@Singleton
public final class TransactionLogger
{
    public enum Type { PLACE, FILL }

    private static final int SEEN_KEYS_CAPACITY = 512;

    private final FlipSmartApiClient apiClient;
    private final PlayerSession session;
    private final Supplier<Optional<String>> rsnSupplier;
    private final RoundTripLedger roundTripLedger;

    private final Set<String> seenKeys = Collections.synchronizedSet(
        Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>()
            {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest)
                {
                    return size() > SEEN_KEYS_CAPACITY;
                }
            }));

    @Inject
    public TransactionLogger(FlipSmartApiClient apiClient, PlayerSession session, RoundTripLedger roundTripLedger)
    {
        this(apiClient, session, session::getRsnSafe, roundTripLedger);
    }

    public TransactionLogger(FlipSmartApiClient apiClient, PlayerSession session,
                             Supplier<Optional<String>> rsnSupplier, RoundTripLedger roundTripLedger)
    {
        this.apiClient = apiClient;
        this.session = session;
        this.rsnSupplier = rsnSupplier;
        this.roundTripLedger = roundTripLedger;
    }

    public void onOfferEvent(OfferEvent e)
    {
        switch (e.kind)
        {
            case PLACED:
                // An offer first seen already filling (immediate / offline fill) records the
                // fill only — never a separate qty-0 placement row. The placement row is for
                // genuine 0-filled placements; emitting both here would double-log the order
                // versus the single fill the old recording path produced.
                if (e.newlyFilledQuantity > 0)
                {
                    recordFill(e.record, e.newlyFilledQuantity, e.newlySpent);
                }
                else
                {
                    recordPlacement(e.record);
                }
                break;
            case FILLED_DELTA:
            case COMPLETED:
            case CANCELLED:
                if (e.newlyFilledQuantity > 0)
                {
                    recordFill(e.record, e.newlyFilledQuantity, e.newlySpent);
                }
                break;
            default:
                // COLLECTED, NONE, REJECTED — nothing to record
                break;
        }
    }

    private void recordPlacement(com.flipsmart.domain.offer.OfferRecord r)
    {
        String rsn = rsnSupplier.get().orElse(null);
        String key = idempotencyKey(rsn, r, Type.PLACE);
        // Zero-fill placement: peek the current cycle rather than recordFill, since no
        // quantity actually moved and peeking must never trip a zero-crossing.
        Integer roundTripId = roundTripLedger.peekRoundTripId(rsn, r.getItemId());
        if (alreadySent(key, normalizedDedupKey(rsn, roundTripId, r, Type.PLACE)))
        {
            return;
        }
        apiClient.recordTransactionAsync(baseBuilder(r, 0, r.getPrice(), rsn, key).roundTripId(roundTripId).build());
    }

    private void recordFill(com.flipsmart.domain.offer.OfferRecord r, int newlyFilled, long newlySpent)
    {
        String rsn = rsnSupplier.get().orElse(null);
        String key = idempotencyKey(rsn, r, Type.FILL);
        // Peek (non-mutating) the round trip this fill belongs to for the dedup key
        // BEFORE the guard, so a suppressed duplicate never advances the ledger.
        Integer roundTripId = roundTripLedger.peekRoundTripId(rsn, r.getItemId());
        if (alreadySent(key, normalizedDedupKey(rsn, roundTripId, r, Type.FILL)))
        {
            return;
        }
        int pricePerItem = newlyFilled > 0 ? (int) (newlySpent / newlyFilled) : 0;
        RoundTripLedger.FillResult fill = roundTripLedger.recordFillGuarded(
            rsn, r.getItemId(), r.getSlot(), r.isBuy(), newlyFilled, r.getFilledQuantity());
        if (fill.duplicateSuppressed)
        {
            // A duplicate/phantom closing fill (Collect re-detection under a churned offer_id) that
            // the peek-based dedup above can't catch: the genuine close already advanced the cycle,
            // so this fill's peeked id — and thus its dedup key — differs from the original's. The
            // ledger recognised it as a byte-identical re-delivery and mutated nothing; forwarding
            // it would stamp the backend with the next cycle's id, so drop it here.
            return;
        }
        apiClient.recordTransactionAsync(baseBuilder(r, newlyFilled, pricePerItem, rsn, key).roundTripId(fill.roundTripId).build());
    }

    /**
     * True if either the exact per-record key or the churn-resistant normalized key
     * has already been sent this session; records both so a later re-report — under
     * the same record OR a Collect-re-detected new offerId — is suppressed. Purely
     * additive: anything not matched is sent exactly as before.
     */
    private boolean alreadySent(String key, String normalizedKey)
    {
        synchronized (seenKeys)
        {
            boolean seen = seenKeys.contains(key) || seenKeys.contains(normalizedKey);
            seenKeys.add(key);
            seenKeys.add(normalizedKey);
            return seen;
        }
    }

    /**
     * Identity of a logical fill/placement that survives Collect-driven offer_id and
     * timestamp churn: {@code rsn:itemId:isBuy:slot:roundTrip:TYPE:cumulative}. The GE
     * slot is included because {@code filledQuantity} is per-offer, so two CONCURRENT
     * same-item offers (different slots) can reach the same cumulative — without the
     * slot they would collide and the second fill would be dropped. A real re-report
     * keeps its slot even when its offer_id churns, so suppression still works. Used
     * only for client-side send suppression; the sent idempotency key is unchanged.
     */
    static String normalizedDedupKey(String rsn, Integer roundTripId,
                                     com.flipsmart.domain.offer.OfferRecord r, Type type)
    {
        long cumulative = type == Type.PLACE ? 0 : r.getFilledQuantity();
        return rsn + ":" + r.getItemId() + ":" + r.isBuy() + ":" + r.getSlot()
            + ":" + roundTripId + ":" + type + ":" + cumulative;
    }

    private TransactionRequest.Builder baseBuilder(com.flipsmart.domain.offer.OfferRecord r,
                                                    int qty, int price, String rsn, String key)
    {
        Integer recPrice = r.isBuy() ? session.getRecommendedPrice(r.getItemId()) : null;
        return TransactionRequest
            .builder(r.getItemId(), r.getItemName(), r.isBuy(), qty, price)
            .geSlot(r.getSlot())
            .recommendedSellPrice(recPrice)
            .rsn(rsn)
            .totalQuantity(r.getTotalQuantity())
            .idempotencyKey(key)
            .offerId(r.getOfferId());
    }

    /**
     * Deterministic idempotency key for a single logical record+type combination.
     * Format: {@code rsn:offerId:createdAtMillis:TYPE:cumulative}
     * where cumulative=0 for PLACE, filledQuantity for FILL.
     */
    public static String idempotencyKey(String rsn, com.flipsmart.domain.offer.OfferRecord r, Type type)
    {
        long cumulative = type == Type.PLACE ? 0 : r.getFilledQuantity();
        return rsn + ":" + r.getOfferId() + ":" + r.getCreatedAtMillis() + ":" + type + ":" + cumulative;
    }
}
