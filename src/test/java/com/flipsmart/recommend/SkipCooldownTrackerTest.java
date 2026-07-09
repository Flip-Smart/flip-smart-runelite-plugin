package com.flipsmart.recommend;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the 5-minute skip window used by Flip Assist (#919): a skipped item is held
 * out of auto-surfacing for {@link SkipCooldownTracker#COOLDOWN_MS}, the window expires
 * lazily, an auto-mode toggle clears every window, and distinct items are independent.
 */
public class SkipCooldownTrackerTest
{
	private static final int ITEM = 4151;
	private static final int OTHER_ITEM = 561;

	private long now;
	private SkipCooldownTracker tracker;

	@Before
	public void setUp()
	{
		now = 1_000_000L;
		tracker = new SkipCooldownTracker(() -> now);
	}

	/** AC1/AC6: a freshly skipped item is inside its window. */
	@Test
	public void skippedItemIsCoolingDown()
	{
		tracker.skip(ITEM);

		assertTrue(tracker.isCoolingDown(ITEM));
	}

	/** An item that was never skipped is not cooling down. */
	@Test
	public void unseenItemIsNotCoolingDown()
	{
		assertFalse(tracker.isCoolingDown(ITEM));
	}

	/** The window holds right up to the 5-minute boundary. */
	@Test
	public void stillCoolingJustBeforeExpiry()
	{
		tracker.skip(ITEM);

		now += SkipCooldownTracker.COOLDOWN_MS - 1;

		assertTrue(tracker.isCoolingDown(ITEM));
	}

	/** At/after the boundary the item is eligible again. */
	@Test
	public void expiresAtBoundary()
	{
		tracker.skip(ITEM);

		now += SkipCooldownTracker.COOLDOWN_MS;

		assertFalse(tracker.isCoolingDown(ITEM));
	}

	/** Acting on a skipped item clears only that item's window. */
	@Test
	public void clearReleasesOnlyTheGivenItem()
	{
		tracker.skip(ITEM);
		tracker.skip(OTHER_ITEM);

		tracker.clear(ITEM);

		assertFalse(tracker.isCoolingDown(ITEM));
		assertTrue(tracker.isCoolingDown(OTHER_ITEM));
	}

	/** AC5/AC6: toggling auto-mode clears every window. */
	@Test
	public void clearAllReleasesEveryItem()
	{
		tracker.skip(ITEM);
		tracker.skip(OTHER_ITEM);

		tracker.clearAll();

		assertFalse(tracker.isCoolingDown(ITEM));
		assertFalse(tracker.isCoolingDown(OTHER_ITEM));
	}

	/** Skipping one item does not suppress another. */
	@Test
	public void distinctItemsAreIndependent()
	{
		tracker.skip(ITEM);

		assertTrue(tracker.isCoolingDown(ITEM));
		assertFalse(tracker.isCoolingDown(OTHER_ITEM));
	}

	/** Re-skipping refreshes the window from the new skip instant. */
	@Test
	public void reSkipExtendsWindow()
	{
		tracker.skip(ITEM);

		now += SkipCooldownTracker.COOLDOWN_MS - 1_000;
		tracker.skip(ITEM);
		now += 2_000;

		// Would have expired under the first skip; the second skip keeps it cooling.
		assertTrue(tracker.isCoolingDown(ITEM));
	}

	/** pruneExpired drops elapsed windows and keeps live ones. */
	@Test
	public void pruneExpiredDropsOnlyElapsedWindows()
	{
		tracker.skip(ITEM);
		now += SkipCooldownTracker.COOLDOWN_MS - 1_000;
		tracker.skip(OTHER_ITEM);

		now += 2_000; // ITEM elapsed, OTHER_ITEM still live
		tracker.pruneExpired();

		assertFalse(tracker.isCoolingDown(ITEM));
		assertTrue(tracker.isCoolingDown(OTHER_ITEM));
	}
}
