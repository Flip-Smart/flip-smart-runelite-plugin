package com.flipsmart.api.dto;

/**
 * Response from starting device authorization
 */
public class DeviceAuthResponse
{
	private String deviceCode;
	private String userCode;
	private String verificationUrl;
	private int expiresIn;
	private int pollInterval;

	/** Default constructor required for Gson deserialization */
	public DeviceAuthResponse() { }

	public String getDeviceCode() { return deviceCode; }
	public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }

	public String getUserCode() { return userCode; }
	public void setUserCode(String userCode) { this.userCode = userCode; }

	public String getVerificationUrl() { return verificationUrl; }
	public void setVerificationUrl(String verificationUrl) { this.verificationUrl = verificationUrl; }

	public int getExpiresIn() { return expiresIn; }
	public void setExpiresIn(int expiresIn) { this.expiresIn = expiresIn; }

	public int getPollInterval() { return pollInterval; }
	public void setPollInterval(int pollInterval) { this.pollInterval = pollInterval; }
}
