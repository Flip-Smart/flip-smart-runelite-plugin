package com.flipsmart.recommend;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the buy/sell adjustment-timer state: per-item buy deadlines, sell
 * adjustment states, and the buy-price cost-basis map.
 *
 * Not thread-safe; guarded by AutoRecommendService's monitor. Every method is
 * only ever called while AutoRecommendService holds its {@code this} monitor,
 * so this class introduces no locks of its own. The API delegation and response
 * handling stay in the coordinator (they touch session, config, and callbacks);
 * this class holds the state and the bounded timer bookkeeping.
 */
public final class AdjustmentService
{
	/**
	 * Tracks state for a pending sell-side adjustment timer.
	 */
	public static final class SellAdjustmentState
	{
		public final int itemId;
		public final String itemName;
		public final int averageBuyPrice;
		public long deadline;
		public int adjustmentCount;

		public SellAdjustmentState(int itemId, String itemName, int averageBuyPrice, long deadline)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.averageBuyPrice = averageBuyPrice;
			this.deadline = deadline;
			this.adjustmentCount = 0;
		}
	}

	// Buy adjustment timer deadlines: itemId → System.currentTimeMillis() when timer expires
	private final Map<Integer, Long> adjustmentDeadlines = new ConcurrentHashMap<>();

	// Sell adjustment tracking
	private final Map<Integer, SellAdjustmentState> sellAdjustmentStates = new ConcurrentHashMap<>();

	// Buy prices stored when buy orders are placed — used as cost basis for sell adjustments
	private final Map<Integer, Integer> buyPrices = new ConcurrentHashMap<>();

	// =====================
	// Buy deadlines
	// =====================

	public boolean hasBuyDeadlines()
	{
		return !adjustmentDeadlines.isEmpty();
	}

	public int buyDeadlineCount()
	{
		return adjustmentDeadlines.size();
	}

	public boolean hasBuyDeadline(int itemId)
	{
		return adjustmentDeadlines.containsKey(itemId);
	}

	public void putBuyDeadline(int itemId, long deadline)
	{
		adjustmentDeadlines.put(itemId, deadline);
	}

	/** Remove a buy deadline; returns true if one was present. */
	public boolean removeBuyDeadline(int itemId)
	{
		return adjustmentDeadlines.remove(itemId) != null;
	}

	public Iterator<Map.Entry<Integer, Long>> buyDeadlineIterator()
	{
		return adjustmentDeadlines.entrySet().iterator();
	}

	// =====================
	// Sell adjustment states
	// =====================

	public boolean hasSellStates()
	{
		return !sellAdjustmentStates.isEmpty();
	}

	public boolean hasSellState(int itemId)
	{
		return sellAdjustmentStates.containsKey(itemId);
	}

	public SellAdjustmentState getSellState(int itemId)
	{
		return sellAdjustmentStates.get(itemId);
	}

	public void putSellState(int itemId, SellAdjustmentState state)
	{
		sellAdjustmentStates.put(itemId, state);
	}

	/** Remove a sell state; returns true if one was present. */
	public boolean removeSellState(int itemId)
	{
		return sellAdjustmentStates.remove(itemId) != null;
	}

	public Iterator<Map.Entry<Integer, SellAdjustmentState>> sellStateIterator()
	{
		return sellAdjustmentStates.entrySet().iterator();
	}

	// =====================
	// Buy prices (cost basis)
	// =====================

	public void putBuyPrice(int itemId, int price)
	{
		buyPrices.put(itemId, price);
	}

	public Integer getBuyPrice(int itemId)
	{
		return buyPrices.get(itemId);
	}

	public Integer getBuyPriceOrDefault(int itemId, int fallback)
	{
		return buyPrices.getOrDefault(itemId, fallback);
	}

	public void removeBuyPrice(int itemId)
	{
		buyPrices.remove(itemId);
	}

	public boolean buyPricesEmpty()
	{
		return buyPrices.isEmpty();
	}

	public Map<Integer, Integer> buyPricesSnapshot()
	{
		return new HashMap<>(buyPrices);
	}

	public void putAllBuyPrices(Map<Integer, Integer> prices)
	{
		buyPrices.putAll(prices);
	}

	// =====================
	// Lifecycle
	// =====================

	/** Clear all adjustment state. Used by stop(). */
	public void clear()
	{
		adjustmentDeadlines.clear();
		sellAdjustmentStates.clear();
		buyPrices.clear();
	}
}
