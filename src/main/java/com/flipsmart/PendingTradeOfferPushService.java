package com.flipsmart;

import com.flipsmart.domain.offer.OfferSignal;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Reports a single GE slot's current offer state to the FlipSmart backend on
 * every {@code GrandExchangeOfferChanged} event, powering the admin-only
 * "Pending Trades per Item" panel on the Item Graphs page (issue #92).
 *
 * <p>Unlike {@link TradeStationSlotPushService} (a debounced, item-id-only
 * snapshot of all open slots), this reports the single changed slot's full
 * detail (item, side, price, quantity) immediately and undebounced — the
 * admin panel wants near-real-time visibility into individual offers, not a
 * periodically-refreshed snapshot. Best-effort: failures are logged at debug
 * and otherwise swallowed, this is not a critical player-facing signal.
 */
@Slf4j
@Singleton
public class PendingTradeOfferPushService
{
	private final FlipSmartApiClient apiClient;
	private final PlayerSession session;

	// Per-slot dedup: RuneLite can fire GrandExchangeOfferChanged more than once
	// for the same effective state (e.g. duplicate/no-op events). Skip the API
	// call when nothing the backend cares about has actually changed.
	private final Map<Integer, ReportedState> lastReported = new ConcurrentHashMap<>();

	@Inject
	public PendingTradeOfferPushService(FlipSmartApiClient apiClient, PlayerSession session)
	{
		this.apiClient = apiClient;
		this.session = session;
	}

	/**
	 * Report the current state of {@code offer} in {@code slot}. Safe to call
	 * from the client thread — the HTTP call itself is dispatched
	 * asynchronously by {@link FlipSmartApiClient}.
	 */
	public void reportOfferChanged(int slot, GrandExchangeOffer offer)
	{
		if (offer == null)
		{
			return;
		}

		String rsn = session.getRsnSafe().orElse(null);
		if (rsn == null)
		{
			log.debug("Skipping pending-trade offer report: no RSN");
			return;
		}

		String state = toReportState(offer.getState());
		if (state == null)
		{
			// BUYING/SELLING-adjacent states we don't recognize (future
			// RuneLite API additions) — skip rather than report a guess.
			return;
		}

		int itemId = offer.getState() == GrandExchangeOfferState.EMPTY ? 0 : offer.getItemId();
		boolean isBuy = OfferSignal.isBuyState(offer.getState());
		int price = offer.getPrice();
		int quantity = offer.getTotalQuantity();
		int quantityFilled = offer.getQuantitySold();

		ReportedState current = new ReportedState(itemId, isBuy, price, quantity, quantityFilled, state);
		if (current.equals(lastReported.get(slot)))
		{
			return;
		}

		try
		{
			// The API client already swallows and logs its own async failures
			// (best-effort push — see FlipSmartApiClient/PendingTradesEndpoints),
			// so no .exceptionally() is needed here. This try/catch only guards
			// against a synchronous throw while building the request itself.
			apiClient.reportPendingTradeOfferAsync(rsn, slot, itemId, isBuy, price, quantity, quantityFilled, state);
			lastReported.put(slot, current);
		}
		catch (RuntimeException e)
		{
			if (log.isDebugEnabled())
			{
				log.debug("Pending-trade offer report threw: {}", e.getMessage());
			}
		}
	}

	/**
	 * Normalize a RuneLite GE offer state into the coarse lifecycle the
	 * backend tracks: "pending" (in progress), "executed" (fully bought/
	 * sold), "cancelled", or "empty" (slot cleared without ever executing).
	 */
	private static String toReportState(GrandExchangeOfferState state)
	{
		switch (state)
		{
			case BUYING:
			case SELLING:
				return "pending";
			case BOUGHT:
			case SOLD:
				return "executed";
			case CANCELLED_BUY:
			case CANCELLED_SELL:
				return "cancelled";
			case EMPTY:
				return "empty";
			default:
				return null;
		}
	}

	/** Immutable snapshot of the fields that matter to the backend, for dedup. */
	private static final class ReportedState
	{
		private final int itemId;
		private final boolean isBuy;
		private final int price;
		private final int quantity;
		private final int quantityFilled;
		private final String state;

		ReportedState(int itemId, boolean isBuy, int price, int quantity, int quantityFilled, String state)
		{
			this.itemId = itemId;
			this.isBuy = isBuy;
			this.price = price;
			this.quantity = quantity;
			this.quantityFilled = quantityFilled;
			this.state = state;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof ReportedState))
			{
				return false;
			}
			ReportedState other = (ReportedState) o;
			return itemId == other.itemId
				&& isBuy == other.isBuy
				&& price == other.price
				&& quantity == other.quantity
				&& quantityFilled == other.quantityFilled
				&& Objects.equals(state, other.state);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(itemId, isBuy, price, quantity, quantityFilled, state);
		}
	}
}
