package com.flipsmart.api.dto;

/**
 * Authentication result with status and message
 */
public class AuthResult
{
	public final boolean success;
	public final String message;

	public AuthResult(boolean success, String message)
	{
		this.success = success;
		this.message = message;
	}
}
