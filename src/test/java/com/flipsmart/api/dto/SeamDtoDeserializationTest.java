package com.flipsmart.api.dto;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Round-trip deserialization coverage for the endpoint-group response DTOs the
 * #731 split exposed (now parsed via {@code ApiHttpTransport.parse}). These pin
 * the snake_case wire-key → field mappings so a renamed {@code @SerializedName}
 * or field can't silently drop data from a response.
 */
public class SeamDtoDeserializationTest
{
	private final Gson gson = new Gson();

	@Test
	public void flipStatisticsResponseMapsSnakeCaseKeys()
	{
		String json = "{\"total_flips\":42,\"successful_flips\":30,\"total_profit\":1500000,"
			+ "\"success_rate\":0.714,\"average_roi\":12.5}";
		FlipStatisticsResponse r = gson.fromJson(json, FlipStatisticsResponse.class);
		assertEquals(42, r.getTotalFlips());
		assertEquals(30, r.getSuccessfulFlips());
		assertEquals(1500000, r.getTotalProfit());
		assertEquals(0.714, r.getSuccessRate(), 1e-9);
		assertEquals(12.5, r.getAverageRoi(), 1e-9);
	}

	@Test
	public void flipFinderResponseMapsKeysNestedSubscriptionAndRecommendations()
	{
		String json = "{\"flip_style\":\"high_volume\",\"cash_stack\":50000000,"
			+ "\"per_slot_budget\":6250000.0,\"total_items_analyzed\":3500,"
			+ "\"items_matching_criteria\":120,"
			+ "\"recommendations\":[{\"item_id\":4151,\"item_name\":\"Abyssal whip\"}],"
			+ "\"subscription\":{\"tier\":\"premium\",\"recommendation_limit\":50,"
			+ "\"recommendations_returned\":40}}";
		FlipFinderResponse r = gson.fromJson(json, FlipFinderResponse.class);
		assertEquals("high_volume", r.getFlipStyle());
		assertEquals(Integer.valueOf(50000000), r.getCashStack());
		assertEquals(Double.valueOf(6250000.0), r.getPerSlotBudget());
		assertEquals(3500, r.getTotalItemsAnalyzed());
		assertEquals(120, r.getItemsMatchingCriteria());
		assertEquals(1, r.getRecommendations().size());
		assertEquals(4151, r.getRecommendations().get(0).getItemId());
		assertEquals("premium", r.getSubscription().getTier());
		assertEquals(Integer.valueOf(50), r.getSubscription().getRecommendationLimit());
		assertTrue("premium-tier subscription must read as premium", r.isPremium());
	}

	@Test
	public void flipFinderResponseNonPremiumTierIsNotPremium()
	{
		String json = "{\"subscription\":{\"tier\":\"free\"}}";
		FlipFinderResponse r = gson.fromJson(json, FlipFinderResponse.class);
		assertFalse(r.isPremium());
	}

	@Test
	public void flipFinderResponseMissingSubscriptionIsNotPremium()
	{
		FlipFinderResponse r = gson.fromJson("{\"flip_style\":\"balanced\"}", FlipFinderResponse.class);
		assertFalse("absent subscription must not throw and must read as non-premium", r.isPremium());
	}

	@Test
	public void completedFlipsResponseMapsListAndCount()
	{
		String json = "{\"count\":2,\"flips\":["
			+ "{\"id\":1,\"item_id\":4151,\"item_name\":\"Abyssal whip\",\"quantity\":1},"
			+ "{\"id\":2,\"item_id\":1305,\"item_name\":\"Dragon longsword\",\"quantity\":5}]}";
		CompletedFlipsResponse r = gson.fromJson(json, CompletedFlipsResponse.class);
		assertEquals(2, r.getCount());
		assertEquals(2, r.getFlips().size());
		assertEquals(4151, r.getFlips().get(0).getItemId());
	}

	@Test
	public void completedFlipParsesMoneyValuesAboveInt4()
	{
		// A single high-value flip whose totals exceed Integer.MAX_VALUE (2,147,483,647).
		// Gson parses the whole response, so one over-int flip that fails to deserialize
		// throws JsonSyntaxException and drops the ENTIRE Flip History panel, not one row.
		long buyTotal = 3_000_000_000L;
		long sellTotal = 3_200_000_000L;
		long grossProfit = 200_000_000_000L;
		long geTax = 4_000_000_000L;
		long netProfit = 196_000_000_000L;
		long perItemPrice = 2_400_000_000L; // e.g. a party hat, > int4 for a single item
		String json = "{\"count\":1,\"flips\":[{\"id\":1,\"item_id\":4151,\"item_name\":\"Third age\","
			+ "\"quantity\":1,\"buy_price_per_item\":" + perItemPrice + ",\"buy_total\":" + buyTotal + ","
			+ "\"sell_price_per_item\":" + perItemPrice + ",\"sell_total\":" + sellTotal + ","
			+ "\"gross_profit\":" + grossProfit + ",\"ge_tax\":" + geTax + ",\"net_profit\":" + netProfit + "}]}";
		CompletedFlipsResponse r = gson.fromJson(json, CompletedFlipsResponse.class);
		com.flipsmart.domain.flip.CompletedFlip f = r.getFlips().get(0);
		assertEquals(perItemPrice, f.getBuyPricePerItem());
		assertEquals(buyTotal, f.getBuyTotal());
		assertEquals(perItemPrice, f.getSellPricePerItem());
		assertEquals(sellTotal, f.getSellTotal());
		assertEquals(grossProfit, f.getGrossProfit());
		assertEquals(geTax, f.getGeTax());
		assertEquals(netProfit, f.getNetProfit());
	}

	@Test
	public void flipStatisticsParsesProfitAboveInt4()
	{
		// /flips/statistics returns SUM(net_profit); Postgres SUM(int4) is bigint, so the API
		// can already emit past-int4 totals for a >2.1b/month window regardless of column type.
		String json = "{\"total_flips\":42,\"successful_flips\":30,\"total_profit\":5000000000,"
			+ "\"success_rate\":0.714,\"average_roi\":12.5}";
		FlipStatisticsResponse r = gson.fromJson(json, FlipStatisticsResponse.class);
		assertEquals(5_000_000_000L, r.getTotalProfit());
	}

	@Test
	public void blocklistsResponseMapsListCountAndNestedSnakeCaseKeys()
	{
		String json = "{\"count\":1,\"blocklists\":[{\"id\":7,\"name\":\"My list\","
			+ "\"description\":\"d\",\"item_count\":3,\"share_id\":\"abc\",\"is_public\":true}]}";
		BlocklistsResponse r = gson.fromJson(json, BlocklistsResponse.class);
		assertEquals(1, r.getCount());
		assertEquals(1, r.getBlocklists().size());
		BlocklistSummary s = r.getBlocklists().get(0);
		assertEquals(7, s.getId());
		assertEquals(3, s.getItemCount());
		assertEquals("abc", s.getShareId());
		assertTrue(s.isPublic());
	}

	@Test
	public void bankSnapshotResponseMapsSnakeCaseKeys()
	{
		String json = "{\"id\":9,\"rsn\":\"Zezima\",\"total_value\":1000,\"inventory_value\":200,"
			+ "\"ge_offers_value\":300,\"total_wealth\":1500,\"item_count\":12,"
			+ "\"snapshot_time\":\"2026-07-01T00:00:00Z\",\"message\":\"ok\"}";
		BankSnapshotResponse r = gson.fromJson(json, BankSnapshotResponse.class);
		assertEquals(9, r.getId());
		assertEquals("Zezima", r.getRsn());
		assertEquals(1000L, r.getTotalValue());
		assertEquals(200L, r.getInventoryValue());
		assertEquals(300L, r.getGeOffersValue());
		assertEquals(1500L, r.getTotalWealth());
		assertEquals(12, r.getItemCount());
		assertEquals("2026-07-01T00:00:00Z", r.getSnapshotTime());
		assertEquals("ok", r.getMessage());
	}
}
