package com.flipsmart.v9;

/**
 * Decides when a V9 short-timeframe flip owns an item's sell, and how to resolve the sell
 * target for its first listing. Pure logic, split out from the plugin so the hand-off between
 * V9 and the legacy adjustment paths is unit-testable.
 */
public final class V9SellArbitration
{
	private V9SellArbitration()
	{
	}

	/**
	 * True once V9 is tracking a flip for this item, from buy-basis capture onward — not only
	 * after the re-adjustment ladder has armed. The legacy sell-adjustment paths key off this to
	 * stand down, so a flip whose first listing has not yet fired can never be grabbed by the
	 * legacy path and re-priced below its V9 target.
	 */
	public static boolean v9OwnsSell(boolean v9Enabled, V9FlipState state)
	{
		return v9Enabled && state != null;
	}

	/**
	 * Sell target for the first listing: the live session recommendation when present, otherwise
	 * the target snapshotted on the flip state at buy time. Returns 0 when neither is available.
	 */
	public static long resolveFirstListingTarget(Long sessionRecommendation, V9FlipState state)
	{
		if (sessionRecommendation != null && sessionRecommendation > 0)
		{
			return sessionRecommendation;
		}
		return state != null ? state.getOriginalTarget() : 0L;
	}
}
