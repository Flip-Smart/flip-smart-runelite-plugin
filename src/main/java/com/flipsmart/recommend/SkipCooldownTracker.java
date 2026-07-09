package com.flipsmart.recommend;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Tracks a short per-item "leave me alone" window after a Flip Assist skip.
 *
 * When the player skips a recommendation or an action in auto-mode, that item is
 * held out of auto-surfacing for {@link #COOLDOWN_MS} so it does not immediately
 * re-appear on the next GE event, game tick, or Flip Finder refresh. Suppression is
 * intentionally limited to auto-surfacing — the passive orange highlight and a manual
 * GE offer-screen pull-up are unaffected.
 *
 * The window survives Flip Finder refreshes (the map is never touched by a refresh)
 * and is cleared only by natural expiry or an auto-mode toggle off-and-on via
 * {@link #clearAll()}. The clock is injectable so the cooldown can be unit-tested
 * without wall-clock sleeps.
 */
public class SkipCooldownTracker
{
	/** How long a skipped item stays out of auto-surfacing (5 minutes). */
	public static final long COOLDOWN_MS = 5 * 60 * 1000L;

	/** Housekeeping cap; expired entries are pruned lazily well before this. */
	private static final int MAX_ENTRIES = 500;

	/** itemId -> wall-clock instant at which the cooldown expires. */
	private final Map<Integer, Long> cooldownUntilMillis = new ConcurrentHashMap<>();

	private final LongSupplier clock;

	public SkipCooldownTracker()
	{
		this(System::currentTimeMillis);
	}

	/** Test seam: inject a deterministic clock. */
	SkipCooldownTracker(LongSupplier clock)
	{
		this.clock = clock;
	}

	/** Start (or restart) the cooldown window for an item. */
	public void skip(int itemId)
	{
		long now = clock.getAsLong();
		cooldownUntilMillis.put(itemId, now + COOLDOWN_MS);
		if (cooldownUntilMillis.size() > MAX_ENTRIES)
		{
			pruneExpired();
		}
	}

	/** True while the item is inside its skip window. Expires lazily on read. */
	public boolean isCoolingDown(int itemId)
	{
		Long until = cooldownUntilMillis.get(itemId);
		if (until == null)
		{
			return false;
		}
		if (clock.getAsLong() >= until)
		{
			cooldownUntilMillis.remove(itemId);
			return false;
		}
		return true;
	}

	/** Drop the cooldown for a single item — used when the player acts on it. */
	public void clear(int itemId)
	{
		cooldownUntilMillis.remove(itemId);
	}

	/** Drop every cooldown — used when auto-mode is toggled off and back on. */
	public void clearAll()
	{
		cooldownUntilMillis.clear();
	}

	/** Remove entries whose window has elapsed. */
	public void pruneExpired()
	{
		long now = clock.getAsLong();
		cooldownUntilMillis.entrySet().removeIf(entry -> now >= entry.getValue());
	}
}
