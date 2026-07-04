package com.flipsmart.api.dto;

/**
 * Response wrapper for dumps API
 */
public class DumpsResponse
{
	public DumpEvent[] dumps;
	public int count;
	public String sort_by;
}
