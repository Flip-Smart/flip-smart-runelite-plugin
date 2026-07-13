package com.flipsmart.domain.offer;

public enum OfferDisposition
{
	SURFACE_PRICE,
	HANDOFF,
	NONE;

	public static OfferDisposition route(OfferAction action)
	{
		if (action == null)
		{
			return NONE;
		}
		switch (action)
		{
			case MOVE_PRICE_DOWN:
			case MOVE_PRICE_UP:
			case EXIT_AT_BREAKEVEN:
			case EXIT_AT_LOSS:
				return SURFACE_PRICE;
			case CANCEL_AND_RELIST_OTHER:
			case CANCEL_AND_SELL_PARTIAL:
				return HANDOFF;
			case WAIT:
			default:
				return NONE;
		}
	}
}
