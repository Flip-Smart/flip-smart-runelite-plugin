package com.flipsmart.api.dto;

/**
 * Response shape for GET /motd.
 */
public class MotdResponse
{
	private MotdChannelData web;
	private MotdChannelData plugin;

	public MotdChannelData getWeb() { return web; }
	public MotdChannelData getPlugin() { return plugin; }
}
