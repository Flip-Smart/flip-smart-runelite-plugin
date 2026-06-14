package com.flipsmart;

public class ActiveOfferAdvisorService
{
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
