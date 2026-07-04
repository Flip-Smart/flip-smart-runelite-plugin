package com.flipsmart;
import com.flipsmart.domain.offer.OfferRecord;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BreakevenRelistDetectionTest
{
	@Test
	public void advancesWhenRelistAtAdvisedPriceWithinTolerance()
	{
		assertTrue(OfferRecord.shouldAdvanceToBreakevenRelist(true, 1048, 1050));
		assertTrue(OfferRecord.shouldAdvanceToBreakevenRelist(true, 1050, 1050));
	}

	@Test
	public void doesNotAdvanceWhenBreakevenNotAccepted()
	{
		assertFalse(OfferRecord.shouldAdvanceToBreakevenRelist(false, 1050, 1050));
	}

	@Test
	public void doesNotAdvanceWhenRelistPriceFarFromAdvised()
	{
		assertFalse(OfferRecord.shouldAdvanceToBreakevenRelist(true, 1200, 1050));
	}

	@Test
	public void doesNotAdvanceWithoutAdvisedPrice()
	{
		assertFalse(OfferRecord.shouldAdvanceToBreakevenRelist(true, 1050, 0));
	}
}
