package com.flipsmart.api;

import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Shared cooldown gate that suppresses API requests while the backend is failing.
 * Each consecutive failure opens an exponentially growing cooldown window with
 * ±20% jitter; any success closes the gate. Every request routed through the
 * transport consults the same instance, so all timers and event-driven polls
 * back off together instead of piling onto a struggling backend.
 */
class ApiBackoffGate
{
	static final long BASE_COOLDOWN_MS = 5_000L;
	static final long MAX_COOLDOWN_MS = 300_000L;
	static final double JITTER_FRACTION = 0.20;
	private static final int MAX_DOUBLINGS = 10;

	private final Random random;
	private final LongSupplier clock;
	private final Object lock = new Object();

	private int consecutiveFailures;
	private long cooldownUntilMillis;

	ApiBackoffGate()
	{
		this(new Random(), System::currentTimeMillis);
	}

	ApiBackoffGate(Random random, LongSupplier clock)
	{
		this.random = random;
		this.clock = clock;
	}

	boolean allowRequest()
	{
		synchronized (lock)
		{
			return clock.getAsLong() >= cooldownUntilMillis;
		}
	}

	void recordFailure()
	{
		synchronized (lock)
		{
			consecutiveFailures++;
			int doublings = Math.min(consecutiveFailures - 1, MAX_DOUBLINGS);
			long cooldown = Math.min(BASE_COOLDOWN_MS << doublings, MAX_COOLDOWN_MS);
			double jitterMultiplier = 1.0 + JITTER_FRACTION * (2 * random.nextDouble() - 1);
			cooldownUntilMillis = clock.getAsLong() + (long) (cooldown * jitterMultiplier);
		}
	}

	void recordSuccess()
	{
		synchronized (lock)
		{
			consecutiveFailures = 0;
			cooldownUntilMillis = 0;
		}
	}
}
