package com.flipsmart.api.dto;

/**
 * Outcome of a bank snapshot upload. The server answers HTTP 429 when a
 * snapshot was already taken inside its 24h window; that is an expected
 * outcome rather than a failure, so callers can back off without surfacing
 * an error to the player.
 */
public final class BankSnapshotResult
{
	private final BankSnapshotResponse response;
	private final boolean rateLimited;

	private BankSnapshotResult(BankSnapshotResponse response, boolean rateLimited)
	{
		this.response = response;
		this.rateLimited = rateLimited;
	}

	public static BankSnapshotResult success(BankSnapshotResponse response)
	{
		return new BankSnapshotResult(response, false);
	}

	public static BankSnapshotResult rateLimited()
	{
		return new BankSnapshotResult(null, true);
	}

	public static BankSnapshotResult failure()
	{
		return new BankSnapshotResult(null, false);
	}

	public boolean isSuccess()
	{
		return response != null;
	}

	public boolean isRateLimited()
	{
		return rateLimited;
	}

	public BankSnapshotResponse getResponse()
	{
		return response;
	}
}
