package com.flipsmart;

public final class OfferPoolClassifier
{
	static final int HIGH_VOL_THRESHOLD = 500_000;
	static final String HIGH_VOL = "high_vol";
	static final String MID_VOL = "mid_vol";

	private OfferPoolClassifier()
	{
	}

	public static String classify(Integer dailyVolume)
	{
		if (dailyVolume != null && dailyVolume >= HIGH_VOL_THRESHOLD)
		{
			return HIGH_VOL;
		}
		return MID_VOL;
	}
}
