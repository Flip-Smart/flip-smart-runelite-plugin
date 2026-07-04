package com.flipsmart.api.dto;

/**
 * Per-channel MOTD payload returned by GET /motd.
 */
public class MotdChannelData
{
	private String message;
	private boolean enabled;
	private String version;
	private String severity;

	public String getMessage() { return message; }
	public boolean isEnabled() { return enabled; }
	public String getVersion() { return version; }
	public String getSeverity() { return severity; }
}
