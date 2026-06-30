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
	public void bankSnapshotStatusResponseMapsSnakeCaseKeys()
	{
		String json = "{\"can_snapshot\":false,\"next_snapshot_available\":\"2026-07-01T00:00:00Z\","
			+ "\"hours_until_available\":4.5,\"message\":\"rate limited\"}";
		BankSnapshotStatusResponse r = gson.fromJson(json, BankSnapshotStatusResponse.class);
		assertFalse(r.isCanSnapshot());
		assertEquals("2026-07-01T00:00:00Z", r.getNextSnapshotAvailable());
		assertEquals(Double.valueOf(4.5), r.getHoursUntilAvailable());
		assertEquals("rate limited", r.getMessage());
	}
}
