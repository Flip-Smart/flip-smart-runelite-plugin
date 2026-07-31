package com.flipsmart.trading;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class FillWatermarksTest
{
	@Test
	public void identity_isValueEqual_andSeparatesGenerations()
	{
		OfferIdentity a = OfferIdentity.of(0, 4444, true, 1);
		OfferIdentity b = OfferIdentity.of(0, 4444, true, 1);
		OfferIdentity nextGen = OfferIdentity.of(0, 4444, true, 2);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals("a reused slot holds a different offer", a, nextGen);
	}

	@Test
	public void identity_ignoresDirectionCollisions()
	{
		assertNotEquals("a buy and a sell of one item are different offers",
			OfferIdentity.of(0, 4444, true, 1), OfferIdentity.of(0, 4444, false, 1));
	}
}
