package com.flipsmart.v9;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-flip state for a V9 short-timeframe sell, carried from first listing through the
 * re-adjustment ladder. The timeframe is snapshotted at listing time so a later change to
 * the global timeframe config never re-points an in-flight flip's rungs.
 */
@Data
@NoArgsConstructor
public class V9FlipState
{
	private int itemId;
	private String timeframe;
	private long listingTimestampMs;
	private String scenario;
	private Long scenarioBMid;
	private long buyPrice;
	private int totalQty;
	private int remainingQty;
	private long originalTarget;
	private int ladderRung;
	private long ladder1ResolvedAtMs;
	private long seed;
	private long realizedProfit;
	private long savedAtMillis;
}
