package com.flipsmart.domain.flip;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which items count as an in-flight flip.
 *
 * Prod (#1133 follow-up): the collected set is persisted per-RSN and restored at login, and its
 * don't-destroy-on-empty guard means an entry can never be cleared from disk once written. An
 * item sold in a later session therefore stayed in the restored collected set forever, kept
 * counting toward the free-tier cap, and pinned the user at "capReached=true" through refreshes
 * AND relogs — locking them out of recommendations entirely.
 *
 * Inventory is the proof a collected lot is still open. A collected item the player no longer
 * holds, with no live offer, is a finished flip.
 */
public class ActiveFlipItemIdsTest
{
	private static final int HELD = 11128;      // Berserker necklace, still in inventory
	private static final int SOLD = 6914;       // stale collected entry, already sold
	private static final int ON_OFFER = 31722;  // Rubium splinters, live GE sell

	private static Map<Integer, Integer> inventory(int... itemIds)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		for (int id : itemIds)
		{
			counts.put(id, 1);
		}
		return counts;
	}

	private static Set<Integer> ids(Integer... itemIds)
	{
		return new HashSet<>(Arrays.asList(itemIds));
	}

	@Test
	public void dropsCollectedItemsThePlayerNoLongerHolds()
	{
		Set<Integer> active = ActiveFlipItemIds.derive(
			ids(ON_OFFER), ids(HELD, SOLD), inventory(HELD), true);

		assertEquals(ids(ON_OFFER, HELD), active);
	}

	@Test
	public void keepsCollectedItemsStillInInventory()
	{
		Set<Integer> active = ActiveFlipItemIds.derive(
			Collections.emptySet(), ids(HELD), inventory(HELD), true);

		assertTrue(active.contains(HELD));
	}

	@Test
	public void alwaysKeepsItemsWithALiveOfferEvenWhenNotInInventory()
	{
		// A listed sell is out of the inventory by definition — it must still count.
		Set<Integer> active = ActiveFlipItemIds.derive(
			ids(ON_OFFER), Collections.emptySet(), inventory(), true);

		assertEquals(ids(ON_OFFER), active);
	}

	@Test
	public void trustsTheCollectedSetWhenNoInventorySnapshotIsReadable()
	{
		// Logged out: the snapshot is empty because it is unknown, not because the player
		// holds nothing. Dropping the set here would release the cap while logged out.
		Set<Integer> active = ActiveFlipItemIds.derive(
			Collections.emptySet(), ids(HELD, SOLD), inventory(), false);

		assertEquals(ids(HELD, SOLD), active);
	}

	@Test
	public void theStuckCapScenarioReleasesOnceTheSoldItemIsGone()
	{
		// Exactly the reported state: one live sell, plus a sold item lingering in the
		// restored collected set. Only the live sell should count, so a 2-item cap frees up.
		Set<Integer> active = ActiveFlipItemIds.derive(
			ids(ON_OFFER), ids(HELD), inventory(), true);

		assertEquals(ids(ON_OFFER), active);
		assertEquals(1, active.size());
	}

	@Test
	public void toleratesNullInputs()
	{
		assertEquals(Collections.emptySet(), ActiveFlipItemIds.derive(null, null, null, true));
	}
}
