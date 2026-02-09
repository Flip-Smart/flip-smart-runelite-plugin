package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Summary of a blocklist for display in the plugin dropdown.
 */
@Data
public class BlocklistSummary
{
	private int id;
	private String name;
	private String description;
	@SerializedName("item_count")
	private int itemCount;
	@SerializedName("share_id")
	private String shareId;
	@SerializedName("is_public")
	private boolean isPublic;
	@SerializedName("is_active")
	private boolean isActive;
	@SerializedName("created_at")
	private String createdAt;
	@SerializedName("updated_at")
	private String updatedAt;
}
