package com.flipsmart;

import com.flipsmart.api.dto.Dtos.TransactionRequest;
import com.flipsmart.trading.OfferStore;

import java.util.concurrent.CompletableFuture;

import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.game.ItemManager;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Manual-mode Flip Assist prompts ("Sold X — collect from the GE", "Bought X — list it to
 * sell") must disappear once the action is done, without waiting for another prompt to
 * replace them. Drives the real GE tracker and manual tracker; the overlay is a stand-in
 * with FlipAssistOverlay's set / clear-for-item semantics.
 */
public class ManualPromptClearingTest
{
	private static final int ITEM_A = 555; // Water rune
	private static final String NAME_A = "Water rune";
	private static final int ITEM_B = 4151;
	private static final String NAME_B = "Abyssal whip";

	/** Mirrors FlipAssistOverlay.setAutoStatusMessage / clearAutoStatusForItem. */
	private static final class FakeOverlay
	{
		String message;
		int itemId;

		void set(String msg, int id)
		{
			message = msg;
			itemId = id;
		}

		void clearForItem(int id)
		{
			if (itemId == id)
			{
				message = null;
				itemId = 0;
			}
		}
	}

	private FakeOverlay overlay;
	private GrandExchangeTracker tracker;

	@Before
	public void setUp()
	{
		PlayerSession session = new PlayerSession();
		session.setRsn("TestPlayer");
		FlipSmartApiClient api = mock(FlipSmartApiClient.class);
		when(api.recordTransactionAsync(any(TransactionRequest.class))).thenReturn(CompletableFuture.completedFuture(null));
		when(api.dismissActiveFlipAsync(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(true));
		when(api.markActiveFlipSellingAsync(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(true));
		ActiveFlipTracker activeFlips = mock(ActiveFlipTracker.class);
		when(activeFlips.getInventoryCountForItem(anyInt())).thenReturn(0);

		OfferStore store = new OfferStore();
		tracker = new GrandExchangeTracker(session, api, activeFlips, mock(ItemManager.class), mock(TradeActivityLog.class));
		tracker.setOfferStore(store);

		overlay = new FakeOverlay();
		ManualAdjustmentTracker manual = new ManualAdjustmentTracker(api, mock(FlipSmartConfig.class, CALLS_REAL_METHODS), store);
		manual.setOnAdjustmentPrompt(overlay::set);
		manual.setOnPromptCleared(overlay::clearForItem);
		tracker.setManualAdjustmentTracker(manual);
		tracker.setAdjustmentPromptsEnabled(() -> true);
	}

	private void event(int slot, GrandExchangeOfferState state, int itemId, String name, int total, int price, int sold, int spent)
	{
		boolean isBuy = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
		tracker.handleOfferChanged(GrandExchangeTracker.OfferContext.builder()
			.slot(slot).state(state).itemId(itemId).itemName(name)
			.totalQuantity(total).price(price).quantitySold(sold).spent(spent).isBuy(isBuy)
			.build());
	}

	private void completeSell(int slot, int itemId, String name)
	{
		event(slot, GrandExchangeOfferState.SELLING, itemId, name, 100, 5, 0, 0);
		event(slot, GrandExchangeOfferState.SOLD, itemId, name, 100, 5, 100, 500);
	}

	@Test
	public void collectPromptClearsWhenTheSoldOfferIsCollected()
	{
		completeSell(0, ITEM_A, NAME_A);
		assertEquals("Sold Water rune — collect from the GE", overlay.message);

		event(0, GrandExchangeOfferState.EMPTY, ITEM_A, NAME_A, 100, 5, 0, 0);

		assertNull("collected: nothing left to collect, so no prompt", overlay.message);
	}

	@Test
	public void collectingAnotherItemLeavesThePromptForThisItem()
	{
		completeSell(0, ITEM_A, NAME_A);
		event(1, GrandExchangeOfferState.SELLING, ITEM_B, NAME_B, 1, 2_000_000, 0, 0);
		event(1, GrandExchangeOfferState.SOLD, ITEM_B, NAME_B, 1, 2_000_000, 1, 2_000_000);
		assertEquals("Sold Abyssal whip — collect from the GE", overlay.message);

		event(0, GrandExchangeOfferState.EMPTY, ITEM_A, NAME_A, 100, 5, 0, 0);

		assertEquals("Sold Abyssal whip — collect from the GE", overlay.message);
	}

	@Test
	public void listPromptSurvivesCollectingTheBuyAndClearsWhenTheSellIsListed()
	{
		event(0, GrandExchangeOfferState.BUYING, ITEM_A, NAME_A, 100, 4, 0, 0);
		event(0, GrandExchangeOfferState.BOUGHT, ITEM_A, NAME_A, 100, 4, 100, 400);
		assertEquals("Bought Water rune — list it to sell", overlay.message);

		event(0, GrandExchangeOfferState.EMPTY, ITEM_A, NAME_A, 100, 4, 0, 0);
		assertEquals("collecting the bought items doesn't list them",
			"Bought Water rune — list it to sell", overlay.message);

		event(0, GrandExchangeOfferState.SELLING, ITEM_A, NAME_A, 100, 5, 0, 0);
		assertNull("listed: the list prompt is done", overlay.message);
	}

	@Test
	public void autoModeLeavesClearingToAutoRecommend()
	{
		AutoRecommendService auto = mock(AutoRecommendService.class);
		when(auto.isActive()).thenReturn(true);
		tracker.setAutoRecommendService(auto);
		event(0, GrandExchangeOfferState.SELLING, ITEM_A, NAME_A, 100, 5, 0, 0);
		event(0, GrandExchangeOfferState.SOLD, ITEM_A, NAME_A, 100, 5, 100, 500);
		// Auto mode re-focuses the same item on collect (e.g. re-listing returned items);
		// manual-prompt clearing must not wipe that message.
		overlay.set("Preparing to sell Water rune", ITEM_A);
		event(0, GrandExchangeOfferState.EMPTY, ITEM_A, NAME_A, 100, 5, 0, 0);

		assertEquals("Preparing to sell Water rune", overlay.message);
	}
}
