package com.flipsmart.api;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApiBackoffGateTest
{
	private final long[] now = {0L};

	private ApiBackoffGate gateWithFixedJitter()
	{
		// nextDouble() == 0.5 makes the jitter multiplier exactly 1.0
		return new ApiBackoffGate(new Random()
		{
			@Override
			public double nextDouble()
			{
				return 0.5;
			}
		}, () -> now[0]);
	}

	@Test
	public void allowsRequestsInitially()
	{
		assertTrue(gateWithFixedJitter().allowRequest());
	}

	@Test
	public void failureOpensBaseCooldownWindow()
	{
		ApiBackoffGate gate = gateWithFixedJitter();
		gate.recordFailure();

		assertFalse(gate.allowRequest());
		now[0] = ApiBackoffGate.BASE_COOLDOWN_MS - 1;
		assertFalse(gate.allowRequest());
		now[0] = ApiBackoffGate.BASE_COOLDOWN_MS;
		assertTrue(gate.allowRequest());
	}

	@Test
	public void cooldownDoublesPerConsecutiveFailure()
	{
		ApiBackoffGate gate = gateWithFixedJitter();
		gate.recordFailure();
		gate.recordFailure();
		gate.recordFailure();

		long expected = ApiBackoffGate.BASE_COOLDOWN_MS * 4;
		now[0] = expected - 1;
		assertFalse(gate.allowRequest());
		now[0] = expected;
		assertTrue(gate.allowRequest());
	}

	@Test
	public void cooldownCapsAtMax()
	{
		ApiBackoffGate gate = gateWithFixedJitter();
		for (int i = 0; i < 40; i++)
		{
			gate.recordFailure();
		}

		now[0] = ApiBackoffGate.MAX_COOLDOWN_MS - 1;
		assertFalse(gate.allowRequest());
		now[0] = ApiBackoffGate.MAX_COOLDOWN_MS;
		assertTrue(gate.allowRequest());
	}

	@Test
	public void successResetsBackoffToBase()
	{
		ApiBackoffGate gate = gateWithFixedJitter();
		gate.recordFailure();
		gate.recordFailure();
		gate.recordFailure();

		gate.recordSuccess();
		assertTrue(gate.allowRequest());

		gate.recordFailure();
		now[0] += ApiBackoffGate.BASE_COOLDOWN_MS;
		assertTrue(gate.allowRequest());
	}

	@Test
	public void jitterStaysWithinTwentyPercentOfBase()
	{
		ApiBackoffGate gate = new ApiBackoffGate(new Random(42), () -> now[0]);
		long lowerBound = (long) (ApiBackoffGate.BASE_COOLDOWN_MS * 0.8);
		long upperBound = (long) (ApiBackoffGate.BASE_COOLDOWN_MS * 1.2);

		for (int i = 0; i < 200; i++)
		{
			long start = now[0];
			gate.recordFailure();

			now[0] = start + lowerBound - 1;
			assertFalse("cooldown shorter than -20% jitter bound", gate.allowRequest());
			now[0] = start + upperBound + 1;
			assertTrue("cooldown longer than +20% jitter bound", gate.allowRequest());

			gate.recordSuccess();
		}
	}
}
