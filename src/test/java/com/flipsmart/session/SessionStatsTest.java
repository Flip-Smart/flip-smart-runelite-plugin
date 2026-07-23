package com.flipsmart.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.flip.CompletedFlip;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class SessionStatsTest
{
	private static final long SESSION_START =
		Instant.parse("2026-07-22T12:00:00Z").toEpochMilli();

	private static CompletedFlip completed(String sellTime, long netProfit)
	{
		CompletedFlip f = new CompletedFlip();
		f.setSellTime(sellTime);
		f.setNetProfit(netProfit);
		return f;
	}

	private static ActiveFlip active(int itemId, Integer recSell, int avgBuy, int qty)
	{
		ActiveFlip a = new ActiveFlip();
		a.setItemId(itemId);
		a.setRecommendedSellPrice(recSell);
		a.setAverageBuyPrice(avgBuy);
		a.setTotalQuantity(qty);
		return a;
	}

	@Test
	public void realisedProfitCountsOnlyFlipsSoldWithinSession()
	{
		long realised = SessionStats.realisedProfit(Arrays.asList(
			completed("2026-07-22T11:00:00Z", 500),   // before session start
			completed("2026-07-22T13:00:00Z", 1000)), // after
			SESSION_START);
		assertEquals(1000L, realised);
	}

	@Test
	public void realisedProfitExcludesUnparseableSellTimes()
	{
		long realised = SessionStats.realisedProfit(
			Collections.singletonList(completed(null, 999)), SESSION_START);
		assertEquals(0L, realised);
	}

	@Test
	public void unrealisedProfitValuesHeldQtyAtRecommendedSellMinusTax()
	{
		// (200 - 100 - 4 tax) * 10 = 960
		long unrealised = SessionStats.unrealisedProfit(
			Collections.singletonList(active(4151, 200, 100, 10)));
		assertEquals(960L, unrealised);
	}

	@Test
	public void unrealisedProfitSkipsNullTargetAndEmptyQty()
	{
		long unrealised = SessionStats.unrealisedProfit(Arrays.asList(
			active(4151, null, 100, 10),
			active(4151, 200, 100, 0)));
		assertEquals(0L, unrealised);
	}

	@Test
	public void gpPerHourIsNullBelowMinimumSessionTime()
	{
		assertNull(SessionStats.gpPerHour(1000L, 59_999L));
	}

	@Test
	public void gpPerHourScalesProfitToAnHour()
	{
		assertEquals(Long.valueOf(2000L), SessionStats.gpPerHour(1000L, 1_800_000L));
	}

	@Test
	public void computeCombinesRealisedAndProjected()
	{
		SessionStats.Snapshot snap = SessionStats.compute(
			Collections.singletonList(completed("2026-07-22T13:00:00Z", 1000)),
			Collections.singletonList(active(4151, 200, 100, 10)),
			SESSION_START, 3_600_000L);
		assertEquals(1000L, snap.realisedProfit);
		assertEquals(1960L, snap.projectedProfit);   // 1000 + 960
		assertEquals(Long.valueOf(1000L), snap.realisedGpPerHour);
		assertEquals(Long.valueOf(1960L), snap.projectedGpPerHour);
	}

	@Test
	public void computeBaseCapturesRealisedAndUnrealisedSeparately()
	{
		SessionStats.ProfitBase base = SessionStats.computeBase(
			Collections.singletonList(completed("2026-07-22T13:00:00Z", 1000)),
			Collections.singletonList(active(4151, 200, 100, 10)),
			SESSION_START);
		assertEquals(1000L, base.realisedProfit);
		assertEquals(960L, base.unrealisedProfit);
	}

	@Test
	public void snapshotDerivesProjectedAndRatesFromBase()
	{
		SessionStats.Snapshot snap = SessionStats.snapshot(
			new SessionStats.ProfitBase(1000L, 960L), 3_600_000L);
		assertEquals(1000L, snap.realisedProfit);
		assertEquals(1960L, snap.projectedProfit);
		assertEquals(Long.valueOf(1000L), snap.realisedGpPerHour);
		assertEquals(Long.valueOf(1960L), snap.projectedGpPerHour);
	}

	@Test
	public void emptyBaseIsZeroed()
	{
		SessionStats.Snapshot snap = SessionStats.snapshot(SessionStats.ProfitBase.EMPTY, 3_600_000L);
		assertEquals(0L, snap.realisedProfit);
		assertEquals(0L, snap.projectedProfit);
	}

	@Test
	public void formatsSignedGpDurationAndRate()
	{
		assertEquals("+1.2M", SessionStats.formatSignedGp(1_200_000L));
		assertEquals("-1.5M", SessionStats.formatSignedGp(-1_500_000L));
		assertEquals("0", SessionStats.formatSignedGp(0L));
		assertEquals("01:47:12", SessionStats.formatDuration((1L * 3600 + 47 * 60 + 12) * 1000));
		assertEquals("00:00:00", SessionStats.formatDuration(0L));
		assertEquals("—", SessionStats.formatGpPerHour(null));
		assertEquals("+684.0K", SessionStats.formatGpPerHour(684_000L));
	}
}
