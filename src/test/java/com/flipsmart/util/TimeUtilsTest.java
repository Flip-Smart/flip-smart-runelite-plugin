package com.flipsmart.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The API emits Python {@code isoformat()} timestamps ("+00:00" offset, optional
 * microseconds). {@code Instant.parse} only accepts offsets from JDK 12, so on a
 * Java 11 RuneLite client these must still parse — the plugin targets release 11.
 */
public class TimeUtilsTest
{
	private static final long T = 1784721600000L; // 2026-07-22T12:00:00Z

	@Test
	public void parsesBackendOffsetWithMicroseconds()
	{
		assertEquals(T + 123, TimeUtils.parseIsoToMillis("2026-07-22T12:00:00.123456+00:00"));
	}

	@Test
	public void parsesBackendOffsetWithoutFraction()
	{
		assertEquals(T, TimeUtils.parseIsoToMillis("2026-07-22T12:00:00+00:00"));
	}

	@Test
	public void parsesZuluSuffix()
	{
		assertEquals(T + 123, TimeUtils.parseIsoToMillis("2026-07-22T12:00:00.123456Z"));
	}

	@Test
	public void appliesNonUtcOffset()
	{
		assertEquals(T, TimeUtils.parseIsoToMillis("2026-07-22T08:00:00-04:00"));
	}

	@Test
	public void returnsZeroForNullEmptyOrGarbage()
	{
		assertEquals(0L, TimeUtils.parseIsoToMillis(null));
		assertEquals(0L, TimeUtils.parseIsoToMillis(""));
		assertEquals(0L, TimeUtils.parseIsoToMillis("not-a-time"));
	}
}
