package com.flipsmart.domain.flip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ActiveFlipDisplayFilterIdempotenceTest
{
	private static ActiveFlip flip(int itemId)
	{
		ActiveFlip f = new ActiveFlip();
		f.setItemId(itemId);
		f.setItemName("Item " + itemId);
		return f;
	}

	@Test
	public void filteringAnAlreadyFilteredListChangesNothing()
	{
		List<ActiveFlip> backend = new ArrayList<>(Arrays.asList(flip(4151), flip(560), flip(2)));
		Set<Integer> active = new HashSet<>(Arrays.asList(4151));
		Set<Integer> inventory = new HashSet<>(Arrays.asList(560));

		List<ActiveFlip> once = ActiveFlipDisplayFilter.retain(backend, active, inventory);
		List<ActiveFlip> twice = ActiveFlipDisplayFilter.retain(once, active, inventory);

		assertEquals(2, once.size());
		assertEquals(once, twice);
	}

	@Test
	public void serverFilteredEmptyListStaysEmpty()
	{
		List<ActiveFlip> serverFiltered = Collections.emptyList();
		List<ActiveFlip> result = ActiveFlipDisplayFilter.retain(
			serverFiltered, Collections.emptySet(), Collections.emptySet());
		assertEquals(0, result.size());
	}
}
