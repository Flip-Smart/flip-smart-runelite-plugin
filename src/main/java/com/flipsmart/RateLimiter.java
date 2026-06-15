package com.flipsmart;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Token bucket rate limiter. Permits are refilled on a fixed interval.
 */
@Slf4j
class RateLimiter
{
	private final Semaphore permits;
	private final int maxPermits;
	private final ScheduledExecutorService refillExecutor;

	RateLimiter(int maxPermits, long refillIntervalMs)
	{
		this.maxPermits = maxPermits;
		this.permits = new Semaphore(maxPermits);
		this.refillExecutor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "FlipSmart-RateLimiter");
			t.setDaemon(true);
			return t;
		});

		refillExecutor.scheduleAtFixedRate(this::refill, refillIntervalMs, refillIntervalMs, TimeUnit.MILLISECONDS);
	}

	/**
	 * Try to acquire a permit without blocking.
	 * @return true if a permit was acquired, false if rate limited
	 */
	boolean tryAcquire()
	{
		return permits.tryAcquire();
	}

	/**
	 * Refill permits back to max. Called on a fixed schedule.
	 */
	private void refill()
	{
		int currentPermits = permits.availablePermits();
		int toRelease = maxPermits - currentPermits;
		if (toRelease > 0)
		{
			permits.release(toRelease);
		}
	}

	/**
	 * Shutdown the refill timer. Call on plugin shutdown.
	 */
	void shutdown()
	{
		refillExecutor.shutdownNow();
	}
}
