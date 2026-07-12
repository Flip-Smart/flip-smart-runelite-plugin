package com.flipsmart.trading;

import static org.junit.Assert.assertEquals;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class RealizedFlipProfitTest
{
	private static final int ITEM_ID = 12934; // Zulrah's scales (not tax-exempt)

	private static OfferRecord sell(int qty, int price, long spent, long activityMs)
	{
		// newOffer(offerId, slot, itemId, itemName, buy=false, totalQuantity, price, now)
		return OfferRecord.newOffer(1L, 0, ITEM_ID, "Zulrah's scales", false, qty, price, activityMs)
			.withFill(qty, spent, OfferState.FILLED, activityMs);
	}

	private static OfferRecord buy(int qty, int price, long spent, long activityMs)
	{
		return OfferRecord.newOffer(2L, 1, ITEM_ID, "Zulrah's scales", true, qty, price, activityMs)
			.withFill(qty, spent, OfferState.FILLED, activityMs);
	}

	@Test
	public void noSellsYieldsZero()
	{
		RealizedFlipProfit.Result r = RealizedFlipProfit.compute(Collections.emptyList(), ITEM_ID, 173, 0L);
		assertEquals(0, r.soldQuantity);
		assertEquals(0L, r.grossProceeds);
		assertEquals(0L, r.netProfit);
	}

	@Test
	public void buyRecordsAreIgnored()
	{
		List<OfferRecord> recs = Collections.singletonList(buy(100, 173, 17_300L, 1000L));
		RealizedFlipProfit.Result r = RealizedFlipProfit.compute(recs, ITEM_ID, 173, 0L);
		assertEquals(0, r.soldQuantity);
		assertEquals(0L, r.netProfit);
	}

	@Test
	public void realizedNetIsProceedsMinusBuyCostMinusTax()
	{
		// sell 1000 @ 179 => proceeds 179,000; tax = taxFor(179)*1000; buyCost = 1000*173
		OfferRecord s = sell(1000, 179, 179_000L, 5000L);
		RealizedFlipProfit.Result r = RealizedFlipProfit.compute(Collections.singletonList(s), ITEM_ID, 173, 0L);
		long expectedTax = (long) com.flipsmart.util.GeTax.taxFor(ITEM_ID, 179) * 1000;
		assertEquals(1000, r.soldQuantity);
		assertEquals(179_000L, r.grossProceeds);
		assertEquals(expectedTax, r.taxTotal);
		assertEquals(179_000L - (1000L * 173) - expectedTax, r.netProfit);
	}

	@Test
	public void sellsBeforeCutoffAreExcluded()
	{
		OfferRecord older = sell(500, 179, 89_500L, 1000L);
		OfferRecord newer = sell(500, 179, 89_500L, 9000L);
		RealizedFlipProfit.Result r = RealizedFlipProfit.compute(Arrays.asList(older, newer), ITEM_ID, 173, 5000L);
		assertEquals(500, r.soldQuantity); // only the newer sell counts
		assertEquals(89_500L, r.grossProceeds);
	}
}
