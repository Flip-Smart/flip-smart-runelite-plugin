package com.flipsmart;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class ActiveOfferAdvisorService
{
	private final Map<Integer, OfferAdviceResponse> dispositions = new ConcurrentHashMap<>();

	private Consumer<OfferAdviceResponse> onSurfacePrice;
	private IntConsumer onHandoff;

	void setCallbacks(Consumer<OfferAdviceResponse> onSurfacePrice, IntConsumer onHandoff)
	{
		this.onSurfacePrice = onSurfacePrice;
		this.onHandoff = onHandoff;
	}

	public OfferAdviceResponse getDisposition(int itemId)
	{
		return dispositions.get(itemId);
	}

	void applyResponse(int itemId, OfferAdviceResponse resp)
	{
		OfferDisposition disposition = OfferDisposition.route(resp == null ? null : resp.getActionEnum());
		switch (disposition)
		{
			case SURFACE_PRICE:
				dispositions.put(itemId, resp);
				if (onSurfacePrice != null)
				{
					onSurfacePrice.accept(resp);
				}
				break;
			case HANDOFF:
				dispositions.remove(itemId);
				if (onHandoff != null)
				{
					onHandoff.accept(itemId);
				}
				break;
			case NONE:
			default:
				dispositions.remove(itemId);
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
