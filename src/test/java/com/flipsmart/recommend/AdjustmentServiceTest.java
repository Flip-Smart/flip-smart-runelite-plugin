package com.flipsmart.recommend;

import com.flipsmart.recommend.AdjustmentService.SellAdjustmentState;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for {@link AdjustmentService}, the adjustment-timer state
 * collaborator extracted from {@code AutoRecommendService} in #733. Pins the buy
 * deadline / sell-adjustment-state / buy-price bookkeeping before the deferred
 * Codacy change (HashMap → ConcurrentHashMap) touches it.
 */
public class AdjustmentServiceTest
{
	// ---- buy deadlines ----

	@Test
	public void buyDeadlinePutHasRemoveAndCount()
	{
		AdjustmentService svc = new AdjustmentService();
		assertFalse(svc.hasBuyDeadlines());
		assertEquals(0, svc.buyDeadlineCount());

		svc.putBuyDeadline(11, 1000L);
		assertTrue(svc.hasBuyDeadlines());
		assertTrue(svc.hasBuyDeadline(11));
		assertFalse(svc.hasBuyDeadline(12));
		assertEquals(1, svc.buyDeadlineCount());

		assertTrue("removing a present deadline returns true", svc.removeBuyDeadline(11));
		assertFalse("removing an absent deadline returns false", svc.removeBuyDeadline(11));
		assertEquals(0, svc.buyDeadlineCount());
	}

	@Test
	public void buyDeadlineIteratorYieldsAllEntries()
	{
		AdjustmentService svc = new AdjustmentService();
		svc.putBuyDeadline(11, 1000L);
		svc.putBuyDeadline(12, 2000L);

		Map<Integer, Long> seen = new HashMap<>();
		svc.buyDeadlineIterator().forEachRemaining(e -> seen.put(e.getKey(), e.getValue()));

		assertEquals(2, seen.size());
		assertEquals(Long.valueOf(1000L), seen.get(11));
		assertEquals(Long.valueOf(2000L), seen.get(12));
	}

	// ---- sell adjustment states ----

	@Test
	public void sellStateConstructorDefaultsAndPutGetRemove()
	{
		AdjustmentService svc = new AdjustmentService();
		assertFalse(svc.hasSellStates());

		SellAdjustmentState state = new SellAdjustmentState(11, "item-11", 250, 5000L);
		assertEquals(11, state.itemId);
		assertEquals("item-11", state.itemName);
		assertEquals(250, state.averageBuyPrice);
		assertEquals(5000L, state.deadline);
		assertEquals("adjustmentCount starts at zero", 0, state.adjustmentCount);

		svc.putSellState(11, state);
		assertTrue(svc.hasSellStates());
		assertTrue(svc.hasSellState(11));
		assertEquals(state, svc.getSellState(11));
		assertNull(svc.getSellState(99));

		assertTrue(svc.removeSellState(11));
		assertFalse(svc.removeSellState(11));
		assertFalse(svc.hasSellStates());
	}

	@Test
	public void sellAdjustmentStateTransitionFieldsAreMutable()
	{
		// The adjustment "transition" is bumping the count and pushing the deadline forward;
		// both are public mutable fields the coordinator advances in place.
		SellAdjustmentState state = new SellAdjustmentState(11, "item-11", 250, 5000L);

		state.adjustmentCount++;
		state.deadline = 9000L;

		assertEquals(1, state.adjustmentCount);
		assertEquals(9000L, state.deadline);
	}

	@Test
	public void sellStateIteratorYieldsAllEntries()
	{
		AdjustmentService svc = new AdjustmentService();
		svc.putSellState(11, new SellAdjustmentState(11, "item-11", 250, 5000L));
		svc.putSellState(12, new SellAdjustmentState(12, "item-12", 300, 6000L));

		Map<Integer, SellAdjustmentState> seen = new HashMap<>();
		svc.sellStateIterator().forEachRemaining(e -> seen.put(e.getKey(), e.getValue()));

		assertEquals(2, seen.size());
		assertEquals(250, seen.get(11).averageBuyPrice);
		assertEquals(300, seen.get(12).averageBuyPrice);
	}

	// ---- buy prices (cost basis) ----

	@Test
	public void buyPricePutGetDefaultRemoveAndEmpty()
	{
		AdjustmentService svc = new AdjustmentService();
		assertTrue(svc.buyPricesEmpty());

		svc.putBuyPrice(11, 250);
		assertEquals(Integer.valueOf(250), svc.getBuyPrice(11));
		assertEquals(Integer.valueOf(250), svc.getBuyPriceOrDefault(11, 9));
		assertEquals("absent item falls back to the default", Integer.valueOf(9), svc.getBuyPriceOrDefault(99, 9));
		assertNull(svc.getBuyPrice(99));
		assertFalse(svc.buyPricesEmpty());

		svc.removeBuyPrice(11);
		assertTrue(svc.buyPricesEmpty());
	}

	@Test
	public void buyPricesSnapshotIsDetached()
	{
		AdjustmentService svc = new AdjustmentService();
		svc.putBuyPrice(11, 250);

		Map<Integer, Integer> snap = svc.buyPricesSnapshot();
		snap.put(12, 999);

		assertNull("mutating the snapshot must not leak into the service", svc.getBuyPrice(12));
		assertEquals(Integer.valueOf(250), svc.getBuyPrice(11));
	}

	@Test
	public void putAllBuyPricesMergesWithoutDroppingExisting()
	{
		AdjustmentService svc = new AdjustmentService();
		svc.putBuyPrice(11, 250);

		Map<Integer, Integer> more = new HashMap<>();
		more.put(12, 300);
		more.put(13, 400);
		svc.putAllBuyPrices(more);

		assertEquals(Integer.valueOf(250), svc.getBuyPrice(11));
		assertEquals(Integer.valueOf(300), svc.getBuyPrice(12));
		assertEquals(Integer.valueOf(400), svc.getBuyPrice(13));
	}

	// ---- lifecycle ----

	@Test
	public void clearWipesAllThreeMaps()
	{
		AdjustmentService svc = new AdjustmentService();
		svc.putBuyDeadline(11, 1000L);
		svc.putSellState(11, new SellAdjustmentState(11, "item-11", 250, 5000L));
		svc.putBuyPrice(11, 250);

		svc.clear();

		assertFalse(svc.hasBuyDeadlines());
		assertFalse(svc.hasSellStates());
		assertTrue(svc.buyPricesEmpty());
	}
}
