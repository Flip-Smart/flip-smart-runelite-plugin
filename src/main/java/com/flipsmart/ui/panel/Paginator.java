package com.flipsmart.ui.panel;

import java.util.Collections;
import java.util.List;

/** Client-side paging helper: pure, so it is unit-tested directly. */
public final class Paginator
{
	private Paginator()
	{
	}

	public static int pageCount(int total, int pageSize)
	{
		if (total <= 0)
		{
			return 1;
		}
		return (total + pageSize - 1) / pageSize;
	}

	public static <T> List<T> page(List<T> items, int pageIndex, int pageSize)
	{
		if (items == null || items.isEmpty())
		{
			return Collections.emptyList();
		}
		int from = pageIndex * pageSize;
		if (from >= items.size() || pageIndex < 0)
		{
			return Collections.emptyList();
		}
		int to = Math.min(from + pageSize, items.size());
		return items.subList(from, to);
	}
}
