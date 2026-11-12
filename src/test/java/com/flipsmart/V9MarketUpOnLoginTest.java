package com.flipsmart;

import com.flipsmart.v9.V9FlipState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class V9MarketUpOnLoginTest
{
	@Test
	public void marketWellAboveListingIsMarketUp()
	{
		assertTrue(FlipSmartPlugin.v9MarketRose(10_000, 10_500));
	}

	@Test
	public void flatOrFallenMarketIsNotMarketUp()
	{
		assertFalse(FlipSmartPlugin.v9MarketRose(10_000, 10_000));
		assertFalse(FlipSmartPlugin.v9MarketRose(10_000, 9_500));
	}

	@Test
	public void deliberateUndercutIsNotMarketUp()
	{
		assertFalse(FlipSmartPlugin.v9MarketRose(10_000, 10_005));
		assertTrue(FlipSmartPlugin.v9MarketRose(10_000, 10_006));
	}

	@Test
	public void cheapItemsUseTheTighterUndercut()
	{
		assertFalse(FlipSmartPlugin.v9MarketRose(500, 502));
		assertTrue(FlipSmartPlugin.v9MarketRose(500, 503));
	}

	@Test
	public void marketUpPromptsOnlyWhenTheNewPriceIsHigher()
	{
		assertTrue(FlipSmartPlugin.v9Priced("relist", 10_400L, true, 10_000L));
		assertFalse(FlipSmartPlugin.v9Priced("relist", 10_000L, true, 10_000L));
		assertFalse(FlipSmartPlugin.v9Priced("relist", 9_800L, true, 10_000L));
		assertFalse(FlipSmartPlugin.v9Priced("relist", 10_400L, true, null));
	}

	@Test
	public void ladderPromptsAreNotHeldToTheHigherPriceRule()
	{
		assertTrue(FlipSmartPlugin.v9Priced("relist", 9_800L, false, 10_000L));
		assertTrue(FlipSmartPlugin.v9Priced("prompt_sell", 9_800L, false, null));
	}

	@Test
	public void unpricedResponsesNeverPrompt()
	{
		assertFalse(FlipSmartPlugin.v9Priced("bypass_to_ladder2", null, false, 10_000L));
		assertFalse(FlipSmartPlugin.v9Priced("relist", 0L, false, 10_000L));
		assertFalse(FlipSmartPlugin.v9Priced("relist", null, true, 10_000L));
	}

	@Test
	public void marketUpPromptLeavesTheLadderUntouched()
	{
		V9FlipState state = new V9FlipState();
		state.setLadderRung(0);
		state.setLadder1ResolvedAtMs(0L);

		assertFalse(FlipSmartPlugin.v9ResolveRung(state, 1, true, 123_456L));
		assertEquals(0, state.getLadderRung());
		assertEquals(0L, state.getLadder1ResolvedAtMs());
	}

	@Test
	public void marketUpPromptOnLadder1LeavesLadder2TimerAlone()
	{
		V9FlipState state = new V9FlipState();
		state.setLadderRung(1);
		state.setLadder1ResolvedAtMs(1_000L);

		assertFalse(FlipSmartPlugin.v9ResolveRung(state, 1, true, 999_999L));
		assertEquals(1, state.getLadderRung());
		assertEquals(1_000L, state.getLadder1ResolvedAtMs());
	}

	@Test
	public void ladderRungStillAdvancesAndStampsLadder1()
	{
		V9FlipState state = new V9FlipState();

		assertTrue(FlipSmartPlugin.v9ResolveRung(state, 1, false, 123_456L));
		assertEquals(1, state.getLadderRung());
		assertEquals(123_456L, state.getLadder1ResolvedAtMs());
	}

	@Test
	public void ladder2AdvancesWithoutRestampingLadder1()
	{
		V9FlipState state = new V9FlipState();
		state.setLadderRung(1);
		state.setLadder1ResolvedAtMs(1_000L);

		assertTrue(FlipSmartPlugin.v9ResolveRung(state, 2, false, 999_999L));
		assertEquals(2, state.getLadderRung());
		assertEquals(1_000L, state.getLadder1ResolvedAtMs());
	}
}
