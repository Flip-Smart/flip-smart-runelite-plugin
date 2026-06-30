package com.flipsmart.api.dto;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

/**
 * Parsed {@code GET /auth/entitlements} response. Replaces the hand-rolled
 * {@code JsonObject} walk that previously lived in the transport.
 *
 * <p>Premium is held as a raw {@link JsonElement} rather than a {@code Boolean}
 * so a non-primitive {@code is_premium} (object/array) defaults to false instead
 * of throwing during deserialization, preserving the original defensive parse.
 */
public class EntitlementsResponse
{
	private static final String STATUS_BLOCKED = "blocked";

	@SerializedName("is_premium")
	private JsonElement isPremium;

	@SerializedName("rsn_entitlement")
	private RsnEntitlement rsnEntitlement;

	public static EntitlementsResponse fromJson(Gson gson, String body)
	{
		EntitlementsResponse parsed = gson.fromJson(body, EntitlementsResponse.class);
		return parsed != null ? parsed : new EntitlementsResponse();
	}

	public boolean isPremium()
	{
		return isPremium != null && isPremium.isJsonPrimitive() && isPremium.getAsBoolean();
	}

	public boolean isRsnBlocked()
	{
		return rsnEntitlement != null && STATUS_BLOCKED.equals(rsnEntitlement.status);
	}

	private static class RsnEntitlement
	{
		@SerializedName("status")
		private String status;
	}
}
