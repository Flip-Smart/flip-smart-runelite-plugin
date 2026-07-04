package com.flipsmart.domain.offer;

public enum OfferAction
{
	WAIT("wait"),
	CANCEL_AND_RELIST_OTHER("cancel_and_relist_other"),
	CANCEL_AND_SELL_PARTIAL("cancel_and_sell_partial"),
	MOVE_PRICE_DOWN("move_price_down"),
	EXIT_AT_BREAKEVEN("exit_at_breakeven"),
	EXIT_AT_LOSS("exit_at_loss");

	private final String wire;

	OfferAction(String wire)
	{
		this.wire = wire;
	}

	public String getWire()
	{
		return wire;
	}

	public static OfferAction fromWire(String value)
	{
		if (value == null)
		{
			return null;
		}
		for (OfferAction action : values())
		{
			if (action.wire.equals(value))
			{
				return action;
			}
		}
		return null;
	}
}
