package com.flipsmart.api.endpoints;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The inventory_gp param feeding the web "Capital Active" card. Zero is a real
 * reading and must survive to the wire — the neighbouring filter params omit zero,
 * so the difference is deliberate and easy to "tidy" away by mistake.
 */
public class FlipsEndpointsInventoryGpTest
{
	@Test
	public void appendsWhenPresent()
	{
		StringBuilder sb = new StringBuilder();
		FlipsEndpoints.appendInventoryGp(sb, 50_000_000);
		assertEquals("&inventory_gp=50000000", sb.toString());
	}

	@Test
	public void sendsZeroRatherThanOmittingIt()
	{
		StringBuilder sb = new StringBuilder();
		FlipsEndpoints.appendInventoryGp(sb, 0);
		assertEquals("&inventory_gp=0", sb.toString());
	}

	@Test
	public void omitsWhenUnknown()
	{
		StringBuilder sb = new StringBuilder();
		FlipsEndpoints.appendInventoryGp(sb, null);
		assertEquals("", sb.toString());
	}
}
