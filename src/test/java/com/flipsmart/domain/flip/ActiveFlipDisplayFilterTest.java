package com.flipsmart.domain.flip;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers issue #914: the Active Flips tab must drop completed / cancelled /
 * collected trades and only show flips backed by live player state.
 */
public class ActiveFlipDisplayFilterTest
{
	private static ActiveFlip flip(int itemId, String name)
	{
		ActiveFlip f = new ActiveFlip();
		f.setItemId(itemId);
		f.setItemName(name);
		return f;
	}

	private static Set<Integer> setOf(int... ids)
	{
		Set<Integer> set = new HashSet<>();
		for (int id : ids)
		{
			set.add(id);
		}
		return set;
	}

	@Test
	public void retainsFlipWithOpenGeOfferOrCollectedThisSession()
	{
		ActiveFlip inGe = flip(1, "In GE");
		List<ActiveFlip> result = ActiveFlipDisplayFilter.retain(
			Collections.singletonList(inGe), setOf(1), Collections.emptySet());

		assertEquals(Collections.singletonList(inGe), result);
	}

	@Test
	public void retainsFlipHeldInInventory()
	{
		ActiveFlip inInventory = flip(2, "Awaiting sale");
		List<ActiveFlip> result = ActiveFlipDisplayFilter.retain(
			Collections.singletonList(inInventory), Collections.emptySet(), setOf(2));

		assertEquals(Collections.singletonList(inInventory), result);
	}

	@Test
	public void excludesCompletedFlipAbsentFromAllLiveState()
	{
		// Swamp Tar sold and collected: no open offer, not collected this session, not in inventory.
		ActiveFlip soldSwampTar = flip(1939, "Swamp tar");
		List<ActiveFlip> result = ActiveFlipDisplayFilter.retain(
			Collections.singletonList(soldSwampTar), Collections.emptySet(), Collections.emptySet());

		assertTrue("A sold/collected flip must not linger", result.isEmpty());
	}

	@Test
	public void keepsOnlyLiveFlipsFromAMixedList()
	{
		ActiveFlip inGe = flip(1, "In GE");
		ActiveFlip inInventory = flip(2, "Inventory");
		ActiveFlip stale = flip(3, "Stale");
		List<ActiveFlip> flips = Arrays.asList(inGe, stale, inInventory);

		List<ActiveFlip> result = ActiveFlipDisplayFilter.retain(
			flips, setOf(1), setOf(2));

		// Stale dropped; survivors keep their input order.
		assertEquals(Arrays.asList(inGe, inInventory), result);
	}

	@Test
	public void nullFlipListReturnsEmpty()
	{
		assertTrue(ActiveFlipDisplayFilter.retain(null, setOf(1), setOf(2)).isEmpty());
	}

	@Test
	public void nullStateSetsAreTreatedAsEmpty()
	{
		ActiveFlip anyFlip = flip(1, "Any");
		Set<Integer> none = null;
		List<ActiveFlip> result = ActiveFlipDisplayFilter.retain(
			Collections.singletonList(anyFlip), none, none);

		assertTrue(result.isEmpty());
	}
}
