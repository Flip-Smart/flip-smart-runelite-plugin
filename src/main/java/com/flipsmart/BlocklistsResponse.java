package com.flipsmart;

import lombok.Data;
import java.util.List;

/**
 * Response from the /blocklists endpoint containing user's blocklists.
 */
@Data
public class BlocklistsResponse
{
	private List<BlocklistSummary> blocklists;
	private int count;
}
