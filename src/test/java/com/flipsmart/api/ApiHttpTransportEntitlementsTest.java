package com.flipsmart.api;

import com.flipsmart.FlipSmartConfig;
import com.flipsmart.api.dto.EntitlementsResponse;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the entitlement-application contract (#1087): an entitlements snapshot
 * only updates RSN-blocked status. Premium is sourced exclusively from the
 * flip-finder payload; applying entitlements must never change the premium
 * flag, because the snapshot nests premium under {@code rsn_entitlement} and
 * re-sourcing it here previously downgraded premium players to the free tier.
 */
public class ApiHttpTransportEntitlementsTest
{
	private final Gson gson = new Gson();
	private ApiHttpTransport transport;

	@Before
	public void setUp()
	{
		transport = new ApiHttpTransport(
			mock(OkHttpClient.class), gson, mock(FlipSmartConfig.class));
	}

	private EntitlementsResponse parse(String json)
	{
		return EntitlementsResponse.fromJson(gson, json);
	}

	@Test
	public void applyingEntitlementsDoesNotDowngradePremium()
	{
		transport.setPremium(true);  // as set by the flip-finder payload / login token
		// Real snapshot shape: premium nested under rsn_entitlement, no top-level flag.
		transport.applyEntitlements(parse(
			"{\"has_any_premium\":true,\"rsn_entitlement\":{\"is_premium\":true,\"status\":\"active\"}}"));
		assertTrue("entitlements must not clobber premium", transport.isPremium());
	}

	@Test
	public void applyingEntitlementsDoesNotPromoteFreeToPremium()
	{
		transport.setPremium(false);
		transport.applyEntitlements(parse("{\"is_premium\":true}"));
		assertFalse("entitlements is not a premium source", transport.isPremium());
	}

	@Test
	public void applyingEntitlementsUpdatesRsnBlocked()
	{
		transport.applyEntitlements(parse("{\"rsn_entitlement\":{\"status\":\"blocked\"}}"));
		assertTrue(transport.isRsnBlocked());
	}

	@Test
	public void applyingNonBlockedEntitlementsClearsRsnBlocked()
	{
		transport.applyEntitlements(parse("{\"rsn_entitlement\":{\"status\":\"blocked\"}}"));
		transport.applyEntitlements(parse("{\"rsn_entitlement\":{\"status\":\"active\"}}"));
		assertFalse(transport.isRsnBlocked());
	}
}
