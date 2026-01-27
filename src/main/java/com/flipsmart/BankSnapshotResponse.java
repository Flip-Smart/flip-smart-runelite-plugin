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

	@SerializedName("inventory_value")
	private long inventoryValue;

	@SerializedName("ge_offers_value")
	private long geOffersValue;

	@SerializedName("total_wealth")
	private long totalWealth;

	@SerializedName("item_count")
	private int itemCount;

	@SerializedName("snapshot_time")
	private String snapshotTime;

	private String message;
}
