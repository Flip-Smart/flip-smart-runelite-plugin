package com.flipsmart;

import com.flipsmart.recommend.ActionDecision;
import com.flipsmart.recommend.ActionStep;
import net.runelite.client.Notifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * Fires a RuneLite notification when a Grand Exchange offer needs the player's action,
 * so a player who is not watching the client does not miss it.
 *
 * Alert identity is deliberately coarser than {@link ActionDecision} equality. An
 * empty-slot buy carries the Flip Finder queue's current suggestion, which rotates on
 * every refresh — but what the player must do, "a slot is free", does not change when
 * the suggested item does, so PLACE_BUY is keyed item-agnostically.
 *
 * Enable/sound/desktop gating belongs to RuneLite: {@link Notifier#notify} no-ops when
 * the notification is disabled, and honours the player's global notification settings
 * unless they opt into overriding them.
 */
public class ActionAlertNotifier
{
	/** Minimum gap before the same alert key may fire again. */
	static final long COOLDOWN_MS = 60_000L;

	/** Housekeeping cap; expired entries are pruned lazily well before this. */
	private static final int MAX_ENTRIES = 64;

	private final Notifier notifier;
	private final FlipSmartConfig config;
	private final IntFunction<String> itemNames;
	private final BooleanSupplier clientFocused;
	private final LongSupplier clock;

	/** alert key -> wall-clock instant at which it may fire again. */
	private final Map<String, Long> cooldownUntilMillis = new ConcurrentHashMap<>();

	private volatile String lastAlertKey;

	public ActionAlertNotifier(Notifier notifier, FlipSmartConfig config, IntFunction<String> itemNames,
		BooleanSupplier clientFocused)
	{
		this(notifier, config, itemNames, clientFocused, System::currentTimeMillis);
	}

	/** Test seam: inject a deterministic clock. */
	ActionAlertNotifier(Notifier notifier, FlipSmartConfig config, IntFunction<String> itemNames,
		BooleanSupplier clientFocused, LongSupplier clock)
	{
		this.notifier = notifier;
		this.config = config;
		this.itemNames = itemNames;
		this.clientFocused = clientFocused;
		this.clock = clock;
	}

	/**
	 * Offer a decision for alerting. Safe to call on every resolve and every tick —
	 * repeats of an unchanged action are silent.
	 */
	@SuppressWarnings("PMD.NullAssignment")
	public void onDecision(ActionDecision decision)
	{
		String key = alertKey(decision);
		if (key == null)
		{
			lastAlertKey = null;
			return;
		}
		if (key.equals(lastAlertKey))
		{
			return;
		}
		// While the client is focused RuneLite discards the notification, so treating it
		// as delivered would strand the action: the player walks away and is never told.
		// Leave it unconsumed instead — the next tick re-offers it once they look away.
		if (clientFocused.getAsBoolean())
		{
			return;
		}
		lastAlertKey = key;
		if (isCoolingDown(key))
		{
			return;
		}
		mark(key);
		notifier.notify(config.actionAlert(), message(decision, itemNames));
	}

	/** The player-facing identity of an action, or null when there is nothing to do. */
	static String alertKey(ActionDecision decision)
	{
		if (decision == null || decision.getStep() == ActionStep.NONE)
		{
			return null;
		}
		switch (decision.getStep())
		{
			case PLACE_BUY:
				return "PLACE_BUY";
			case LIST:
				return "LIST:" + decision.getItemId();
			default:
				return decision.getStep() + ":" + decision.getItemId() + ":" + decision.getSlot();
		}
	}

	static String message(ActionDecision decision, IntFunction<String> itemNames)
	{
		switch (decision.getStep())
		{
			case PLACE_BUY:
				return "A Grand Exchange slot is free";
			case LIST:
				return "List " + itemNames.apply(decision.getItemId()) + " to sell";
			case CANCEL:
				return "Cancel your " + itemNames.apply(decision.getItemId()) + " offer";
			case REPRICE:
				return "Adjust the price of your " + itemNames.apply(decision.getItemId()) + " offer";
			case COLLECT:
				return "Collect your " + itemNames.apply(decision.getItemId());
			default:
				return "A Grand Exchange offer needs attention";
		}
	}

	private boolean isCoolingDown(String key)
	{
		Long until = cooldownUntilMillis.get(key);
		if (until == null)
		{
			return false;
		}
		if (clock.getAsLong() >= until)
		{
			cooldownUntilMillis.remove(key);
			return false;
		}
		return true;
	}

	private void mark(String key)
	{
		long now = clock.getAsLong();
		cooldownUntilMillis.put(key, now + COOLDOWN_MS);
		if (cooldownUntilMillis.size() > MAX_ENTRIES)
		{
			cooldownUntilMillis.entrySet().removeIf(entry -> now >= entry.getValue());
		}
	}
}
