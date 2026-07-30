package com.flipsmart.session;

import static org.junit.Assert.assertEquals;

import com.flipsmart.domain.flip.ActiveFlip;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class OpenPositionsTest
{
	private static ActiveFlip projected(int itemId, Integer recSell, int avgBuy, int qty)
	{
		ActiveFlip a = new ActiveFlip();
		a.setItemId(itemId);
		a.setRecommendedSellPrice(recSell);
		a.setAverageBuyPrice(avgBuy);
		a.setTotalQuantity(qty);
		return a;
	}

	@Test
	public void partiallyFilledSell_leavesOnlyTheUnsoldRemainder()
	{
		// The projection reports the sell offer's original quantity (1000). 400 have sold and
		// are already counted in realised profit, so only 600 may be valued as unrealised —
		// otherwise those 400 are counted twice in projected profit.
		List<OpenPosition> open = OpenPositions.derive(
			Collections.singletonList(projected(4151, 200, 100, 1000)), itemId -> 400);

		assertEquals(1, open.size());
		assertEquals(600, open.get(0).unsoldQuantity);
	}

	@Test
	public void awaitingSaleLot_withNoLiveSell_keepsItsFullQuantity()
	{
		List<OpenPosition> open = OpenPositions.derive(
			Collections.singletonList(projected(4151, 200, 100, 25)), itemId -> 0);

		assertEquals(25, open.get(0).unsoldQuantity);
	}

	@Test
	public void fullyFilledSell_leavesNothingUnsold()
	{
		List<OpenPosition> open = OpenPositions.derive(
			Collections.singletonList(projected(4151, 200, 100, 500)), itemId -> 500);

		assertEquals(0, open.get(0).unsoldQuantity);
	}

	@Test
	public void filledExceedingProjectedQuantity_clampsAtZero()
	{
		// Consolidation or a re-list can report more filled than the projection's snapshot
		// quantity; a negative remainder would credit phantom profit.
		List<OpenPosition> open = OpenPositions.derive(
			Collections.singletonList(projected(4151, 200, 100, 100)), itemId -> 250);

		assertEquals(0, open.get(0).unsoldQuantity);
	}

	@Test
	public void carriesPricingBasisThrough()
	{
		OpenPosition p = OpenPositions.derive(
			Collections.singletonList(projected(4151, 200, 100, 10)), itemId -> 0).get(0);

		assertEquals(4151, p.itemId);
		assertEquals(Integer.valueOf(200), p.recommendedSellPrice);
		assertEquals(100, p.averageBuyPrice);
	}

	@Test
	public void filledQuantityIsLookedUpPerItem()
	{
		List<OpenPosition> open = OpenPositions.derive(
			Arrays.asList(projected(4151, 200, 100, 1000), projected(561, 200, 100, 1000)),
			itemId -> itemId == 4151 ? 900 : 0);

		assertEquals(100, open.get(0).unsoldQuantity);
		assertEquals(1000, open.get(1).unsoldQuantity);
	}

	@Test
	public void doesNotMutateTheProjectedFlips()
	{
		// The same ActiveFlip instances render the Active Flips cards and feed the backend
		// snapshot push — deriving positions must not rewrite their quantities.
		ActiveFlip flip = projected(4151, 200, 100, 1000);

		OpenPositions.derive(Collections.singletonList(flip), itemId -> 400);

		assertEquals(1000, flip.getTotalQuantity());
	}
}
