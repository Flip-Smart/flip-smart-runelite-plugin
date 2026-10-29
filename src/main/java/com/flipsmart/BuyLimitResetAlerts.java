package com.flipsmart;

import net.runelite.client.Notifier;
import net.runelite.client.config.Notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Notifies once when a tracked item's 4h buy limit window ends, so the player can
 * re-buy it straight away. Windows already over when tracked are ignored.
 */
public class BuyLimitResetAlerts
{
	private final Notifier notifier;
	private final Supplier<Notification> notification;
	private final IntFunction<String> itemNames;
	private final LongSupplier clock;

	/** itemId -> epoch millis at which its buy limit resets. */
	private final Map<Integer, Long> resetsAt = new ConcurrentHashMap<>();

	public BuyLimitResetAlerts(Notifier notifier, Supplier<Notification> notification,
		IntFunction<String> itemNames, LongSupplier clock)
	{
		this.notifier = notifier;
		this.notification = notification;
		this.itemNames = itemNames;
		this.clock = clock;
	}

	public void track(int itemId, Instant resetAt)
	{
		if (resetAt != null && resetAt.toEpochMilli() > clock.getAsLong())
		{
			resetsAt.put(itemId, resetAt.toEpochMilli());
		}
	}

	public void clear()
	{
		resetsAt.clear();
	}

	/** Fire one notification covering every window that has ended since the last poll. */
	public void poll()
	{
		long now = clock.getAsLong();
		List<String> names = new ArrayList<>();
		for (Iterator<Map.Entry<Integer, Long>> it = resetsAt.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, Long> entry = it.next();
			if (entry.getValue() <= now)
			{
				it.remove();
				names.add(itemNames.apply(entry.getKey()));
			}
		}
		if (!names.isEmpty())
		{
			notifier.notify(notification.get(), message(names));
		}
	}

	static String message(List<String> names)
	{
		return (names.size() == 1 ? "Buy limit reset: " : "Buy limits reset: ") + String.join(", ", names);
	}
}
