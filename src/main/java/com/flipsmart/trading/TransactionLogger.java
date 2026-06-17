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

    private final Set<String> seenKeys = Collections.newSetFromMap(
        new LinkedHashMap<String, Boolean>()
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest)
            {
                return size() > SEEN_KEYS_CAPACITY;
            }
        });

    @Inject
    public TransactionLogger(FlipSmartApiClient apiClient, PlayerSession session)
    {
        this(apiClient, session, session::getRsnSafe);
    }

    public TransactionLogger(FlipSmartApiClient apiClient, PlayerSession session,
                             Supplier<Optional<String>> rsnSupplier)
    {
        this.apiClient = apiClient;
        this.session = session;
        this.rsnSupplier = rsnSupplier;
    }

    public void onOfferEvent(OfferEvent e)
    {
        switch (e.kind)
        {
            case PLACED:
                recordPlacement(e.record);
                if (e.newlyFilledQuantity > 0)
                {
                    recordFill(e.record, e.newlyFilledQuantity, e.newlySpent);
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
        if (!seenKeys.add(key))
        {
            return;
        }
        Integer recPrice = r.isBuy() ? session.getRecommendedPrice(r.getItemId()) : null;
        apiClient.recordTransactionAsync(TransactionRequest
            .builder(r.getItemId(), r.getItemName(), r.isBuy(), 0, r.getPrice())
            .geSlot(r.getSlot())
            .recommendedSellPrice(recPrice)
            .rsn(rsn)
            .totalQuantity(r.getTotalQuantity())
            .idempotencyKey(key)
            .build());
    }

    private void recordFill(com.flipsmart.domain.offer.OfferRecord r, int newlyFilled, long newlySpent)
    {
        String rsn = rsnSupplier.get().orElse(null);
        String key = idempotencyKey(rsn, r, Type.FILL);
        if (!seenKeys.add(key))
        {
            return;
        }
        int pricePerItem = newlyFilled > 0 ? (int) (newlySpent / newlyFilled) : 0;
        Integer recPrice = r.isBuy() ? session.getRecommendedPrice(r.getItemId()) : null;
        apiClient.recordTransactionAsync(TransactionRequest
            .builder(r.getItemId(), r.getItemName(), r.isBuy(), newlyFilled, pricePerItem)
            .geSlot(r.getSlot())
            .recommendedSellPrice(recPrice)
            .rsn(rsn)
            .totalQuantity(r.getTotalQuantity())
            .idempotencyKey(key)
            .build());
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
