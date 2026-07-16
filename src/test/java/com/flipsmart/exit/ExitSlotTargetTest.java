package com.flipsmart.exit;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExitSlotTargetTest
{
	@Test
	public void sellTargetStartsPending()
	{
		ExitSlotTarget t = ExitSlotTarget.sell(0, 561, "Nature rune", 200);
		assertFalse(t.isBuy());
		assertEquals(ExitPhase.PENDING, t.getPhase());
		assertEquals(200, t.getBuyBasis());
	}

	@Test
	public void buyTargetStartsPendingCancel()
	{
		ExitSlotTarget t = ExitSlotTarget.buy(3, 4151, "Abyssal whip", 1_500_000);
		assertTrue(t.isBuy());
		assertEquals(ExitPhase.PENDING_CANCEL, t.getPhase());
	}

	@Test
	public void phaseIsMutable()
	{
		ExitSlotTarget t = ExitSlotTarget.sell(1, 2, "x", 10);
		t.setPhase(ExitPhase.DONE);
		assertEquals(ExitPhase.DONE, t.getPhase());
	}
}
