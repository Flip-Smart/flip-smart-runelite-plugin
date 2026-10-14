package com.flipsmart.plugin;

import com.flipsmart.AutoRecommendService;
import com.flipsmart.BankSnapshotService;
import com.flipsmart.FlipSmartConfig;
import com.flipsmart.FlipSmartPlugin;
import com.flipsmart.GEHistoryService;
import com.flipsmart.GeOfferDescriptionService;
import com.flipsmart.GrandExchangeTracker;
import com.flipsmart.OfflineSyncService;
import com.flipsmart.PlayerSession;
import com.flipsmart.recommend.ActionDecision;
import com.flipsmart.recommend.ActionKind;
import com.flipsmart.recommend.ActionStep;
import com.flipsmart.trading.OfferStore;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.PostMenuSort;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Left-click swap on GE offer slots: only the slot Auto mode is currently prompting a
 * re-price (Modify) or cancel (Abort) on gets its entry promoted, and only while Auto
 * mode is running. Everything else keeps the vanilla menu.
 */
public class EventRouterMenuSwapTest
{
	private static final int GE_OFFERS_GROUP = 465;
	private static final int INDEX_0_CHILD = 7;
	private static final int ITEM = 4151;

	private Client client;
	private AutoRecommendService auto;
	private EventRouter router;

	@Before
	public void setUp()
	{
		FlipSmartPlugin plugin = mock(FlipSmartPlugin.class);
		client = mock(Client.class);
		auto = mock(AutoRecommendService.class);
		when(plugin.getAutoRecommendService()).thenReturn(auto);
		router = new EventRouter(
			plugin,
			client,
			mock(FlipSmartConfig.class),
			mock(PlayerSession.class),
			mock(OfflineSyncService.class),
			mock(BankSnapshotService.class),
			mock(GEHistoryService.class),
			mock(GeOfferDescriptionService.class),
			mock(GrandExchangeTracker.class),
			new OfferStore());
	}

	private static int slotWidget(int slot)
	{
		return (GE_OFFERS_GROUP << 16) | (INDEX_0_CHILD + slot);
	}

	private static MenuEntry entry(String option, int param1)
	{
		MenuEntry e = mock(MenuEntry.class);
		when(e.getOption()).thenReturn(option);
		when(e.getParam1()).thenReturn(param1);
		return e;
	}

	private static ActionDecision decision(ActionStep step, int slot)
	{
		return new ActionDecision(ActionKind.S1, step, ITEM, slot, 0L);
	}

	/** Menu entries are bottom-to-top: the last element is the left-click option. */
	private MenuEntry[] slotMenu(int slot)
	{
		return new MenuEntry[]{
			entry("Cancel", 0),
			entry("Modify offer", slotWidget(slot)),
			entry("Abort offer", slotWidget(slot)),
			entry("View offer", slotWidget(slot)),
		};
	}

	private MenuEntry[] runSwap(ActionDecision decision, boolean active, int menuSlot)
	{
		return runSwap(decision, active, menuSlot, ITEM);
	}

	private MenuEntry[] runSwap(ActionDecision decision, boolean active, int menuSlot, int slotItem)
	{
		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		for (int i = 0; i < offers.length; i++)
		{
			offers[i] = mock(GrandExchangeOffer.class);
			when(offers[i].getItemId()).thenReturn(slotItem);
		}
		when(client.getGrandExchangeOffers()).thenReturn(offers);
		when(auto.isActive()).thenReturn(active);
		when(auto.getLastAppliedDecision()).thenReturn(decision);
		MenuEntry[] menu = slotMenu(menuSlot);
		when(client.getMenuEntries()).thenReturn(menu);
		router.onPostMenuSort(new PostMenuSort());
		return menu;
	}

	@Test
	public void repriceOnPromptedSlotMakesModifyLeftClick()
	{
		MenuEntry[] menu = runSwap(decision(ActionStep.REPRICE, 3), true, 3);
		assertEquals("Modify offer", menu[3].getOption());
		assertEquals("View offer", menu[1].getOption());
		verify(client).setMenuEntries(menu);
	}

	@Test
	public void cancelOnPromptedSlotMakesAbortLeftClick()
	{
		MenuEntry[] menu = runSwap(decision(ActionStep.CANCEL, 0), true, 0);
		assertEquals("Abort offer", menu[3].getOption());
		verify(client).setMenuEntries(menu);
	}

	@Test
	public void otherSlotKeepsVanillaMenu()
	{
		MenuEntry[] menu = runSwap(decision(ActionStep.CANCEL, 2), true, 5);
		assertEquals("View offer", menu[3].getOption());
		verify(client, never()).setMenuEntries(any());
	}

	@Test
	public void slotReusedForAnotherItemKeepsVanillaMenu()
	{
		MenuEntry[] menu = runSwap(decision(ActionStep.CANCEL, 1), true, 1, ITEM + 1);
		assertEquals("View offer", menu[3].getOption());
		verify(client, never()).setMenuEntries(any());
	}

	@Test
	public void autoModeOffNeverSwaps()
	{
		MenuEntry[] menu = runSwap(decision(ActionStep.CANCEL, 1), false, 1);
		assertEquals("View offer", menu[3].getOption());
		verify(client, never()).setMenuEntries(any());
	}

	@Test
	public void nonOfferPromptsNeverSwap()
	{
		assertNull(EventRouter.swapOption(null));
		assertNull(EventRouter.swapOption(decision(ActionStep.COLLECT, 1)));
		assertNull(EventRouter.swapOption(decision(ActionStep.LIST, 1)));
		assertNull(EventRouter.swapOption(decision(ActionStep.CANCEL, -1)));
		assertSame("Modify", EventRouter.swapOption(decision(ActionStep.REPRICE, 1)));
	}

	@Test
	public void slotOfRejectsNonGeWidgets()
	{
		assertEquals(4, GeOfferDescriptionService.slotOf(slotWidget(4)));
		assertEquals(-1, GeOfferDescriptionService.slotOf(slotWidget(8)));
		assertEquals(-1, GeOfferDescriptionService.slotOf((149 << 16) | INDEX_0_CHILD));
	}
}
