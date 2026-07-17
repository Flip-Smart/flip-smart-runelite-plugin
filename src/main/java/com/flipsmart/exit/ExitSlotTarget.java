package com.flipsmart.exit;

import lombok.Getter;

/** One occupied GE slot the Exit Trades flow must unwind. Phase mutates as the player acts. */
@Getter
public final class ExitSlotTarget
{
	private final int slot;
	private final int itemId;
	private final String itemName;
	private final boolean buy;
	private final int buyBasis;
	private ExitPhase phase;
	private int heldQuantity; // stock bought from a cancelled/filled buy, remembered across the collect lag

	private ExitSlotTarget(int slot, int itemId, String itemName, boolean buy, int buyBasis, ExitPhase phase)
	{
		this.slot = slot;
		this.itemId = itemId;
		this.itemName = itemName;
		this.buy = buy;
		this.buyBasis = buyBasis;
		this.phase = phase;
	}

	public static ExitSlotTarget sell(int slot, int itemId, String itemName, int buyBasis)
	{
		return new ExitSlotTarget(slot, itemId, itemName, false, buyBasis, ExitPhase.PENDING);
	}

	public static ExitSlotTarget buy(int slot, int itemId, String itemName, int buyBasis)
	{
		return new ExitSlotTarget(slot, itemId, itemName, true, buyBasis, ExitPhase.PENDING_CANCEL);
	}

	public void setPhase(ExitPhase phase)
	{
		this.phase = phase;
	}

	public void setHeldQuantity(int heldQuantity)
	{
		this.heldQuantity = heldQuantity;
	}
}
