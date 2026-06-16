package com.flipsmart;
import com.flipsmart.util.GeTax;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeTaxTest
{
	private static final int ABYSSAL_WHIP_ID = 4151;       // not exempt
	private static final int OLD_SCHOOL_BOND_ID = 13190;   // exempt
	private static final int COOKED_LOBSTER_ID = 379;      // exempt

	@Test
	public void exemptWhenSellPriceIsFiftyOrLess()
	{
		assertTrue(GeTax.isExempt(ABYSSAL_WHIP_ID, 50));
		assertTrue(GeTax.isExempt(ABYSSAL_WHIP_ID, 49));
		assertTrue(GeTax.isExempt(ABYSSAL_WHIP_ID, 1));
	}

	@Test
	public void exemptWhenItemIsOnList()
	{
		assertTrue(GeTax.isExempt(OLD_SCHOOL_BOND_ID, 8_000_000));
		assertTrue(GeTax.isExempt(COOKED_LOBSTER_ID, 250));
	}

	@Test
	public void notExemptForArbitraryItemAboveThreshold()
	{
		assertFalse(GeTax.isExempt(ABYSSAL_WHIP_ID, 51));
	}

	@Test
	public void taxIsZeroForExempt()
	{
		assertEquals(0, GeTax.taxFor(OLD_SCHOOL_BOND_ID, 8_000_000));
		assertEquals(0, GeTax.taxFor(ABYSSAL_WHIP_ID, 50));
	}

	@Test
	public void taxFloorsTwoPercent()
	{
		assertEquals(1, GeTax.taxFor(ABYSSAL_WHIP_ID, 99));      // floor(1.98) = 1
		assertEquals(30_000, GeTax.taxFor(ABYSSAL_WHIP_ID, 1_500_000));
		assertEquals(582_000, GeTax.taxFor(ABYSSAL_WHIP_ID, 29_100_000));
	}

	@Test
	public void taxCapsAtFiveMillion()
	{
		assertEquals(5_000_000, GeTax.taxFor(ABYSSAL_WHIP_ID, 500_000_000));
		assertEquals(5_000_000, GeTax.taxFor(ABYSSAL_WHIP_ID, Integer.MAX_VALUE));
	}
}
