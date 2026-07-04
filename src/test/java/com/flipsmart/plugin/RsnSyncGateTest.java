package com.flipsmart.plugin;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RsnSyncGateTest
{
	private static final String RSN_A = "PlayerOne";
	private static final String RSN_B = "PlayerTwo";

	@Test
	public void pushesAnRsnItHasNeverSeen()
	{
		assertTrue(new RsnSyncGate().shouldPush(RSN_A));
	}

	@Test
	public void suppressesRepeatPushOfConfirmedRsn()
	{
		RsnSyncGate gate = new RsnSyncGate();
		gate.markPushed(RSN_A);
		assertFalse("world hops re-trigger sync; a confirmed RSN must not re-push", gate.shouldPush(RSN_A));
	}

	@Test
	public void pushesAgainWhenRsnChanges()
	{
		RsnSyncGate gate = new RsnSyncGate();
		gate.markPushed(RSN_A);
		assertTrue(gate.shouldPush(RSN_B));
	}

	@Test
	public void unconfirmedPushStaysEligibleForRetry()
	{
		RsnSyncGate gate = new RsnSyncGate();
		assertTrue(gate.shouldPush(RSN_A));
		assertTrue("no markPushed happened, so the next transition must retry", gate.shouldPush(RSN_A));
	}

	@Test
	public void resetForcesRepush()
	{
		RsnSyncGate gate = new RsnSyncGate();
		gate.markPushed(RSN_A);
		gate.reset();
		assertTrue(gate.shouldPush(RSN_A));
	}

	@Test
	public void neverPushesNullOrEmpty()
	{
		RsnSyncGate gate = new RsnSyncGate();
		assertFalse(gate.shouldPush(null));
		assertFalse(gate.shouldPush(""));
	}

	@Test
	public void accountSwitchBackAndForthPushesEachSwitch()
	{
		RsnSyncGate gate = new RsnSyncGate();
		gate.markPushed(RSN_A);
		gate.markPushed(RSN_B);
		assertTrue(gate.shouldPush(RSN_A));
	}
}
