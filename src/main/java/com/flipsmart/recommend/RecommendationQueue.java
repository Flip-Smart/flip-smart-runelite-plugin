package com.flipsmart.recommend;

import com.flipsmart.domain.flip.FlipRecommendation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
	private final List<FlipRecommendation> recommendations = new ArrayList<>();
	// Cached item names from recommendations - survives queue refreshes
	private final Map<Integer, String> itemNames = new ConcurrentHashMap<>();
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
		return recommendations.size();
	}

	public boolean isEmpty()
	{
		return recommendations.isEmpty();
	}

	public FlipRecommendation get(int index)
	{
		return recommendations.get(index);
	}

	public List<FlipRecommendation> snapshot()
	{
		return new ArrayList<>(recommendations);
	}

	/** Unmodifiable view of the underlying list for scan helpers; reflects live reads. */
	public List<FlipRecommendation> view()
	{
		return Collections.unmodifiableList(recommendations);
	}

	public void clear()
	{
		recommendations.clear();
		currentIndex = 0;
	}

	public void clearAll()
	{
		recommendations.clear();
		itemNames.clear();
		currentIndex = 0;
	}

	public void putItemName(int itemId, String itemName)
	{
		// itemNames is a ConcurrentHashMap; a null name is simply not cached.
		if (itemName != null)
		{
			itemNames.put(itemId, itemName);
		}
	}

	public String getItemName(int itemId, String fallback)
	{
		return itemNames.getOrDefault(itemId, fallback);
	}

	/**
	 * Replace the queue contents with the given recommendations and reset the
	 * cursor to the front.
	 */
	public void replace(List<FlipRecommendation> newRecommendations)
	{
		// Copy first: the argument may alias the live backing list (e.g. view()), and
		// clear() would empty it before it could be re-added.
		List<FlipRecommendation> copy = new ArrayList<>(newRecommendations);
		recommendations.clear();
		recommendations.addAll(copy);
		currentIndex = 0;
	}

	/**
	 * Add every recommendation whose item id is not in {@code activeItemIds},
	 * caching all item names. Used by start() — does not reset the cursor.
	 */
	public void addFilteredByActive(List<FlipRecommendation> incoming, Set<Integer> activeItemIds)
	{
		for (FlipRecommendation rec : incoming)
		{
			if (!activeItemIds.contains(rec.getItemId()))
			{
				recommendations.add(rec);
			}
			if (rec.getItemName() != null)
			{
				itemNames.put(rec.getItemId(), rec.getItemName());
			}
		}
	}

	/**
	 * Append recommendations without touching the cursor. Used by the offline-sync
	 * restore path, which clears, re-populates, then sets a specific cursor — unlike
	 * {@link #replace(List)}, which resets the cursor to the front.
	 */
	public void addAll(List<FlipRecommendation> toAdd)
	{
		recommendations.addAll(toAdd);
	}

	/** Sort the queue by volume per hour ascending (slowest-filling items first). */
	public void sortByVolumeAscending()
	{
		recommendations.sort(Comparator.comparingDouble(FlipRecommendation::getVolumePerHour));
	}

	public FlipRecommendation getCurrentRecommendation()
	{
		if (currentIndex >= 0 && currentIndex < recommendations.size())
		{
			return recommendations.get(currentIndex);
		}
		return null;
	}

	/** Find a recommendation matching the given item ID from the queue. */
	public FlipRecommendation findRecommendationForItem(int itemId)
	{
		for (FlipRecommendation rec : recommendations)
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
		return currentIndex >= recommendations.size();
	}

	public boolean cursorWithinBounds()
	{
		return currentIndex < recommendations.size();
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
		while (currentIndex < recommendations.size())
		{
			FlipRecommendation next = recommendations.get(currentIndex);
			if (!activeItemIds.contains(next.getItemId())
				&& profitFn.applyAsInt(next) >= minProfit)
			{
				break;
			}
			currentIndex++;
		}
	}
}
