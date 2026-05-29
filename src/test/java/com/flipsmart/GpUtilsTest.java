package com.flipsmart;

import org.junit.Test;

import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpUtilsTest
{
	@Test
	public void parsesRawInteger()
	{
		assertEquals(OptionalInt.of(5_000_000), GpUtils.parseGp("5000000"));
	}

	@Test
	public void parsesThousandsShorthandLowercase()
	{
		assertEquals(OptionalInt.of(500_000), GpUtils.parseGp("500k"));
	}

	@Test
	public void parsesThousandsShorthandUppercase()
	{
		assertEquals(OptionalInt.of(500_000), GpUtils.parseGp("500K"));
	}

	@Test
	public void parsesMillionsCaseInsensitive()
	{
		assertEquals(GpUtils.parseGp("10m"), GpUtils.parseGp("10M"));
		assertEquals(OptionalInt.of(10_000_000), GpUtils.parseGp("10M"));
	}

	@Test
	public void parsesDecimalMillions()
	{
		assertEquals(OptionalInt.of(2_500_000), GpUtils.parseGp("2.5m"));
	}

	@Test
	public void parsesDecimalThousands()
	{
		assertEquals(OptionalInt.of(1_500), GpUtils.parseGp("1.5k"));
	}

	@Test
	public void parsesBillionsShorthand()
	{
		assertEquals(OptionalInt.of(1_000_000_000), GpUtils.parseGp("1b"));
	}

	@Test
	public void stripsCommaSeparators()
	{
		assertEquals(OptionalInt.of(10_000_000), GpUtils.parseGp("10,000,000"));
	}

	@Test
	public void toleratesGpSuffixAndWhitespace()
	{
		assertEquals(OptionalInt.of(5_000_000), GpUtils.parseGp("  5m gp "));
	}

	@Test
	public void clampsToIntegerMaxValue()
	{
		// 2.5b exceeds the max coins a single stack can hold; clamp to Integer.MAX_VALUE.
		assertEquals(OptionalInt.of(Integer.MAX_VALUE), GpUtils.parseGp("2.5b"));
	}

	@Test
	public void rejectsBlankInput()
	{
		assertFalse(GpUtils.parseGp("").isPresent());
		assertFalse(GpUtils.parseGp("   ").isPresent());
		assertFalse(GpUtils.parseGp(null).isPresent());
	}

	@Test
	public void rejectsNonNumericInput()
	{
		assertFalse(GpUtils.parseGp("abc").isPresent());
		assertFalse(GpUtils.parseGp("5x").isPresent());
		assertFalse(GpUtils.parseGp("m").isPresent());
	}

	@Test
	public void rejectsZeroAndNegative()
	{
		assertFalse(GpUtils.parseGp("0").isPresent());
		assertFalse(GpUtils.parseGp("-5m").isPresent());
	}

	@Test
	public void roundsFractionalResultDown()
	{
		// 1.2345k = 1234.5 -> floor to 1234
		assertTrue(GpUtils.parseGp("1.2345k").isPresent());
		assertEquals(1234, GpUtils.parseGp("1.2345k").getAsInt());
	}
}
