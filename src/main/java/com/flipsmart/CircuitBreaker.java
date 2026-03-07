package com.flipsmart;

import lombok.extern.slf4j.Slf4j;

/**
 * Simple circuit breaker to prevent hammering the API when it's down.
 * States: CLOSED (normal) -> OPEN (tripped) -> HALF_OPEN (testing) -> CLOSED
 */
@Slf4j
class CircuitBreaker
{
	enum State
	{
		CLOSED, OPEN, HALF_OPEN
	}

	private final int failureThreshold;
	private final long recoveryTimeoutMs;

	private State state = State.CLOSED;
	private int consecutiveFailures = 0;
	private long openedAt = 0;

	CircuitBreaker(int failureThreshold, long recoveryTimeoutMs)
	{
		this.failureThreshold = failureThreshold;
		this.recoveryTimeoutMs = recoveryTimeoutMs;
	}

	/**
	 * Check if a request is allowed through.
	 * In CLOSED state, always allows.
	 * In OPEN state, rejects unless recovery timeout has elapsed (transitions to HALF_OPEN).
	 * In HALF_OPEN state, allows one request through to test.
	 */
	synchronized boolean allowRequest()
	{
		switch (state)
		{
			case CLOSED:
				return true;
			case OPEN:
				if (System.currentTimeMillis() - openedAt >= recoveryTimeoutMs)
				{
					state = State.HALF_OPEN;
					log.info("Circuit breaker half-open, testing API connectivity");
					return true;
				}
				return false;
			case HALF_OPEN:
				return true;
			default:
				return true;
		}
	}

	/**
	 * Record a successful request. Resets failure count and closes circuit.
	 */
	synchronized void recordSuccess()
	{
		if (state == State.HALF_OPEN)
		{
			log.info("Circuit breaker closed, API is healthy");
		}
		consecutiveFailures = 0;
		state = State.CLOSED;
	}

	/**
	 * Record a failed request. Increments failure count and may trip the circuit.
	 */
	synchronized void recordFailure()
	{
		consecutiveFailures++;
		if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold)
		{
			state = State.OPEN;
			openedAt = System.currentTimeMillis();
			log.warn("Circuit breaker opened after {} consecutive failures, will retry in {}s",
				consecutiveFailures, recoveryTimeoutMs / 1000);
		}
	}

	synchronized State getState()
	{
		return state;
	}
}
