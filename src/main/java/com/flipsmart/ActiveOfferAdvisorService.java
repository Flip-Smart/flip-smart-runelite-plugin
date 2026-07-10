package com.flipsmart;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.api.dto.OfferAdviceResponse;
import com.flipsmart.domain.offer.OfferDisposition;
import com.flipsmart.api.dto.OfferAdviceRequest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import lombok.Getter;

public class ActiveOfferAdvisorService
{
	/**
	 * Cross-poll advisor state (#918). The backend advisor is stateless, so the
	 * plugin relays the counters it returned on the previous poll: last position
	 * margin (for consecutive-decrease detection) and the joint reduction budget.
	 */
	@Getter
	public static final class CourierState
	{
		private final Integer previousPositionMargin;
		private final int consecutiveMarginDecreases;
		private final double cumulativeMarginReductionPct;

		public CourierState(Integer previousPositionMargin, int consecutiveMarginDecreases, double cumulativeMarginReductionPct)
		{
			this.previousPositionMargin = previousPositionMargin;
			this.consecutiveMarginDecreases = consecutiveMarginDecreases;
			this.cumulativeMarginReductionPct = cumulativeMarginReductionPct;
		}

		static final CourierState EMPTY = new CourierState(null, 0, 0.0);
	}

	private final Map<Integer, OfferAdviceResponse> dispositions = new ConcurrentHashMap<>();
	private final Map<Integer, CourierState> courierByItem = new ConcurrentHashMap<>();

	private volatile Consumer<OfferAdviceResponse> onSurfacePrice;
	private volatile IntConsumer onHandoff;
	private volatile IntConsumer onClearSurface;

	public void setCallbacks(Consumer<OfferAdviceResponse> onSurfacePrice, IntConsumer onHandoff, IntConsumer onClearSurface)
	{
		this.onSurfacePrice = onSurfacePrice;
		this.onHandoff = onHandoff;
		this.onClearSurface = onClearSurface;
	}

	public OfferAdviceResponse getDisposition(int itemId)
	{
		return dispositions.get(itemId);
	}

	/** The courier state to relay for this item's next snapshot (EMPTY if none). */
	public CourierState getCourierState(int itemId)
	{
		return courierByItem.getOrDefault(itemId, CourierState.EMPTY);
	}

	/**
	 * Drop dispositions for items that are no longer open active offers (sold, bought,
	 * collected, cancelled) so their prompt/highlight clears immediately instead of
	 * lingering until the next poll skips them.
	 */
	void reconcile(java.util.Set<Integer> activeItemIds)
	{
		for (Integer itemId : dispositions.keySet())
		{
			if (!activeItemIds.contains(itemId))
			{
				applyResponse(itemId, null);
			}
		}
		// Courier state can outlive a disposition (a WAITing offer clears its
		// disposition but keeps accumulating decay/budget), so prune it too.
		courierByItem.keySet().removeIf(id -> !activeItemIds.contains(id));
	}

	void applyResponse(int itemId, OfferAdviceResponse resp)
	{
		if (resp == null)
		{
			courierByItem.remove(itemId);
		}
		else
		{
			courierByItem.put(itemId, new CourierState(
				resp.getPositionMargin(), resp.getConsecutiveMarginDecreases(), resp.getCumulativeMarginReductionPct()));
		}
		OfferDisposition disposition = OfferDisposition.route(resp == null ? null : resp.getActionEnum());
		switch (disposition)
		{
			case SURFACE_PRICE:
				resp.setItemIdHint(itemId);
				dispositions.put(itemId, resp);
				if (onSurfacePrice != null)
				{
					onSurfacePrice.accept(resp);
				}
				break;
			case HANDOFF:
				dispositions.remove(itemId);
				if (onClearSurface != null)
				{
					onClearSurface.accept(itemId);
				}
				if (onHandoff != null)
				{
					onHandoff.accept(itemId);
				}
				break;
			case NONE:
			default:
				dispositions.remove(itemId);
				if (onClearSurface != null)
				{
					onClearSurface.accept(itemId);
				}
				break;
		}
	}

	static OfferAdviceRequest buildSnapshot(
		OfferRecord offer,
		WikiPrice market,
		Integer userAvgBuyPrice,
		Integer dailyVolume)
	{
		return buildSnapshot(offer, market, userAvgBuyPrice, dailyVolume, null, CourierState.EMPTY);
	}

	static OfferAdviceRequest buildSnapshot(
		OfferRecord offer,
		WikiPrice market,
		Integer userAvgBuyPrice,
		Integer dailyVolume,
		Integer originalMargin,
		CourierState courier)
	{
		boolean isSell = !offer.isBuy();
		long lastFill = offer.getLastActivityAtMillis();
		CourierState c = courier == null ? CourierState.EMPTY : courier;
		return OfferAdviceRequest.builder()
			.itemId(offer.getItemId())
			.pool(OfferPoolClassifier.classify(dailyVolume))
			.side(isSell ? "sell" : "buy")
			.stage(offer.getOfferStage())
			.listedAtMillis(offer.getCreatedAtMillis())
			.listedPrice(offer.getPrice())
			.listedQuantity(offer.getTotalQuantity())
			.filledQuantity(offer.getFilledQuantity())
			.lastFillAtMillis(lastFill > 0 ? lastFill : null)
			.currentMarketHigh(market == null ? null : market.instaBuy)
			.currentMarketLow(market == null ? null : market.instaSell)
			// Now carried for buys too — the margin-decay exit (AC2) needs the
			// avg buy price on a partially-filled buy.
			.userAvgBuyPrice(userAvgBuyPrice)
			.originalMargin(originalMargin)
			.previousPositionMargin(c.getPreviousPositionMargin())
			.consecutiveMarginDecreases(c.getConsecutiveMarginDecreases())
			.cumulativeMarginReductionPct(c.getCumulativeMarginReductionPct())
			.build();
	}
}
