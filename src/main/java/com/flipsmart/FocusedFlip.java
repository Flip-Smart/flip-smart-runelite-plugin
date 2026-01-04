package com.flipsmart;

import lombok.Data;

/**
 * Represents a flip that the user has selected as their current focus.
 * This can be either a recommended flip (buying step) or an active flip (selling step).
 */
@Data
public class FocusedFlip
{
	/**
	 * The step of the flip process
	 */
	public enum FlipStep
	{
		BUY,  // Need to buy the item
		SELL  // Already bought, need to sell
	}

	private final int itemId;
	private final String itemName;
	private final FlipStep step;
	
	// Buy step fields
	private final int buyPrice;
	private final int buyQuantity;
	
	// Sell step fields
	private final int sellPrice;
	private final int sellQuantity;
	
	/**
	 * Create a FocusedFlip for the BUY step (from a recommendation)
	 */
	public static FocusedFlip forBuy(int itemId, String itemName, int buyPrice, int buyQuantity, int sellPrice)
	{
		return forBuy(itemId, itemName, buyPrice, buyQuantity, sellPrice, 0);
	}
	
	/**
	 * Create a FocusedFlip for the BUY step with price offset applied
	 * @param priceOffset Positive offset = buy higher to fill faster
	 */
	public static FocusedFlip forBuy(int itemId, String itemName, int buyPrice, int buyQuantity, int sellPrice, int priceOffset)
	{
		// Apply offset: add to buy price (pay more to fill faster)
		int adjustedBuyPrice = Math.max(1, buyPrice + priceOffset);
		// Also adjust sell price by subtracting offset (sell lower to fill faster)
		int adjustedSellPrice = Math.max(1, sellPrice - priceOffset);
		
		return new FocusedFlip(
			itemId,
			itemName,
			FlipStep.BUY,
			adjustedBuyPrice,
			buyQuantity,
			adjustedSellPrice,
			0  // No sell quantity yet
		);
	}
	
	/**
	 * Create a FocusedFlip for the SELL step (from an active flip)
	 */
	public static FocusedFlip forSell(int itemId, String itemName, int sellPrice, int sellQuantity)
	{
		return forSell(itemId, itemName, sellPrice, sellQuantity, 0);
	}
	
	/**
	 * Create a FocusedFlip for the SELL step with price offset applied
	 * @param priceOffset Positive offset = sell lower to fill faster
	 */
	public static FocusedFlip forSell(int itemId, String itemName, int sellPrice, int sellQuantity, int priceOffset)
	{
		// Apply offset: subtract from sell price (sell lower to fill faster)
		int adjustedSellPrice = Math.max(1, sellPrice - priceOffset);
		
		return new FocusedFlip(
			itemId,
			itemName,
			FlipStep.SELL,
			0,  // Not relevant for selling
			0,
			adjustedSellPrice,
			sellQuantity
		);
	}
	
	/**
	 * Get the price relevant to the current step
	 */
	public int getCurrentStepPrice()
	{
		return step == FlipStep.BUY ? buyPrice : sellPrice;
	}
	
	/**
	 * Get the quantity relevant to the current step
	 */
	public int getCurrentStepQuantity()
	{
		return step == FlipStep.BUY ? buyQuantity : sellQuantity;
	}
	
	/**
	 * Check if we're in the buying step
	 */
	public boolean isBuying()
	{
		return step == FlipStep.BUY;
	}
	
	/**
	 * Check if we're in the selling step
	 */
	public boolean isSelling()
	{
		return step == FlipStep.SELL;
	}
}

