package com.flipsmart;

import com.flipsmart.FlipAssistOverlay.FlipAssistStep;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Covers {@link FlipAssistOverlay#offerSetupStep} — the step shown on the GE offer-setup
 * screen given the current vs target quantity/price. Quantity must match EXACTLY: the ±1
 * price tolerance previously accepted the default qty 1 when the target was 2, skipping the
 * "Set Quantity" step and jumping straight to Confirm (the user would buy 1 instead of 2).
 */
public class FlipAssistOfferSetupStepTest
{
	// Regression: default qty 1 with target 2 must still prompt SET_QUANTITY, not Confirm.
	@Test
	public void defaultQtyOneWithTargetTwoPromptsSetQuantity()
	{
		assertEquals(FlipAssistStep.SET_QUANTITY,
			FlipAssistOverlay.offerSetupStep(1, 2, 7348074, 7348074, true));
	}

	// Exact qty + exact price → Confirm (buy).
	@Test
	public void exactQtyAndPriceConfirmsBuy()
	{
		assertEquals(FlipAssistStep.CONFIRM_OFFER,
			FlipAssistOverlay.offerSetupStep(2, 2, 7348074, 7348074, true));
	}

	// Exact qty + exact price → Confirm (sell).
	@Test
	public void exactQtyAndPriceConfirmsSell()
	{
		assertEquals(FlipAssistStep.CONFIRM_SELL,
			FlipAssistOverlay.offerSetupStep(2, 2, 21505278, 21505278, false));
	}

	// Price keeps ±1 tolerance (1gp rounding) once qty is exact.
	@Test
	public void priceWithinOneGpStillConfirms()
	{
		assertEquals(FlipAssistStep.CONFIRM_OFFER,
			FlipAssistOverlay.offerSetupStep(2, 2, 7348073, 7348074, true));
	}

	// Correct qty but wrong price → SET_PRICE (buy).
	@Test
	public void correctQtyWrongPricePromptsSetPrice()
	{
		assertEquals(FlipAssistStep.SET_PRICE,
			FlipAssistOverlay.offerSetupStep(2, 2, 7000000, 7348074, true));
	}

	// Target qty 1 with default 1 → no quantity entry needed, advance to price/confirm.
	@Test
	public void targetQtyOneDoesNotForceQuantityStep()
	{
		assertEquals(FlipAssistStep.CONFIRM_OFFER,
			FlipAssistOverlay.offerSetupStep(1, 1, 7348074, 7348074, true));
	}
}
