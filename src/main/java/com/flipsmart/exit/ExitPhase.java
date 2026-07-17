package com.flipsmart.exit;

public enum ExitPhase
{
	PENDING,           // sell offer awaiting relist
	PENDING_CANCEL,    // active buy offer awaiting cancel
	AWAITING_COLLECT,  // buy cancelled/filled, bought items still in the GE slot awaiting collection
	CANCELLED_HOLDING, // bought stock collected into inventory, awaiting resell
	DONE
}
