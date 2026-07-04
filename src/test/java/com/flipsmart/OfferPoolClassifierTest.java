package com.flipsmart;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class OfferPoolClassifierTest
{
	@Test
	public void atOrAboveThresholdIsHighVol()
	{
		assertEquals("high_vol", OfferPoolClassifier.classify(500_000));
		assertEquals("high_vol", OfferPoolClassifier.classify(1_000_000));
	}

	@Test
	public void belowThresholdIsMidVol()
	{
		assertEquals("mid_vol", OfferPoolClassifier.classify(499_999));
		assertEquals("mid_vol", OfferPoolClassifier.classify(0));
	}

	@Test
	public void unknownVolumeFallsBackToMidVol()
	{
		assertEquals("mid_vol", OfferPoolClassifier.classify(null));
	}
}
