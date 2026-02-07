package com.flipsmart;

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
	private int item_count;
	private String share_id;
	private boolean is_public;
	private boolean is_active;
	private String created_at;
	private String updated_at;
}
