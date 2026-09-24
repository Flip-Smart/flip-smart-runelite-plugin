package com.flipsmart;

import com.flipsmart.api.dto.Dtos.FavoriteItem;
import com.flipsmart.api.dto.Dtos.FlipAdjustmentResponse;
import com.flipsmart.api.dto.Dtos.FlipFinderResponse;
import com.flipsmart.api.dto.Dtos.OfferAdviceResponse;
import com.flipsmart.api.dto.Dtos.PriceTargetResponse;
import com.flipsmart.api.dto.Dtos.ReadjustmentResponse;
import com.flipsmart.api.dto.Dtos.SellPriceCheckResponse;
import com.flipsmart.api.dto.Dtos.TransactionRequest;
import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.flip.FlipAnalysis;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.recommend.SmartSellPricer;
import com.flipsmart.trading.ActiveFlipCardMetrics;
import com.flipsmart.trading.RealizedFlipProfit;
import com.flipsmart.util.BuyPriceLookup;
import com.flipsmart.util.GeTax;
import com.flipsmart.util.GpUtils;
import com.flipsmart.v9.V9FlipState;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Jagex's max-cash update lets a single GE price exceed Integer.MAX_VALUE. Every GP value the
 * plugin parses, stores, computes or displays must survive a 3b per-item price and ~2.1T totals.
 */
public class MaxCashGpTest
{
	private static final long PRICE_3B = 3_000_000_000L;
	private static final long SELL_3_1B = 3_100_000_000L;
	private static final long TOTAL_2_1T = 2_100_000_000_000L;
	private static final int QTY_700 = 700;
	private static final int ITEM = 4151;
	private static final int TAX_CAP = 5_000_000;

	private final Gson gson = new Gson();

	// ---- DTO parse: an int field would throw JsonSyntaxException and drop the whole response ----

	@Test
	public void flipRecommendationParsesPricesAndTotalsAboveInt32()
	{
		String json = "{\"item_id\":4151,\"instant_buy_price\":3100000000,\"instant_sell_price\":3000000000,"
			+ "\"recommended_buy_price\":3000000000,\"recommended_sell_price\":3100000000,"
			+ "\"recommended_quantity\":700,\"margin\":100000000,\"ge_tax\":5000000,"
			+ "\"breakeven_sell_price\":3005000000,\"potential_profit\":66500000000,"
			+ "\"total_cost\":2100000000000}";
		FlipRecommendation rec = gson.fromJson(json, FlipRecommendation.class);

		assertEquals(SELL_3_1B, rec.getInstantBuyPrice());
		assertEquals(PRICE_3B, rec.getInstantSellPrice());
		assertEquals(PRICE_3B, rec.getRecommendedBuyPrice());
		assertEquals(SELL_3_1B, rec.getRecommendedSellPrice());
		assertEquals(100_000_000L, rec.getMargin());
		assertEquals(3_005_000_000L, rec.getBreakevenSellPrice());
		assertEquals(66_500_000_000L, rec.getPotentialProfit());
		assertEquals(TOTAL_2_1T, rec.getTotalCost());
	}

	@Test
	public void otherResponseDtosParseMoneyAboveInt32()
	{
		ActiveFlip flip = gson.fromJson(
			"{\"average_buy_price\":3000000000,\"recommended_sell_price\":3100000000,\"total_invested\":2100000000000}",
			ActiveFlip.class);
		assertEquals(PRICE_3B, flip.getAverageBuyPrice());
		assertEquals(Long.valueOf(SELL_3_1B), flip.getRecommendedSellPrice());
		assertEquals(TOTAL_2_1T, flip.getTotalInvested());

		FlipAnalysis analysis = gson.fromJson(
			"{\"current_prices\":{\"high\":3100000000,\"low\":3000000000,\"gross_margin\":100000000,"
				+ "\"ge_tax\":5000000,\"net_margin\":95000000},\"historical_data\":{\"avg_price\":3050000000}}",
			FlipAnalysis.class);
		assertEquals(Long.valueOf(SELL_3_1B), analysis.getCurrentPrices().getHigh());
		assertEquals(Long.valueOf(PRICE_3B), analysis.getCurrentPrices().getLow());
		assertEquals(Long.valueOf(3_050_000_000L), analysis.getHistoricalData().getAvgPrice());

		FavoriteItem fav = gson.fromJson(
			"{\"buy_price\":3000000000,\"sell_price\":3100000000,\"margin\":95000000,\"profit\":66500000000}",
			FavoriteItem.class);
		assertEquals(Long.valueOf(PRICE_3B), fav.getBuyPrice());
		assertEquals(66_500_000_000L, fav.getProfit());

		OfferAdviceResponse advice = gson.fromJson(
			"{\"action\":\"move_down\",\"new_price\":3000000000,\"net_profit_estimate\":2100000000000,"
				+ "\"position_margin\":95000000}",
			OfferAdviceResponse.class);
		assertEquals(Long.valueOf(PRICE_3B), advice.getNewPrice());
		assertEquals(Long.valueOf(TOTAL_2_1T), advice.getNetProfitEstimate());

		FlipAdjustmentResponse adj = gson.fromJson(
			"{\"recommended_price\":3000000000,\"current_margin\":95000000,\"breakeven_price\":3005000000}",
			FlipAdjustmentResponse.class);
		assertEquals(Long.valueOf(PRICE_3B), adj.getRecommendedPrice());
		assertEquals(3_005_000_000L, adj.getBreakevenPrice());

		SellPriceCheckResponse check = gson.fromJson(
			"{\"recommended_sell_price\":3100000000}", SellPriceCheckResponse.class);
		assertEquals(SELL_3_1B, check.getRecommendedSellPrice());

		FlipFinderResponse finder = gson.fromJson("{\"cash_stack\":2100000000000}", FlipFinderResponse.class);
		assertEquals(Long.valueOf(TOTAL_2_1T), finder.getCashStack());
	}

	@Test
	public void transactionRequestSerializesPriceAboveInt32Untruncated()
	{
		TransactionRequest req = TransactionRequest.builder(ITEM, "Whip", true, QTY_700, PRICE_3B)
			.recommendedSellPrice(SELL_3_1B)
			.build();
		String json = gson.toJson(req);
		assertTrue(json, json.contains("3000000000"));
		assertTrue(json, json.contains("3100000000"));
	}

	@Test
	public void v9PriceTargetDtosAndStateCarryPricesAboveInt32()
	{
		PriceTargetResponse target = gson.fromJson(
			"{\"recommended_buy_price\":3000000000,\"recommended_sell_price\":3100000000,"
				+ "\"listing_sell_price\":3050000000,\"scenario\":\"B\",\"scenario_b_mid\":3040000000}",
			PriceTargetResponse.class);
		assertEquals(PRICE_3B, target.getRecommendedBuyPrice());
		assertEquals(Long.valueOf(3_050_000_000L), target.getListingSellPrice());
		assertEquals(Long.valueOf(3_040_000_000L), target.getScenarioBMid());

		ReadjustmentResponse readjust = gson.fromJson(
			"{\"action\":\"relist\",\"listing_price\":3020000000}", ReadjustmentResponse.class);
		assertEquals(Long.valueOf(3_020_000_000L), readjust.getListingPrice());

		V9FlipState legacy = gson.fromJson(
			"{\"itemId\":4151,\"buyPrice\":1000,\"originalTarget\":1100,\"scenarioBMid\":1050}", V9FlipState.class);
		assertEquals(1000L, legacy.getBuyPrice());
		assertEquals(Long.valueOf(1050L), legacy.getScenarioBMid());

		JsonObject body = FlipSmartApiClient.buildReadjustmentBody(
			"B", 1, PRICE_3B, QTY_700, QTY_700, TOTAL_2_1T, SELL_3_1B, SELL_3_1B, 3_040_000_000L, 7L);
		assertEquals(PRICE_3B, body.get("buy_price").getAsLong());
		assertEquals(SELL_3_1B, body.get("original_target").getAsLong());
		assertEquals(TOTAL_2_1T, body.get("realized_profit").getAsLong());
	}

	// ---- persisted local state written by the int-typed build must still load ----

	@Test
	public void legacyIntPersistedStateDeserializesIntoWidenedFields()
	{
		String legacyOffers = "[{\"offerId\":7,\"slot\":2,\"itemId\":4151,\"itemName\":\"Whip\",\"buy\":true,"
			+ "\"totalQuantity\":10,\"price\":2000000000,\"filledQuantity\":10,\"spent\":20000000000,"
			+ "\"state\":\"FILLED\",\"createdAtMillis\":1,\"completedAtMillis\":2,\"lastActivityAtMillis\":2}]";
		List<OfferRecord> records = gson.fromJson(legacyOffers, new TypeToken<List<OfferRecord>>() { }.getType());
		assertEquals(2_000_000_000L, records.get(0).getPrice());
		assertEquals(20_000_000_000L, records.get(0).getSpent());

		String legacyAuto = "{\"active\":true,\"currentIndex\":0,\"savedAtMillis\":5,"
			+ "\"queue\":[{\"item_id\":4151,\"recommended_buy_price\":1500000,\"recommended_sell_price\":1600000}],"
			+ "\"buyPrices\":{\"4151\":1500000}}";
		AutoRecommendService.PersistedState state = gson.fromJson(legacyAuto, AutoRecommendService.PersistedState.class);
		assertEquals(1_500_000L, state.queue.get(0).getRecommendedBuyPrice());
		assertEquals(Long.valueOf(1_500_000L), state.buyPrices.get(4151));
	}

	@Test
	public void persistedStateRoundTripsPricesAboveInt32()
	{
		AutoRecommendService.PersistedState state = new AutoRecommendService.PersistedState();
		state.buyPrices = Collections.singletonMap(ITEM, PRICE_3B);
		AutoRecommendService.PersistedState back = gson.fromJson(gson.toJson(state), AutoRecommendService.PersistedState.class);
		Map<Integer, Long> prices = back.buyPrices;
		assertEquals(Long.valueOf(PRICE_3B), prices.get(ITEM));

		OfferRecord rec = OfferRecord.newOffer(1L, 0, ITEM, "Whip", false, QTY_700, PRICE_3B, 1L);
		OfferRecord roundTripped = gson.fromJson(gson.toJson(rec), OfferRecord.class);
		assertEquals(PRICE_3B, roundTripped.getPrice());
	}

	// ---- tax / breakeven / min profitable ----

	@Test
	public void taxIsCappedAndBreakevenIsExactAboveInt32()
	{
		assertEquals(TAX_CAP, GeTax.taxFor(ITEM, PRICE_3B));
		assertEquals(TAX_CAP, GeTax.taxFor(TOTAL_2_1T));
		assertEquals(3_005_000_000L, GeTax.breakevenSellPrice(ITEM, PRICE_3B));
		assertEquals(TOTAL_2_1T + TAX_CAP, GeTax.breakevenSellPrice(TOTAL_2_1T));
	}

	@Test(timeout = 1_000)
	public void breakevenAboveTheTaxCapDoesNotWalkMillionsOfSteps()
	{
		for (long buy = 250_000_000L; buy < 2_100_000_000_000L; buy *= 3)
		{
			long s = GeTax.breakevenSellPrice(buy);
			assertTrue(s - GeTax.taxFor(s) >= buy);
			assertTrue((s - 1) - GeTax.taxFor(s - 1) < buy);
		}
	}

	@Test
	public void minProfitableSellPriceDoesNotWrapNegative()
	{
		long min = SmartSellPricer.calculateMinProfitableSellPrice(PRICE_3B);
		// Tax caps at 5m, so the 2% closed form (~3.061b) would overshoot the real breakeven.
		assertEquals(GeTax.breakevenSellPrice(PRICE_3B) + 1, min);
		assertEquals(3_005_000_001L, min);
		assertEquals(104L, SmartSellPricer.calculateMinProfitableSellPrice(100));
		assertEquals((long) Math.ceil(8_000_000 / 0.98) + 1, SmartSellPricer.calculateMinProfitableSellPrice(8_000_000));
		assertTrue(min > PRICE_3B);

		ActiveFlip flip = new ActiveFlip();
		flip.setAverageBuyPrice(PRICE_3B);
		assertEquals(Long.valueOf(min), SmartSellPricer.calculateSmartSellPrice(flip, SELL_3_1B + 100_000_000L));
	}

	// ---- profit calc sites ----

	@Test
	public void adjustedProfitDoesNotOverflowAboveInt32()
	{
		FlipRecommendation rec = new FlipRecommendation();
		rec.setItemId(ITEM);
		rec.setRecommendedBuyPrice(PRICE_3B);
		rec.setRecommendedSellPrice(SELL_3_1B);
		rec.setRecommendedQuantity(QTY_700);
		assertEquals((SELL_3_1B - PRICE_3B - TAX_CAP) * QTY_700, FocusedFlip.calculateAdjustedProfit(rec, 0));
	}

	@Test
	public void slotTooltipProfitLineShowsAboveInt32Total()
	{
		List<String> lines = new ArrayList<>();
		GrandExchangeSlotOverlay.addProfitLossLine(lines, SELL_3_1B, PRICE_3B, QTY_700, ITEM);
		// (3.1b - 3b - 5m) x 700 = 66.5b; the old int math wrapped this to a loss.
		assertEquals("Profit: 66.5B gp (3.2%)", lines.get(lines.size() - 1));
	}

	@Test
	public void cardMetricsAndRealizedProfitHandleAboveInt32()
	{
		ActiveFlipCardMetrics.Result m = ActiveFlipCardMetrics.compute(PRICE_3B, SELL_3_1B, ITEM, PRICE_3B, 0, QTY_700);
		assertEquals(100_000_000L, m.margin);
		assertEquals(SELL_3_1B - PRICE_3B - TAX_CAP, m.positionNetPerUnit);

		OfferRecord sold = OfferRecord.newOffer(1L, 0, ITEM, "Whip", false, QTY_700, SELL_3_1B, 1L)
			.withFill(QTY_700, SELL_3_1B * QTY_700, OfferState.FILLED, 2L);
		RealizedFlipProfit.Result r = RealizedFlipProfit.compute(Collections.singletonList(sold), ITEM, PRICE_3B, 0L);
		assertEquals((SELL_3_1B - PRICE_3B - TAX_CAP) * QTY_700, r.netProfit);
	}

	@Test
	public void sellDescriptionShowsBreakevenAndProfitAboveInt32()
	{
		String out = GeOfferDescriptionFormatter.formatSellDescription(ITEM, PRICE_3B, SELL_3_1B, QTY_700);
		assertTrue(out, out.contains("3,005,000,000 gp"));
		assertTrue(out, out.contains("+66,500,000,000 gp"));
	}

	@Test
	public void averageBuyPriceFromOffersKeepsAboveInt32()
	{
		OfferRecord buy = OfferRecord.newOffer(1L, 0, ITEM, "Whip", true, QTY_700, PRICE_3B, 1L)
			.withFill(QTY_700, PRICE_3B * QTY_700, OfferState.FILLED, 2L);
		assertEquals(Long.valueOf(PRICE_3B), BuyPriceLookup.findAverageBuyPriceWithFallback(
			null, null, Collections.singletonList(buy), ITEM));
	}

	// ---- text parsing ----

	@Test
	public void parseDigitsReadsAboveInt32()
	{
		assertEquals(PRICE_3B, GpUtils.parseDigits("3,000,000,000 coins"));
		assertEquals(TOTAL_2_1T, GpUtils.parseDigits("2,100,000,000,000"));
	}

	@Test
	public void geHistoryRowsAboveInt32AreKeptNotDropped()
	{
		assertEquals(PRICE_3B, GEHistoryService.parsePerItemPriceFromText("<col=ffb83f>= 3,000,000,000 each</col>"));
		assertEquals(TOTAL_2_1T, GEHistoryService.parseLeadingTotalFromText("2,100,000,000,000 coins"));

		OfferRecord offer = OfferRecord.newOffer(9L, 0, ITEM, "Whip", true, QTY_700, PRICE_3B, 1L);
		assertEquals(Long.valueOf(9L), GEHistoryService.matchOfferId(Arrays.asList(offer), ITEM, true, PRICE_3B));
	}

	@Test
	public void geHistoryEntryCarriesAboveInt32Price()
	{
		GEHistoryEntry e = new GEHistoryEntry(ITEM, false, QTY_700, PRICE_3B);
		assertNotNull(e.toString());
		assertEquals(PRICE_3B, e.getPricePerItem());
	}
}
