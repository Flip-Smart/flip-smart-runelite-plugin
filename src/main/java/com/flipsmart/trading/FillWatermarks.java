package com.flipsmart.trading;

import java.util.HashMap;
import java.util.Map;

/**
 * Highest cumulative fill ever observed for each order.
 *
 * <p>The Grand Exchange reports cumulative totals per slot, never increments, so what is new
 * has to be derived by subtraction. Deriving it from whatever record happens to be in memory
 * makes the result depend on that record surviving — and it does not always survive a hop, a
 * reconcile, or a skipped persist. Holding the high-water mark here instead means a lost or
 * rewound record cannot re-report fills that were already counted, and the first observation
 * after a gap reports exactly the amount that filled during it.</p>
 *
 * <p>Marks only ever rise. Every path that brings marks in from elsewhere merges by maximum,
 * so restoring an older snapshot can add knowledge but never remove it.</p>
 */
public final class FillWatermarks
{
	/** What one observation newly revealed. Zero on a replay or a stale snapshot. */
	public static final class Delta
	{
		public final int quantity;
		public final long spent;

		Delta(int quantity, long spent)
		{
			this.quantity = quantity;
			this.spent = spent;
		}
	}

	private static final int FILLED = 0;
	private static final int SPENT = 1;
	private static final int FIRST_GENERATION = 1;

	private final Map<String, long[]> marks = new HashMap<>();
	private final Map<Integer, Integer> slotGeneration = new HashMap<>();

	/** Record a cumulative observation and return only what it added. */
	public synchronized Delta observe(OfferIdentity id, int cumulativeFilled, long cumulativeSpent)
	{
		String key = id.toString();
		long[] mark = marks.get(key);
		long priorFilled = mark == null ? 0L : mark[FILLED];
		long priorSpent = mark == null ? 0L : mark[SPENT];

		long raisedFilled = Math.max(priorFilled, cumulativeFilled);
		long raisedSpent = Math.max(priorSpent, cumulativeSpent);
		marks.put(key, new long[]{raisedFilled, raisedSpent});

		return new Delta((int) (raisedFilled - priorFilled), raisedSpent - priorSpent);
	}

	/** The generation currently in force for {@code slot}. */
	public synchronized int generationFor(int slot)
	{
		Integer generation = slotGeneration.get(slot);
		return generation == null ? FIRST_GENERATION : generation;
	}

	/**
	 * Called when a slot demonstrably turns over, so the order that follows starts from a zero
	 * baseline rather than inheriting the finished order's progress.
	 */
	public synchronized void advanceGeneration(int slot)
	{
		slotGeneration.put(slot, generationFor(slot) + 1);
	}

	public synchronized Map<String, long[]> export()
	{
		return new HashMap<>(marks);
	}

	/**
	 * Raise marks to the progress already recorded on {@code records}.
	 *
	 * <p>Clients upgrading from a build without marks have none persisted, so the first
	 * observation of an in-flight order would measure against zero and re-report the whole
	 * cumulative. The records themselves already carry the correct totals, so seeding from them
	 * starts the marks in the right place. Terminal records are skipped: they are finished, and
	 * no further observation of them can arrive.</p>
	 */
	public synchronized void seedFrom(java.util.Collection<com.flipsmart.domain.offer.OfferRecord> records)
	{
		if (records == null)
		{
			return;
		}
		for (com.flipsmart.domain.offer.OfferRecord r : records)
		{
			if (r == null || r.getSlot() == null || r.getState().isTerminal())
			{
				continue;
			}
			observe(OfferIdentity.of(r.getSlot(), r.getItemId(), r.isBuy(), generationFor(r.getSlot())),
				r.getFilledQuantity(), r.getSpent());
		}
	}

	/**
	 * Fold restored marks in, keeping the higher of each pair. Never replaces: a snapshot
	 * written before the current session's fills would otherwise rewind them, and the next
	 * observation would re-report fills already sent.
	 */
	public synchronized void mergeFrom(Map<String, long[]> restored)
	{
		if (restored == null)
		{
			return;
		}
		for (Map.Entry<String, long[]> entry : restored.entrySet())
		{
			long[] incoming = entry.getValue();
			if (incoming == null || incoming.length < 2)
			{
				continue;
			}
			long[] current = marks.get(entry.getKey());
			if (current == null)
			{
				marks.put(entry.getKey(), new long[]{incoming[FILLED], incoming[SPENT]});
				continue;
			}
			marks.put(entry.getKey(), new long[]{
				Math.max(current[FILLED], incoming[FILLED]),
				Math.max(current[SPENT], incoming[SPENT])
			});
		}
	}
}
