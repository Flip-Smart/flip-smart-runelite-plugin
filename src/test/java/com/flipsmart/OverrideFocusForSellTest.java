package com.flipsmart;

import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.trading.OfferStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * Pins the tri-state contract of {@link AutoRecommendService#overrideFocusForSell}
 * introduced with the stale re-sell focus fix (#156). The caller's tick-retry
 * logic depends on the distinction between {@code UNAVAILABLE} (state not settled,
 * retry later) and the terminal {@code ALREADY_SELLING}/{@code FOCUSED} outcomes,
 * so each branch is covered here.
 *
 * <p>{@code ALREADY_SELLING} and the reprice/known-price {@code FOCUSED} paths are
 * already pinned in {@link AutoRecommendDispatchTest}; this class fills the
 * {@code UNAVAILABLE} branches and the clean resolve-and-fire-callback path.
 */
public class OverrideFocusForSellTest
{
	@Mock private FlipSmartConfig config;
	@Mock private FlipSmartPlugin plugin;
	@Mock private FlipSmartApiClient apiClient;
	private OfferStore offerStore;
	private AutoRecommendService service;
	private PlayerSession session;

	private static FlipRecommendation rec(int itemId)
	{
		FlipRecommendation r = new FlipRecommendation();
		r.setItemId(itemId);
		r.setItemName("item-" + itemId);
		r.setRecommendedBuyPrice(100);
		r.setRecommendedSellPrice(200);
		r.setRecommendedQuantity(10);
		return r;
	}

	@Before
	public void setUp()
	{
		MockitoAnnotations.openMocks(this);
		offerStore = new OfferStore();
		session = new PlayerSession();
		when(plugin.getSession()).thenReturn(session);
		when(plugin.getFlipSlotLimit()).thenReturn(8);
		when(plugin.getActiveFlipItemIds()).thenReturn(new HashSet<>());
		when(plugin.getApiClient()).thenReturn(apiClient);
		when(apiClient.isRsnBlocked()).thenReturn(false);
		when(config.priceOffset()).thenReturn(0);
		when(config.minimumProfit()).thenReturn(1);
		when(plugin.isClientThread()).thenReturn(true);
		service = new AutoRecommendService(config, plugin, offerStore);
		service.start(Arrays.asList(rec(21)));
	}

	@Test
	public void inactiveServiceReturnsUnavailable()
	{
		service.stop();
		assertEquals(AutoRecommendService.SellFocusResult.UNAVAILABLE,
			service.overrideFocusForSell(21, "item-21"));
	}

	@Test
	public void nullSessionReturnsUnavailable()
	{
		when(plugin.getSession()).thenReturn(null);
		assertEquals(AutoRecommendService.SellFocusResult.UNAVAILABLE,
			service.overrideFocusForSell(21, "item-21"));
	}

	@Test
	public void unresolvablePriceReturnsUnavailable()
	{
		// Item 999 is not in the queue and has no session/advisor price, so no sell price
		// can be resolved yet → caller should retry on a later tick.
		assertEquals(AutoRecommendService.SellFocusResult.UNAVAILABLE,
			service.overrideFocusForSell(999, "item-999"));
	}

	@Test
	public void knownPriceButZeroQuantityReturnsUnavailable()
	{
		// Price resolvable from session, but nothing in inventory / collected and no rec to
		// supply a fallback quantity → quantity unsettled → UNAVAILABLE.
		session.setRecommendedPrice(888, 150);
		when(plugin.getInventoryCountForItem(888)).thenReturn(0);
		assertEquals(AutoRecommendService.SellFocusResult.UNAVAILABLE,
			service.overrideFocusForSell(888, "item-888"));
	}

	@Test
	public void resolvedPriceAndQuantityReturnsFocusedAndFiresCallback() throws Exception
	{
		session.setRecommendedPrice(888, 150);
		when(plugin.getInventoryCountForItem(888)).thenReturn(5);

		AtomicReference<FocusedFlip> painted = new AtomicReference<>();
		service.setOnFocusChanged(painted::set);

		AutoRecommendService.SellFocusResult result = service.overrideFocusForSell(888, "item-888");
		SwingUtilities.invokeAndWait(() -> { });

		assertEquals(AutoRecommendService.SellFocusResult.FOCUSED, result);
		assertNotNull("resolve must fire the focus callback", painted.get());
		assertEquals(888, painted.get().getItemId());
		assertEquals("focus must carry the resolved sell price", 150, painted.get().getCurrentStepPrice());
	}
}
