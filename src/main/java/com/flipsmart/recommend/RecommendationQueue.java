package com.flipsmart.recommend;

import com.flipsmart.domain.flip.FlipRecommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Owns the auto-recommend buy queue: the ordered recommendation list, the
 * monotonic cursor, cached item names, the offer-screen lock, and the
 * last-refresh timestamp.
 *
 * Not thread-safe; guarded by AutoRecommendService's monitor. Every method is
 * only ever called while AutoRecommendService holds its {@code this} monitor,
 * so this class deliberately introduces no locks of its own.
 */
public final class RecommendationQueue
{
	private final List<FlipRecommendation> recommendationQueue = new ArrayList<>();
	// Cached item names from recommendations - survives queue refreshes
	private final Map<Integer, String> itemNames = new HashMap<>();
	private int currentIndex;

	// Offer-screen lock. While set, the coordinator drops focus changes
	// for any item other than this one. null = unlocked.
	private volatile Integer lockedItemId;

	// Timestamp of last queue refresh for staleness checks
	private volatile long lastQueueRefreshMillis;

	public int getCurrentIndex()
	{
		return currentIndex;
	}

	public void setCurrentIndex(int index)
	{
		this.currentIndex = index;
	}

	public int size()
	{
		return recommendationQueue.size();
	}

	public boolean isEmpty()
	{
		return recommendationQueue.isEmpty();
	}

	public FlipRecommendation get(int index)
	{
		return recommendationQueue.get(index);
	}

	public List<FlipRecommendation> snapshot()
	{
		return new ArrayList<>(recommendationQueue);
	}

	/** Live, read-only view of the underlying list for scan helpers. */
	public List<FlipRecommendation> view()
	{
		return recommendationQueue;
	}

	public void clear()
	{
		recommendationQueue.clear();
	}

	public void clearAll()
	{
		recommendationQueue.clear();
		itemNames.clear();
	}

	public void putItemName(int itemId, String itemName)
	{
		itemNames.put(itemId, itemName);
	}

	public String getItemName(int itemId, String fallback)
	{
		return itemNames.getOrDefault(itemId, fallback);
	}

	/**
	 * Replace the queue contents with the given recommendations and reset the
	 * cursor to the front.
	 */
	public void replace(List<FlipRecommendation> recommendations)
	{
		recommendationQueue.clear();
		recommendationQueue.addAll(recommendations);
		currentIndex = 0;
	}

	/**
	 * Add every recommendation whose item id is not in {@code activeItemIds},
	 * caching all item names. Used by start() — does not reset the cursor.
	 */
	public void addFilteredByActive(List<FlipRecommendation> recommendations, Set<Integer> activeItemIds)
	{
		for (FlipRecommendation rec : recommendations)
		{
			if (!activeItemIds.contains(rec.getItemId()))
			{
				recommendationQueue.add(rec);
			}
			itemNames.put(rec.getItemId(), rec.getItemName());
		}
	}

	/** Sort the queue by volume per hour ascending (slowest-filling items first). */
	public void sortByVolumeAscending()
	{
		recommendationQueue.sort(Comparator.comparingDouble(FlipRecommendation::getVolumePerHour));
	}

	public FlipRecommendation getCurrentRecommendation()
	{
		if (currentIndex >= 0 && currentIndex < recommendationQueue.size())
		{
			return recommendationQueue.get(currentIndex);
		}
		return null;
	}

	/** Find a recommendation matching the given item ID from the queue. */
	public FlipRecommendation findRecommendationForItem(int itemId)
	{
		for (FlipRecommendation rec : recommendationQueue)
		{
			if (rec.getItemId() == itemId)
			{
				return rec;
			}
		}
		return null;
	}

	public boolean cursorBeyondEnd()
	{
		return currentIndex >= recommendationQueue.size();
	}

	public boolean cursorWithinBounds()
	{
		return currentIndex < recommendationQueue.size();
	}

	// =====================
	// Offer-screen lock
	// =====================

	public Integer getLockedItemId()
	{
		return lockedItemId;
	}

	public void setLockedItemId(Integer itemId)
	{
		this.lockedItemId = itemId;
	}

	// =====================
	// Refresh timestamp
	// =====================

	public long getLastQueueRefreshMillis()
	{
		return lastQueueRefreshMillis;
	}

	public void setLastQueueRefreshMillis(long millis)
	{
		this.lastQueueRefreshMillis = millis;
	}

	// =====================
	// Cursor advancement
	// =====================

	/**
	 * Advance {@code currentIndex} past any item that is currently on the GE or
	 * below the minimum adjusted profit, stopping at the first surfaceable buy
	 * or running off the end. Mirrors the skip-loop in advanceToNext().
	 *
	 * @param activeItemIds items currently live on the GE
	 * @param profitFn      adjusted-profit calculator for a recommendation
	 * @param minProfit     minimum acceptable adjusted profit
	 */
	public void skipToNextSurfaceable(
		Set<Integer> activeItemIds,
		ToIntFunction<FlipRecommendation> profitFn,
		int minProfit)
	{
		currentIndex++;
		while (currentIndex < recommendationQueue.size())
		{
			FlipRecommendation next = recommendationQueue.get(currentIndex);
			if (!activeItemIds.contains(next.getItemId())
				&& profitFn.applyAsInt(next) >= minProfit)
			{
				break;
			}
			currentIndex++;
		}
	}
}
