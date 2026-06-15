package com.flipsmart;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class ActiveOfferAdvisorService
{
	private final Map<Integer, OfferAdviceResponse> dispositions = new ConcurrentHashMap<>();

	private volatile Consumer<OfferAdviceResponse> onSurfacePrice;
	private volatile IntConsumer onHandoff;
	private volatile IntConsumer onClearSurface;

	void setCallbacks(Consumer<OfferAdviceResponse> onSurfacePrice, IntConsumer onHandoff, IntConsumer onClearSurface)
	{
		this.onSurfacePrice = onSurfacePrice;
		this.onHandoff = onHandoff;
		this.onClearSurface = onClearSurface;
	}

	public OfferAdviceResponse getDisposition(int itemId)
	{
		return dispositions.get(itemId);
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
	}

	void applyResponse(int itemId, OfferAdviceResponse resp)
	{
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
		TrackedOffer offer,
		FlipSmartApiClient.WikiPrice market,
		Integer userAvgBuyPrice,
		Integer dailyVolume)
	{
		boolean isSell = !offer.isBuy();
		long lastFill = offer.getLastActivityAtMillis();
		return OfferAdviceRequest.builder()
			.itemId(offer.getItemId())
			.pool(OfferPoolClassifier.classify(dailyVolume))
			.side(isSell ? "sell" : "buy")
			.stage(offer.getOfferStage())
			.listedAtMillis(offer.getCreatedAtMillis())
			.listedPrice(offer.getPrice())
			.listedQuantity(offer.getTotalQuantity())
			.filledQuantity(offer.getPreviousQuantitySold())
			.lastFillAtMillis(lastFill > 0 ? lastFill : null)
			.currentMarketHigh(market == null ? null : market.instaBuy)
			.currentMarketLow(market == null ? null : market.instaSell)
			.userAvgBuyPrice(isSell ? userAvgBuyPrice : null)
			.build();
	}
}
