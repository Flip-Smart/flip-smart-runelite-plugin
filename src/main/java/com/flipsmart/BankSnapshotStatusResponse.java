package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Response from checking bank snapshot rate limit status
 */
@Data
public class BankSnapshotStatusResponse
{
	@SerializedName("can_snapshot")
	private boolean canSnapshot;

	@SerializedName("next_snapshot_available")
	private String nextSnapshotAvailable;

	@SerializedName("hours_until_available")
	private Double hoursUntilAvailable;

	private String message;
}
