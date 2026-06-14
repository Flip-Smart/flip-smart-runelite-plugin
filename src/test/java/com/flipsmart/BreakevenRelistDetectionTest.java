package com.flipsmart;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BreakevenRelistDetectionTest
{
	@Test
	public void advancesWhenRelistAtAdvisedPriceWithinTolerance()
	{
		assertTrue(TrackedOffer.shouldAdvanceToBreakevenRelist(true, 1048, 1050));
		assertTrue(TrackedOffer.shouldAdvanceToBreakevenRelist(true, 1050, 1050));
	}

	@Test
	public void doesNotAdvanceWhenBreakevenNotAccepted()
	{
		assertFalse(TrackedOffer.shouldAdvanceToBreakevenRelist(false, 1050, 1050));
	}

	@Test
	public void doesNotAdvanceWhenRelistPriceFarFromAdvised()
	{
		assertFalse(TrackedOffer.shouldAdvanceToBreakevenRelist(true, 1200, 1050));
	}

	@Test
	public void doesNotAdvanceWithoutAdvisedPrice()
	{
		assertFalse(TrackedOffer.shouldAdvanceToBreakevenRelist(true, 1050, 0));
	}
}
