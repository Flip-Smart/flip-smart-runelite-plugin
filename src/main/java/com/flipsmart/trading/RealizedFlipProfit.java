package com.flipsmart.trading;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.util.GeTax;
import java.util.List;

/**
 * Realized profit on the units of an active flip that have actually sold, computed
 * from locally-tracked GE sell fills. Pure: a function of the supplied records.
 */
public final class RealizedFlipProfit
{
	public static final class Result
	{
		public final int soldQuantity;
		public final long grossProceeds;
		public final long taxTotal;
		public final long netProfit;

		Result(int soldQuantity, long grossProceeds, long taxTotal, long netProfit)
		{
			this.soldQuantity = soldQuantity;
			this.grossProceeds = grossProceeds;
			this.taxTotal = taxTotal;
			this.netProfit = netProfit;
		}
	}

	private RealizedFlipProfit()
	{
	}

	/**
	 * @param itemRecords     all offer records for the item (e.g. OfferStore.forItem)
	 * @param itemId          item id, for the GE tax-exempt list
	 * @param averageBuyPrice player's average buy price for the flip
	 * @param sinceMillis     include only sells last active at/after this epoch-ms
	 *                        (the current flip's first-buy time); 0 includes all
	 */
	public static Result compute(List<OfferRecord> itemRecords, int itemId, int averageBuyPrice, long sinceMillis)
	{
		int soldQuantity = 0;
		long grossProceeds = 0L;
		long taxTotal = 0L;

		if (itemRecords != null)
		{
			for (OfferRecord r : itemRecords)
			{
				if (r == null || r.isBuy() || r.getFilledQuantity() <= 0)
				{
					continue;
				}
				if (r.getEffectiveLastActivityAtMillis() < sinceMillis)
				{
					continue;
				}
				soldQuantity += r.getFilledQuantity();
				grossProceeds += r.getSpent();
				taxTotal += (long) GeTax.taxFor(itemId, r.getPrice()) * r.getFilledQuantity();
			}
		}

		long buyCost = (long) soldQuantity * averageBuyPrice;
		long netProfit = grossProceeds - buyCost - taxTotal;
		return new Result(soldQuantity, grossProceeds, taxTotal, netProfit);
	}
}
