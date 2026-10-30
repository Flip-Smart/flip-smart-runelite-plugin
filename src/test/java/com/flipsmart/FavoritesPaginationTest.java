package com.flipsmart;

import com.flipsmart.ui.panel.Paginator;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FavoritesPaginationTest
{
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
