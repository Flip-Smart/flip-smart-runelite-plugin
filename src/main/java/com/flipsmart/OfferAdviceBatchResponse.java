package com.flipsmart;

import java.util.List;
import lombok.Data;

@Data
public class OfferAdviceBatchResponse
{
	private List<OfferAdviceResult> results;
}
