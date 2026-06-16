package com.flipsmart.api.dto;

/**
 * Response from polling device authorization status
 */
public class DeviceStatusResponse
{
	private String status;  // pending, authorized, expired
	private String accessToken;
	private String tokenType;
	private String refreshToken;  // For session persistence across client restarts

	/** Default constructor required for Gson deserialization */
	public DeviceStatusResponse() { }

	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }

	public String getAccessToken() { return accessToken; }
	public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

	public String getTokenType() { return tokenType; }
	public void setTokenType(String tokenType) { this.tokenType = tokenType; }

	public String getRefreshToken() { return refreshToken; }
	public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
