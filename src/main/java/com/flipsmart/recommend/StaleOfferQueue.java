package com.flipsmart.recommend;

import com.flipsmart.domain.offer.OfferRecord;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Owns the stale-offer queue and its companion price/net maps plus the
 * already-prompted set. Guides the user through stale offers one at a time.
 *
 * Not thread-safe; guarded by AutoRecommendService's monitor. Every method is
 * only ever called while AutoRecommendService holds its {@code this} monitor.
 * The concurrent collection types are retained from the original fields for
 * behavioural parity, not because this class provides synchronization.
 */
public final class StaleOfferQueue
{
	private final List<OfferRecord> offers = new CopyOnWriteArrayList<>();
	private final Map<Integer, Integer> staleResellPrices = new ConcurrentHashMap<>();
	// Advisor-only: net profit/loss estimate to show alongside a re-sell prompt. Kept in
	// sync with staleResellPrices at both put-sites, so it's never read with a stale price.
	private final Map<Integer, Integer> staleResellNet = new ConcurrentHashMap<>();
	// Items that have already been flagged as stale/uncompetitive — prevents repeated prompts.
	// Cleared when the offer is cancelled, filled, or a new offer is placed for the item.
	private final Set<Integer> promptedStaleItems = ConcurrentHashMap.newKeySet();

	public boolean isEmpty()
	{
		return offers.isEmpty();
	}

	public int size()
	{
		return offers.size();
	}

	public OfferRecord head()
	{
		return offers.isEmpty() ? null : offers.get(0);
	}

	public List<OfferRecord> snapshot() {
		return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(offers));
	}

	public boolean headIsItem(int itemId)
	{
		return !offers.isEmpty() && offers.get(0).getItemId() == itemId;
	}

	public OfferRecord findByItemId(int itemId)
	{
		for (OfferRecord o : offers)
		{
			if (o.getItemId() == itemId)
			{
				return o;
			}
		}
		return null;
	}

	public Integer getResellPrice(int itemId)
	{
		return staleResellPrices.get(itemId);
	}

	public void putResellPrice(int itemId, int price)
	{
		staleResellPrices.put(itemId, price);
	}

	public Integer getResellNet(int itemId)
	{
		return staleResellNet.get(itemId);
	}

	public void putResellNet(int itemId, int net)
	{
		staleResellNet.put(itemId, net);
	}

	public void removeResellNet(int itemId)
	{
		staleResellNet.remove(itemId);
	}

	public void addPrompted(int itemId)
	{
		promptedStaleItems.add(itemId);
	}

	public void removePrompted(int itemId)
	{
		promptedStaleItems.remove(itemId);
	}

	/**
	 * Forget an item entirely: drop it from the queue and its resell price.
	 * Used by buy/cancel/collect handlers when the offer leaves the GE.
	 * Does not touch the prompted set (callers manage that separately, matching
	 * the original ordering of side effects).
	 */
	public void removeOffer(int itemId)
	{
		offers.removeIf(o -> o.getItemId() == itemId);
		staleResellPrices.remove(itemId);
		staleResellNet.remove(itemId);
	}

	/** Remove the head offer and clear its resell price; returns the removed offer. */
	public OfferRecord removeHead()
	{
		OfferRecord skipped = offers.remove(0);
		staleResellPrices.remove(skipped.getItemId());
		staleResellNet.remove(skipped.getItemId());
		return skipped;
	}

	/** Outcome of {@link #addIfAbsent(OfferRecord)}. */
	public enum AddResult
	{
		/** An entry for this item already existed — nothing changed. */
		ALREADY_PRESENT,
		/** The offer was appended to a previously non-empty offers. */
		ADDED,
		/** The offer was added and the queue was empty before — surface it now. */
		ADDED_WAS_EMPTY
	}

	/**
	 * Add an offer to the queue if no entry for its item already exists, marking
	 * it prompted. The return value lets the caller distinguish the dedup no-op
	 * from a genuine add and decide whether to surface the first prompt.
	 */
	public AddResult addIfAbsent(OfferRecord offer)
	{
		for (OfferRecord existing : offers)
		{
			if (existing.getItemId() == offer.getItemId())
			{
				return AddResult.ALREADY_PRESENT;
			}
		}
		boolean wasEmpty = offers.isEmpty();
		offers.add(offer);
		promptedStaleItems.add(offer.getItemId());
		return wasEmpty ? AddResult.ADDED_WAS_EMPTY : AddResult.ADDED;
	}

	/**
	 * Drop every queued offer for which {@code shouldRemove} returns true,
	 * clearing the matching resell price for each removed entry. The predicate
	 * is supplied by the coordinator because relevance depends on live session
	 * and competitiveness state outside this class.
	 */
	public void pruneIrrelevant(Predicate<OfferRecord> shouldRemove)
	{
		offers.removeIf(o ->
		{
			if (shouldRemove.test(o))
			{
				staleResellPrices.remove(o.getItemId());
				staleResellNet.remove(o.getItemId());
				return true;
			}
			return false;
		});
	}

	/** Clear all stale-queue state. Used by stop(). */
	public void clear()
	{
		promptedStaleItems.clear();
		offers.clear();
		staleResellPrices.clear();
		staleResellNet.clear();
	}
}
