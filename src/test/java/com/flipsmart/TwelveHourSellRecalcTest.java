package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The standalone AC5 sell-price recalc must defer to the /flips/adjustment ladder once
 * the ladder has begun stepping a sitting 12h sell down — otherwise the recalc re-anchors
 * the offer back up to the fresh overnight estimate and the two fight (the overlay shows
 * the laddered price while the GE screen shows the recalc'd one).
 */
public class TwelveHourSellRecalcTest
{
	private static final long HOUR = 3_600_000L;

	private static OfferRecord sell(long placedAtMillis)
	{
		return OfferRecord.newOffer(1L, 0, 4151, "Fremennik kilt", false, 3, 100, placedAtMillis);
	}

	@Test
	public void freshSell_ladderDoesNotOwnYet()
	{
		long now = 100 * HOUR;
		assertFalse("a sell listed under 6h ago is still in the rung-0 window",
			FlipSmartPlugin.ladderOwnsSell(sell(now - 5 * HOUR), now));
	}

	@Test
	public void sellOpenPastSixHours_ladderOwns()
	{
		long now = 100 * HOUR;
		assertTrue("past the 6h rung-1 boundary the ladder is stepping the price down",
			FlipSmartPlugin.ladderOwnsSell(sell(now - 7 * HOUR), now));
	}

	@Test
	public void noLiveSell_ladderDoesNotOwn()
	{
		assertFalse("a brand-new listing has no live sell yet — recalc may refresh it",
			FlipSmartPlugin.ladderOwnsSell(null, 100 * HOUR));
	}
}
