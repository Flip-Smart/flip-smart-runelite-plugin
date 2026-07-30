package com.flipsmart;

import com.flipsmart.api.dto.ActiveFlipsResponse;
import com.flipsmart.domain.flip.AwaitingSaleLots;
import com.flipsmart.recommend.SmartSellPricer;
import com.flipsmart.trading.OfferStore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import net.runelite.client.game.ItemManager;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Manual-mode (Auto Mode off) sell focus must resolve from local state.
 *
 * <p>Prod (#1133): the manual path resolved the sell price only from
 * {@code GET /transactions/active-flips}, which the backend trims to the subscription's
 * display slots — 2 on free tier, unlimited on premium. A free user's held position could
 * be absent from that response, so {@code handleActiveFlipResponse} found no match, never
 * reached {@code setFocusForSell}, and no price was populated at all. Auto Mode was
 * unaffected because it resolves locally, which is why the bug looked free-and-manual only.
 *
 * <p>A display cap must never stop someone selling what they already own.</p>
 */
public class ManualSellFocusLocalResolutionTest
{
	private static final int ITEM_ID = 4151; // Abyssal whip
	private static final String ITEM_NAME = "Abyssal whip";
	private static final int AVG_BUY_PRICE = 1_000_000;
	private static final int HELD_QUANTITY = 3;

	private PlayerSession session;
	private FlipSmartApiClient apiClient;
	private ActiveFlipTracker activeFlipTracker;
	private GrandExchangeTracker tracker;
	private AtomicReference<FocusedFlip> focus;

	@Before
	public void setUp()
	{
		session = new PlayerSession();
		session.setRsn("TestPlayer");

		apiClient = mock(FlipSmartApiClient.class);
		activeFlipTracker = mock(ActiveFlipTracker.class);
		ItemManager itemManager = mock(ItemManager.class);

		// The backend list is EMPTY throughout: this is the free-tier trim, where the
		// held position never appears in the response the old path depended on.
		when(apiClient.getActiveFlipsAsync(any()))
			.thenReturn(CompletableFuture.completedFuture(new ActiveFlipsResponse()));
		when(activeFlipTracker.getInventoryCountForItem(anyInt())).thenReturn(HELD_QUANTITY);

		tracker = new GrandExchangeTracker(
			session, apiClient, activeFlipTracker, itemManager, mock(TradeActivityLog.class));
		tracker.setOfferStore(new OfferStore());

		focus = new AtomicReference<>();
		tracker.setOnFocusChanged(focus::set);

		// Auto-recommend left unset => manual mode, the path under test.
	}

	/** onFocusChanged is dispatched to the EDT; drain it before asserting. */
	private FocusedFlip awaitFocus() throws Exception
	{
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});
		return focus.get();
	}

	@Test
	public void pricesTheExitFromTheOfferStoreBasisWhenTheBackendListOmitsTheHolding() throws Exception
	{
		tracker.setBuyBasisProvider(
			itemId -> new AwaitingSaleLots.BuyBasis(ITEM_NAME, AVG_BUY_PRICE, null));

		tracker.autoFocusOnActiveFlip(ITEM_ID);

		FocusedFlip focused = awaitFocus();
		assertNotNull("free-tier holding must still be priced for sale", focused);
		assertEquals(ITEM_ID, focused.getItemId());
		assertEquals(HELD_QUANTITY, focused.getSellQuantity());
		assertEquals(SmartSellPricer.calculateMinProfitableSellPrice(AVG_BUY_PRICE),
			focused.getSellPrice());
	}

	@Test
	public void anEmptyBackendListNeitherBlocksNorUndoesTheLocalFocus() throws Exception
	{
		// The lookup still fires — it carries the under-counted-quantity reconciliation —
		// but a response that omits the holding must no longer decide whether a price shows.
		tracker.setBuyBasisProvider(
			itemId -> new AwaitingSaleLots.BuyBasis(ITEM_NAME, AVG_BUY_PRICE, null));

		tracker.autoFocusOnActiveFlip(ITEM_ID);

		verify(apiClient).getActiveFlipsAsync(any());
		assertNotNull("empty backend response must not clear a locally-priced focus", awaitFocus());
	}

	@Test
	public void prefersThePanelPriceOverTheDerivedBreakevenPrice() throws Exception
	{
		tracker.setBuyBasisProvider(
			itemId -> new AwaitingSaleLots.BuyBasis(ITEM_NAME, AVG_BUY_PRICE, null));
		tracker.setDisplayedSellPriceProvider(itemId -> 1_234_567);

		tracker.autoFocusOnActiveFlip(ITEM_ID);

		assertEquals(1_234_567, awaitFocus().getSellPrice());
	}

	@Test
	public void stillFallsBackToTheBackendLookupWhenNothingLocalCanPriceTheExit() throws Exception
	{
		// No buy basis, no cached price: local resolution genuinely cannot price this,
		// so the original backend path must remain in place.
		tracker.setBuyBasisProvider(itemId -> null);

		tracker.autoFocusOnActiveFlip(ITEM_ID);

		verify(apiClient).getActiveFlipsAsync(any());
		// Nothing priced it, and the stubbed backend list is empty, so no focus is invented.
		assertNull(awaitFocus());
	}

	@Test
	public void doesNotFocusAnItemThePlayerDoesNotHold() throws Exception
	{
		when(activeFlipTracker.getInventoryCountForItem(anyInt())).thenReturn(0);
		tracker.setBuyBasisProvider(
			itemId -> new AwaitingSaleLots.BuyBasis(ITEM_NAME, AVG_BUY_PRICE, null));

		tracker.autoFocusOnActiveFlip(ITEM_ID);

		// Falls through to the backend rather than inventing a quantity out of nothing.
		verify(apiClient).getActiveFlipsAsync(any());
		assertNull(awaitFocus());
	}

	@Test
	public void derivedPriceClearsGeTaxSoTheExitIsNotAGuaranteedLoss() throws Exception
	{
		tracker.setBuyBasisProvider(
			itemId -> new AwaitingSaleLots.BuyBasis(ITEM_NAME, AVG_BUY_PRICE, null));

		tracker.autoFocusOnActiveFlip(ITEM_ID);

		assertTrue("a locally-derived price must still beat breakeven after 2% tax",
			awaitFocus().getSellPrice() * 0.98 > AVG_BUY_PRICE);
	}
}
