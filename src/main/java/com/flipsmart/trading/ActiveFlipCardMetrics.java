package com.flipsmart.trading;

import com.flipsmart.util.GeTax;

/**
 * Market-based metrics for an active-flip card row (margin, ROI, profit potential,
 * total tax, break-even position). Pure: a function of the supplied prices and
 * quantities, independent of Swing state.
 */
public final class ActiveFlipCardMetrics
{
	public static final class Result
	{
		public final int margin;
		public final double roi;
		public final long profitPotential;
		public final long totalTax;
		public final int positionNetPerUnit;

		Result(int margin, double roi, long profitPotential, long totalTax, int positionNetPerUnit)
		{
			this.margin = margin;
			this.roi = roi;
			this.profitPotential = profitPotential;
			this.totalTax = totalTax;
			this.positionNetPerUnit = positionNetPerUnit;
		}
	}

	private ActiveFlipCardMetrics()
	{
	}

	/**
	 * @param low             current market low (instant-buy target)
	 * @param high            current market high (instant-sell target)
	 * @param itemId          item id, for the GE tax-exempt list
	 * @param averageBuyPrice player's average buy price for the flip
	 * @param realizedSoldQuantity units already sold and realized this flip
	 * @param remainingQuantity    units still held (flip.getTotalQuantity())
	 */
	public static Result compute(int low, int high, int itemId, int averageBuyPrice,
		int realizedSoldQuantity, int remainingQuantity)
	{
		int margin = high - low;
		double roi = low > 0 ? (margin * 100.0) / low : 0.0;
		long fullQuantity = (long) realizedSoldQuantity + remainingQuantity;
		long profitPotential = (long) margin * fullQuantity;
		int perItemTax = GeTax.taxFor(itemId, high);
		long totalTax = (long) perItemTax * fullQuantity;
		int positionNetPerUnit = high - averageBuyPrice - perItemTax;

		return new Result(margin, roi, profitPotential, totalTax, positionNetPerUnit);
	}
}
