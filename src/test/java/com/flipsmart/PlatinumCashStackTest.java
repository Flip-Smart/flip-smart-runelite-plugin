package com.flipsmart;

import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PlatinumCashStackTest
{
	private static ItemContainer container(Item... items)
	{
		ItemContainer c = mock(ItemContainer.class);
		when(c.getItems()).thenReturn(items);
		return c;
	}

	@Test
	public void gpInCountsCoinsAndPlatinumBeyondMaxCash()
	{
		ItemContainer inv = container(
			new Item(995, Integer.MAX_VALUE),
			new Item(13204, Integer.MAX_VALUE),
			new Item(4151, 1));

		assertEquals(Integer.MAX_VALUE * 1_001L, FlipSmartPlugin.gpIn(inv));
	}

	@Test
	public void gpInIgnoresEmptySlotsAndOtherItems()
	{
		assertEquals(0L, FlipSmartPlugin.gpIn(container(new Item(-1, 0), new Item(560, 5_000))));
	}

	@Test
	public void coinsOnlyInventoryMatchesPreviousCashStack()
	{
		assertEquals(25_000_000L, FlipSmartPlugin.gpIn(container(new Item(995, 25_000_000), new Item(4151, 1))));
	}

	@Test
	public void spendableGpIsInventoryOnlyUntilBankSeen()
	{
		PlayerSession session = new PlayerSession();
		session.setCashStack(1_000_000);
		assertEquals(1_000_000L, session.getSpendableGp());

		session.setBankGp(5_000_000_000L);
		assertEquals(5_001_000_000L, session.getSpendableGp());
		assertEquals(1_000_000L, session.getCurrentCashStack());
	}

	@Test
	public void hopOrLoginDropsLastSeenBankGp()
	{
		PlayerSession session = new PlayerSession();
		session.setCashStack(1_000_000);
		session.setBankGp(5_000_000_000L);

		session.onLoginStateChange(42);

		assertEquals(1_000_000L, session.getSpendableGp());
	}
}
