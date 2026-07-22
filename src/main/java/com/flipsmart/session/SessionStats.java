package com.flipsmart.session;

import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.flip.CompletedFlip;
import com.flipsmart.util.GeTax;
import com.flipsmart.util.GpUtils;
import com.flipsmart.util.TimeUtils;

import java.util.List;

/**
 * Pure session-performance math: realised profit from completed flips sold within
 * the session, projected profit including unrealised gains on open positions, and
 * the derived GP/hour rates. No Swing, no I/O.
 */
public final class SessionStats
{
	/** Below this much logged-in time, GP/hour is too volatile to be meaningful. */
	static final long MIN_ACTIVE_MS = 60_000L;
	private static final long MS_PER_HOUR = 3_600_000L;
	private static final String NOT_AVAILABLE = "—";

	private SessionStats()
	{
	}

	public static Snapshot compute(List<CompletedFlip> completed, List<ActiveFlip> active,
								   long sessionStartMs, long activeMs)
	{
		long realised = realisedProfit(completed, sessionStartMs);
		long projected = realised + unrealisedProfit(active);
		return new Snapshot(realised, projected, activeMs,
			gpPerHour(realised, activeMs), gpPerHour(projected, activeMs));
	}

	static long realisedProfit(List<CompletedFlip> completed, long sessionStartMs)
	{
		long sum = 0L;
		for (CompletedFlip flip : completed)
		{
			if (TimeUtils.parseIsoToMillis(flip.getSellTime()) >= sessionStartMs)
			{
				sum += flip.getNetProfit();
			}
		}
		return sum;
	}

	static long unrealisedProfit(List<ActiveFlip> active)
	{
		long sum = 0L;
		for (ActiveFlip flip : active)
		{
			Integer recommendedSell = flip.getRecommendedSellPrice();
			int heldQty = flip.getTotalQuantity();
			if (recommendedSell == null || heldQty <= 0)
			{
				continue;
			}
			long tax = GeTax.taxFor(flip.getItemId(), recommendedSell);
			long perItem = (long) recommendedSell - flip.getAverageBuyPrice() - tax;
			sum += perItem * heldQty;
		}
		return sum;
	}

	static Long gpPerHour(long profit, long activeMs)
	{
		if (activeMs < MIN_ACTIVE_MS)
		{
			return null;
		}
		return profit * MS_PER_HOUR / activeMs;
	}

	public static String formatSignedGp(long amount)
	{
		String magnitude = GpUtils.formatGPSigned(amount);
		return amount > 0 ? "+" + magnitude : magnitude;
	}

	public static String formatGpPerHour(Long perHour)
	{
		return perHour == null ? NOT_AVAILABLE : formatSignedGp(perHour);
	}

	public static String formatDuration(long ms)
	{
		long totalSeconds = Math.max(0L, ms) / 1000L;
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;
		return String.format("%02d:%02d:%02d", hours, minutes, seconds);
	}

	public static final class Snapshot
	{
		public final long realisedProfit;
		public final long projectedProfit;
		public final long activeMs;
		public final Long realisedGpPerHour;
		public final Long projectedGpPerHour;

		public Snapshot(long realisedProfit, long projectedProfit, long activeMs,
						Long realisedGpPerHour, Long projectedGpPerHour)
		{
			this.realisedProfit = realisedProfit;
			this.projectedProfit = projectedProfit;
			this.activeMs = activeMs;
			this.realisedGpPerHour = realisedGpPerHour;
			this.projectedGpPerHour = projectedGpPerHour;
		}
	}
}
