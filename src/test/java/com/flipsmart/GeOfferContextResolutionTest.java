package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the item-scoping contract of
 * {@link GeOfferDescriptionService#resolveOfferContext()} (issues #972, #992).
 *
 * <p>Sources are ranked by how authoritative each is about the item actually on
 * screen, not by how much context each carries:
 *
 * <ol>
 *   <li>Flip Assist focus tracks the plugin's <em>next recommended action</em>, which
 *       is routinely a different item than the panel the player has open. It may only
 *       drive the description when it agrees with the on-screen item (#972).</li>
 *   <li>The "Set up offer" window is pre-confirm and belongs to no committed slot.
 *       While it is open it outranks the active slot, which may hold an unrelated
 *       in-flight offer the player merely hovered or clicked earlier (#992).</li>
 *   <li>The committed offer on the active slot owns the in-flight status panel.</li>
 * </ol>
 */
public class GeOfferContextResolutionTest
{
	private static final int ELY_ITEM_ID = 12817;   // focused elsewhere, insta-buy ~515m
	private static final int KIT_ITEM_ID = 25454;   // item actually on the open offer panel

	private static final int SHARK = 385;           // item on the setup screen
	private static final int RAW_KARAMBWAN = 3142;  // unrelated in-flight offer on another slot

	private static final int GE_OFFERS_GROUP = 465;
	private static final int INDEX_0_CHILD = 7;

	private final Client client = mock(Client.class);

	private GeOfferDescriptionService newService()
	{
		return newServiceWithFocus(null);
	}

	private GeOfferDescriptionService newServiceWithFocus(FocusedFlip focus)
	{
		FlipAssistOverlay overlay = mock(FlipAssistOverlay.class);
		when(overlay.getFocusedFlip()).thenReturn(focus);
		return new GeOfferDescriptionService(
			client,
			mock(ClientThread.class),
			mock(FlipSmartApiClient.class),
			mock(FlipSmartPlugin.class),
			mock(ItemManager.class),
			overlay);
	}

	/** Puts an in-flight offer for {@code itemId} on {@code slot} and selects that slot. */
	private void openOfferPanel(int slot, int itemId, int price)
	{
		putOffer(slot, itemId, price, 1);
		when(client.getVarbitValue(VarbitID.GE_SELECTEDSLOT)).thenReturn(slot);
	}

	/** Puts an in-flight sell offer on {@code slot} without selecting it. */
	private void putOffer(int slot, int itemId, int price, int qty)
	{
		GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(GrandExchangeOfferState.SELLING);
		when(offer.getItemId()).thenReturn(itemId);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getTotalQuantity()).thenReturn(qty);

		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		offers[slot] = offer;
		when(client.getGrandExchangeOffers()).thenReturn(offers);
	}

	/** Opens the "Set up offer" window showing {@code itemId} at price x qty. */
	private void openSetupWindow(int itemId, int price, int qty, boolean isSell)
	{
		Widget setupDesc = mock(Widget.class);
		when(setupDesc.isHidden()).thenReturn(false);
		when(client.getWidget(InterfaceID.GeOffers.SETUP_DESC)).thenReturn(setupDesc);

		when(client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE)).thenReturn(isSell ? 1 : 2);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(itemId);
		when(client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE)).thenReturn(price);
		when(client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY)).thenReturn(qty);
	}

	/** Player clicks the slot tile for {@code slot} (group 465, child 7+N). */
	private void clickSlot(GeOfferDescriptionService service, int slot)
	{
		MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getParam1()).thenReturn((GE_OFFERS_GROUP << 16) | (INDEX_0_CHILD + slot));
		service.onMenuOptionClicked(event);
	}

	// -- #972: focus must not bleed onto an unrelated panel ---------------------

	@Test
	public void focusOnAnotherItemDoesNotBleedOntoOpenOfferPanel()
	{
		// Focus is on the Ely (next recommended action) ...
		GeOfferDescriptionService service =
			newServiceWithFocus(FocusedFlip.forSell(ELY_ITEM_ID, "Elysian spirit shield", 515_000_000, 1));
		// ... but the open GE panel is the Dragon Pickaxe Upgrade Kit.
		openOfferPanel(0, KIT_ITEM_ID, 650_000);

		int[] ctx = service.resolveOfferContext();

		assertNotNull("open offer panel must resolve a context", ctx);
		assertEquals("resolved item must be the on-screen offer item, not the focus",
			KIT_ITEM_ID, ctx[0]);
	}

	@Test
	public void focusIsUsedWhenItMatchesTheOpenOfferPanel()
	{
		// Focus and the open panel are the same item — focus is authoritative and
		// contributes its recommended price (distinct from the raw offer price).
		GeOfferDescriptionService service =
			newServiceWithFocus(FocusedFlip.forSell(KIT_ITEM_ID, "Dragon pickaxe upgrade kit", 700_000, 1));
		openOfferPanel(0, KIT_ITEM_ID, 650_000);

		int[] ctx = service.resolveOfferContext();

		assertNotNull(ctx);
		assertEquals(KIT_ITEM_ID, ctx[0]);
		assertEquals("focus price should drive when focus matches the panel", 700_000, ctx[2]);
	}

	@Test
	public void focusIsUsedWhenNoPanelItemCanBeDetermined()
	{
		// No offers loaded and no setup window open → nothing on screen to compare
		// against, so the focus remains the best available context.
		GeOfferDescriptionService service =
			newServiceWithFocus(FocusedFlip.forSell(ELY_ITEM_ID, "Elysian spirit shield", 515_000_000, 1));
		when(client.getGrandExchangeOffers()).thenReturn(null);
		when(client.getVarbitValue(VarbitID.GE_SELECTEDSLOT)).thenReturn(-1);

		int[] ctx = service.resolveOfferContext();

		assertNotNull(ctx);
		assertEquals(ELY_ITEM_ID, ctx[0]);
	}

	// -- #992: the open setup window outranks an unrelated active slot ----------

	/**
	 * Reported case: an in-flight Raw Karambwan sell sits on a slot the player
	 * clicked earlier; the player then sets up a Shark sell. The panel must show
	 * Shark's numbers, not the Karambwan offer's.
	 */
	@Test
	public void setupWindowOutranksStaleClickedSlot_withFocusOnTheStaleItem()
	{
		GeOfferDescriptionService service =
			newServiceWithFocus(FocusedFlip.forSell(RAW_KARAMBWAN, "Raw karambwan", 274, 4500));

		putOffer(0, RAW_KARAMBWAN, 274, 4500);
		clickSlot(service, 0);
		openSetupWindow(SHARK, 960, 10_000, true);

		int[] ctx = service.resolveOfferContext();

		assertNotNull(ctx);
		assertEquals("item must be the one on the setup screen", SHARK, ctx[0]);
		assertEquals("price must be the setup screen's", 960, ctx[2]);
		assertEquals("qty must be the setup screen's", 10_000, ctx[3]);
	}

	/** Same divergence with no focus at all — isolates the slot path. */
	@Test
	public void setupWindowOutranksStaleClickedSlot_withoutFocus()
	{
		GeOfferDescriptionService service = newService();

		putOffer(0, RAW_KARAMBWAN, 274, 4500);
		clickSlot(service, 0);
		openSetupWindow(SHARK, 960, 10_000, true);

		int[] ctx = service.resolveOfferContext();

		assertNotNull(ctx);
		assertEquals("item must be the one on the setup screen", SHARK, ctx[0]);
		assertEquals("price must be the setup screen's", 960, ctx[2]);
		assertEquals("qty must be the setup screen's", 10_000, ctx[3]);
	}

	/**
	 * No click ever recorded, so resolveActiveSlot() falls back to GE_SELECTEDSLOT,
	 * which tracks the *hovered* slot. Hovering an unrelated in-flight offer must
	 * not poison the setup window either — this is why clearing the clicked-slot
	 * latch alone cannot fix the bug.
	 */
	@Test
	public void setupWindowOutranksMerelyHoveredSlot()
	{
		GeOfferDescriptionService service = newService();

		putOffer(0, RAW_KARAMBWAN, 274, 4500);
		when(client.getVarbitValue(VarbitID.GE_SELECTEDSLOT)).thenReturn(0);
		openSetupWindow(SHARK, 960, 10_000, true);

		int[] ctx = service.resolveOfferContext();

		assertNotNull(ctx);
		assertEquals("item must be the one on the setup screen", SHARK, ctx[0]);
	}

	/** With no setup window open, the clicked slot still owns the in-flight panel. */
	@Test
	public void clickedSlotStillOwnsInFlightPanelWhenNoSetupWindow()
	{
		GeOfferDescriptionService service = newService();

		putOffer(0, RAW_KARAMBWAN, 274, 4500);
		clickSlot(service, 0);
		when(client.getWidget(InterfaceID.GeOffers.SETUP_DESC)).thenReturn(null);

		int[] ctx = service.resolveOfferContext();

		assertNotNull(ctx);
		assertEquals(RAW_KARAMBWAN, ctx[0]);
	}
}
