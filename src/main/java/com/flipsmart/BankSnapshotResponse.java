package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Response from creating a bank snapshot
 */
@Data
public class BankSnapshotResponse
{
	private int id;

	private String rsn;

	@SerializedName("total_value")
	private long totalValue;

	@SerializedName("item_count")
	private int itemCount;

	@SerializedName("snapshot_time")
	private String snapshotTime;

	private String message;
}
