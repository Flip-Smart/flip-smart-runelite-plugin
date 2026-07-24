package com.flipsmart;

import com.flipsmart.api.dto.FavoriteItem;
import com.flipsmart.ui.panel.FavoritesSort;
import com.flipsmart.ui.panel.Paginator;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FavoritesSortAndPaginationTest
{
	private FavoriteItem item(String name, int profit, int volume)
	{
		FavoriteItem i = new FavoriteItem();
		i.setItemName(name);
		i.setProfit(profit);
		i.setVolume(volume);
		return i;
	}

	@Test
	public void profitSortsHighToLow()
	{
		List<FavoriteItem> list = new ArrayList<>();
		list.add(item("A", 100, 1));
		list.add(item("B", 300, 1));
		list.add(item("C", 200, 1));
		list.sort(FavoritesSort.PROFIT.comparator());
		assertEquals("B", list.get(0).getItemName());
		assertEquals("C", list.get(1).getItemName());
		assertEquals("A", list.get(2).getItemName());
	}

	@Test
	public void volumeSortsHighToLow()
	{
		List<FavoriteItem> list = new ArrayList<>();
		list.add(item("A", 1, 50));
		list.add(item("B", 1, 90));
		list.sort(FavoritesSort.VOLUME.comparator());
		assertEquals("B", list.get(0).getItemName());
	}

	@Test
	public void alphabeticalSortsAtoZCaseInsensitive()
	{
		List<FavoriteItem> list = new ArrayList<>();
		list.add(item("banana", 1, 1));
		list.add(item("Apple", 1, 1));
		list.sort(FavoritesSort.ALPHABETICAL.comparator());
		assertEquals("Apple", list.get(0).getItemName());
		assertEquals("banana", list.get(1).getItemName());
	}

	@Test
	public void paginationSlicesAndCountsPages()
	{
		List<Integer> items = new ArrayList<>();
		for (int i = 0; i < 23; i++)
		{
			items.add(i);
		}
		assertEquals(3, Paginator.pageCount(23, 10));
		assertEquals(10, Paginator.page(items, 0, 10).size());
		assertEquals(3, Paginator.page(items, 2, 10).size()); // last partial page
		assertEquals(0, Paginator.page(items, 5, 10).size()); // out-of-range page
		assertEquals(1, Paginator.pageCount(0, 10)); // empty still one page
	}
}
