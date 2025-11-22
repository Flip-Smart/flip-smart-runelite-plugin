package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

/**
 * Response from the completed flips API endpoint
 */
@Data
public class CompletedFlipsResponse
{
	@SerializedName("flips")
	private List<CompletedFlip> flips;

	@SerializedName("count")
	private int count;
}

