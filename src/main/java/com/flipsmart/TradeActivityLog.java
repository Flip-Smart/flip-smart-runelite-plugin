package com.flipsmart;

import lombok.Value;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded, in-memory log of recent fulfilled GE trade events used by the Flip
 * Assist "waiting for flips" hint. Newest entries are at the front; older
 * entries beyond {@link #MAX_ENTRIES} are dropped.
 *
 * <p>Each entry represents a single fill event ("Bought 28x Bow string"); a
 * partially filled offer that fills again later produces two separate entries
 * because the user perceives them as distinct activity beats.
 */
@Singleton
public class TradeActivityLog
{
	public static final int MAX_ENTRIES = 4;

	@Value
	public static class Entry
	{
		int itemId;
		String itemName;
		boolean buy;
		int quantity;
		long timestampMs;
	}

	private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();

	public void addEntry(int itemId, String itemName, boolean isBuy, int quantity)
	{
		if (quantity <= 0 || itemName == null)
		{
			return;
		}
		entries.addFirst(new Entry(itemId, itemName, isBuy, quantity, System.currentTimeMillis()));
		while (entries.size() > MAX_ENTRIES)
		{
			entries.pollLast();
		}
	}

	public List<Entry> snapshot()
	{
		return Collections.unmodifiableList(new ArrayList<>(entries));
	}

	public boolean isEmpty()
	{
		return entries.isEmpty();
	}

	public void clear()
	{
		entries.clear();
	}
}
