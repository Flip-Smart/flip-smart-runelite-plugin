package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the item-scoping contract of
 * {@link GeOfferDescriptionService#resolveOfferContext()} (issue #972).
 *
 * <p>Flip Assist focus tracks the plugin's <em>next recommended action</em>, which
 * is routinely a different item than the GE offer panel the player currently has
 * open. Focus must therefore only drive the description when it agrees with the
 * item actually on screen — otherwise the focused item's wiki price/volume bleeds
 * onto an unrelated offer panel while 2+ flips are active.
 */
public class GeOfferContextResolutionTest
{
	private static final int ELY_ITEM_ID = 12817;   // focused elsewhere, insta-buy ~515m
	private static final int KIT_ITEM_ID = 25454;   // item actually on the open offer panel

	private final Client client = mock(Client.class);

	private GeOfferDescriptionService newService()
	{
		FlipAssistOverlay overlay = mock(FlipAssistOverlay.class);
		return new GeOfferDescriptionService(
			client,
			mock(ClientThread.class),
			mock(FlipSmartApiClient.class),
			mock(FlipSmartPlugin.class),
			mock(ItemManager.class),
			overlay);
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
		GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(GrandExchangeOfferState.SELLING);
		when(offer.getItemId()).thenReturn(itemId);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getTotalQuantity()).thenReturn(1);

		GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
		offers[slot] = offer;
		when(client.getGrandExchangeOffers()).thenReturn(offers);
		when(client.getVarbitValue(VarbitID.GE_SELECTEDSLOT)).thenReturn(slot);
	}

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
}
