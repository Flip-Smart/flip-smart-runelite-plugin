package com.flipsmart.api.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Parsed {@code GET /plugin/sync} response — the bundled 2-minute poll.
 *
 * Each sub-payload is byte-equal to its standalone endpoint's response, so the
 * existing per-endpoint DTOs deserialize the bundle directly. Any field may be
 * null when that sub-fetch failed server-side; callers skip a null payload and
 * leave the corresponding UI section unchanged. The webhook and _meta fields
 * from the endpoint are intentionally omitted — the poll does not consume them.
 */
@Data
public class PluginSyncResponse
{
	@SerializedName("version")
	private int version;

	@SerializedName("flip_finder")
	private FlipFinderResponse flipFinder;

	@SerializedName("active_flips")
	private ActiveFlipsResponse activeFlips;

	@SerializedName("completed_flips")
	private CompletedFlipsResponse completedFlips;

	@SerializedName("statistics")
	private FlipStatisticsResponse statistics;

	@SerializedName("entitlements")
	private EntitlementsResponse entitlements;
}
