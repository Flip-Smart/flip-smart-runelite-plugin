package com.flipsmart.api.dto;

import java.util.List;
import lombok.Data;

@Data
public class OfferAdviceBatchResponse
{
	private List<OfferAdviceResult> results;
}
