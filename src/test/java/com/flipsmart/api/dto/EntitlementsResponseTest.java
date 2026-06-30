package com.flipsmart.api.dto;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the defensive entitlement-parsing behavior extracted from
 * {@code ApiHttpTransport.fetchEntitlementsAsync}. The branches below mirror the
 * exact hand-rolled guards that were in the transport: premium defaults to false
 * unless {@code is_premium} is a JSON primitive that reads truthy; an rsn is
 * blocked only when {@code rsn_entitlement.status == "blocked"}; everything is
 * safe on null/missing values.
 */
public class EntitlementsResponseTest
{
	private final Gson gson = new Gson();

	private EntitlementsResponse parse(String json)
	{
		return EntitlementsResponse.fromJson(gson, json);
	}

	// ---- premium branches ----

	@Test
	public void premiumTrueWhenIsPremiumTrue()
	{
		assertTrue(parse("{\"is_premium\":true}").isPremium());
	}

	@Test
	public void premiumFalseWhenIsPremiumFalse()
	{
		assertFalse(parse("{\"is_premium\":false}").isPremium());
	}

	@Test
	public void premiumFalseWhenIsPremiumMissing()
	{
		assertFalse(parse("{}").isPremium());
	}

	@Test
	public void premiumFalseWhenIsPremiumNonPrimitive()
	{
		// A non-primitive (object) is_premium must default to false WITHOUT throwing,
		// so the rest of the payload (rsn_entitlement) is still evaluated.
		EntitlementsResponse r = parse("{\"is_premium\":{},\"rsn_entitlement\":{\"status\":\"blocked\"}}");
		assertFalse(r.isPremium());
		assertTrue("non-primitive premium must not abort rsn parsing", r.isRsnBlocked());
	}

	@Test
	public void premiumFalseWhenIsPremiumNull()
	{
		assertFalse(parse("{\"is_premium\":null}").isPremium());
	}

	// ---- rsn-entitlement branches ----

	@Test
	public void rsnBlockedWhenStatusBlocked()
	{
		assertTrue(parse("{\"rsn_entitlement\":{\"status\":\"blocked\"}}").isRsnBlocked());
	}

	@Test
	public void rsnNotBlockedWhenStatusOther()
	{
		assertFalse(parse("{\"rsn_entitlement\":{\"status\":\"active\"}}").isRsnBlocked());
	}

	@Test
	public void rsnNotBlockedWhenStatusMissing()
	{
		assertFalse(parse("{\"rsn_entitlement\":{}}").isRsnBlocked());
	}

	@Test
	public void rsnNotBlockedWhenEntitlementNull()
	{
		assertFalse(parse("{\"rsn_entitlement\":null}").isRsnBlocked());
	}

	@Test
	public void rsnNotBlockedWhenEntitlementMissing()
	{
		assertFalse(parse("{}").isRsnBlocked());
	}

	// ---- whole-body safety ----

	@Test
	public void emptyBodyIsSafeBothFalse()
	{
		EntitlementsResponse r = parse("");
		assertFalse(r.isPremium());
		assertFalse(r.isRsnBlocked());
	}

	@Test
	public void nullBodyIsSafeBothFalse()
	{
		EntitlementsResponse r = parse(null);
		assertFalse(r.isPremium());
		assertFalse(r.isRsnBlocked());
	}

	@Test
	public void premiumAndBlockedTogether()
	{
		EntitlementsResponse r = parse("{\"is_premium\":true,\"rsn_entitlement\":{\"status\":\"blocked\"}}");
		assertTrue(r.isPremium());
		assertTrue(r.isRsnBlocked());
	}
}
