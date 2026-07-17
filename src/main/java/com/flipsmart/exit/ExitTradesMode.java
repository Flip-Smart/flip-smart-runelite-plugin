package com.flipsmart.exit;

public enum ExitTradesMode
{
	BREAKEVEN,
	INSTANT,
	REGULAR // sell-only: suppress buys but leave the normal sell flow and its prices untouched
}
