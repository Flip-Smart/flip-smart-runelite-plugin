package com.flipsmart.recommend;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.recommend.StaleOfferQueue.AddResult;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for {@link StaleOfferQueue}, the stale-offer collaborator
 * extracted from {@code AutoRecommendService} in #733. Pins the dedup/prompt-once
 * add semantics, head/remove/prune bookkeeping, and the companion resell-price/net
 * maps before the deferred Codacy field-rename touches it.
 */
public class StaleOfferQueueTest
{
	private static OfferRecord sell(int itemId)
	{
		return OfferRecord.newOffer(itemId, 0, itemId, "item-" + itemId, false, 1, 100, 1000L);
	}

	@Test
	public void head_onEmptyQueue_returnsNullInsteadOfThrowing()
	{
		// The queue can be drained between an isEmpty() check and head() (the offer is
		// cancelled/filled on another path), so head() must be null-safe rather than throw
		// ArrayIndexOutOfBoundsException into the RuneLite client thread.
		assertNull("head() on an empty queue must return null, not throw",
			new StaleOfferQueue().head());
	}

	@Test
	public void head_returnsFirstQueuedOffer_whenNonEmpty()
	{
		StaleOfferQueue queue = new StaleOfferQueue();
		queue.addIfAbsent(OfferRecord.newOffer(1L, 0, 4151, "Abyssal whip", false, 1, 100, 1000L));

		OfferRecord head = queue.head();
		assertNotNull(head);
		assertEquals(4151, head.getItemId());
	}

	// ---- addIfAbsent tri-state dedup (first-prompt-once) ----

	@Test
	public void addIfAbsentReportsEmptyThenAppendThenDedup()
	{
		StaleOfferQueue q = new StaleOfferQueue();

		assertEquals("first add into an empty queue surfaces the prompt",
			AddResult.ADDED_WAS_EMPTY, q.addIfAbsent(sell(11)));
		assertEquals(1, q.size());

		assertEquals("a different item appends without surfacing",
			AddResult.ADDED, q.addIfAbsent(sell(12)));
		assertEquals(2, q.size());

		// Same item again (even a distinct offer id) is the dedup no-op — prompt only once.
		assertEquals("an item already queued must not be re-added or re-prompted",
			AddResult.ALREADY_PRESENT,
			q.addIfAbsent(OfferRecord.newOffer(99L, 0, 11, "item-11", false, 1, 100, 2000L)));
		assertEquals(2, q.size());
	}

	// ---- head / remove / prune ----

	@Test
	public void removeHeadReturnsOfferAndClearsItsResellPrice()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));
		q.putResellPrice(11, 150);

		OfferRecord removed = q.removeHead();

		assertEquals(11, removed.getItemId());
		assertNull("removeHead must clear the removed item's resell price", q.getResellPrice(11));
		assertTrue(q.isEmpty());
	}

	@Test
	public void removeOfferDropsItAndItsResellPriceKeepingOthers()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));
		q.addIfAbsent(sell(12));
		q.putResellPrice(11, 150);
		q.putResellPrice(12, 200);

		q.removeOffer(11);

		assertEquals(1, q.size());
		assertTrue(q.headIsItem(12));
		assertNull(q.getResellPrice(11));
		assertEquals(Integer.valueOf(200), q.getResellPrice(12));
	}

	@Test
	public void pruneIrrelevantRemovesMatchingAndClearsTheirResellPrice()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));
		q.addIfAbsent(sell(12));
		q.putResellPrice(11, 150);
		q.putResellPrice(12, 200);

		q.pruneIrrelevant(o -> o.getItemId() == 11);

		assertEquals(1, q.size());
		assertTrue(q.headIsItem(12));
		assertNull("pruned item's resell price must be cleared", q.getResellPrice(11));
		assertEquals(Integer.valueOf(200), q.getResellPrice(12));
	}

	// ---- resell price / net bookkeeping ----

	@Test
	public void resellPriceAndNetRoundTripAndRemove()
	{
		StaleOfferQueue q = new StaleOfferQueue();

		assertNull(q.getResellPrice(11));
		q.putResellPrice(11, 150);
		assertEquals(Integer.valueOf(150), q.getResellPrice(11));

		assertNull(q.getResellNet(11));
		q.putResellNet(11, -50);
		assertEquals(Integer.valueOf(-50), q.getResellNet(11));
		q.removeResellNet(11);
		assertNull(q.getResellNet(11));
	}

	@Test
	public void removeOfferAlsoClearsResellNet()
	{
		// staleResellNet is a companion map to staleResellPrices; removal must clear both
		// or the net estimate leaks for an item no longer queued.
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));
		q.putResellPrice(11, 150);
		q.putResellNet(11, -50);

		q.removeOffer(11);

		assertNull("removeOffer must clear the companion resell-net entry", q.getResellNet(11));
	}

	@Test
	public void removeHeadAlsoClearsResellNet()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));
		q.putResellNet(11, -50);

		q.removeHead();

		assertNull("removeHead must clear the companion resell-net entry", q.getResellNet(11));
	}

	@Test
	public void pruneIrrelevantAlsoClearsResellNet()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));
		q.putResellNet(11, -50);

		q.pruneIrrelevant(o -> o.getItemId() == 11);

		assertNull("pruneIrrelevant must clear the companion resell-net entry", q.getResellNet(11));
	}

	// ---- lookups ----

	@Test
	public void findByItemIdReturnsMatchOrNull()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));
		q.addIfAbsent(sell(12));

		assertEquals(12, q.findByItemId(12).getItemId());
		assertNull(q.findByItemId(999));
	}

	@Test
	public void headIsItemMatchesOnlyTheFront()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		assertFalse("empty queue head matches nothing", q.headIsItem(11));

		q.addIfAbsent(sell(11));
		q.addIfAbsent(sell(12));
		assertTrue(q.headIsItem(11));
		assertFalse(q.headIsItem(12));
	}

	// ---- snapshot ----

	@Test(expected = UnsupportedOperationException.class)
	public void snapshotIsUnmodifiable()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));
		q.snapshot().add(sell(12));
	}

	@Test
	public void snapshotIsDetachedFromLaterMutations()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));

		List<OfferRecord> snap = q.snapshot();
		q.removeHead();

		assertEquals("snapshot must not reflect mutations after it was taken", 1, snap.size());
	}

	// ---- lifecycle ----

	@Test
	public void clearWipesQueueResellPriceAndNet()
	{
		StaleOfferQueue q = new StaleOfferQueue();
		q.addIfAbsent(sell(11));
		q.putResellPrice(11, 150);
		q.putResellNet(11, -50);

		q.clear();

		assertTrue(q.isEmpty());
		assertNull(q.getResellPrice(11));
		assertNull(q.getResellNet(11));
	}
}
