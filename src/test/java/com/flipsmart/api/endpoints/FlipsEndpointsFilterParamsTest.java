package com.flipsmart.api.endpoints;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The server-side Min Profit / Min Volume query params (#935 Part 3) are appended only
 * when set, and each value lands on the correct key (guards against a profit/volume swap).
 */
public class FlipsEndpointsFilterParamsTest
{
	@Test
	public void appendsBothWhenPositive()
	{
		StringBuilder sb = new StringBuilder();
		FlipsEndpoints.appendFilterParams(sb, 5000, 1000);
		assertEquals("&min_profit=5000&min_volume=1000", sb.toString());
	}

	@Test
	public void omitsZero()
	{
		StringBuilder sb = new StringBuilder();
		FlipsEndpoints.appendFilterParams(sb, 0, 0);
		assertEquals("", sb.toString());
	}

	@Test
	public void appendsOnlyProfitWhenVolumeZero()
	{
		StringBuilder sb = new StringBuilder();
		FlipsEndpoints.appendFilterParams(sb, 5000, 0);
		assertEquals("&min_profit=5000", sb.toString());
	}
}
