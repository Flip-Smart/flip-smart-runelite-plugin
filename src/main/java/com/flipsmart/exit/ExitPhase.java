package com.flipsmart.exit;

public enum ExitPhase
{
	PENDING,           // sell offer awaiting relist
	PENDING_CANCEL,    // buy offer awaiting cancel
	CANCELLED_HOLDING, // buy cancelled, stock held, awaiting resell
	DONE
}
