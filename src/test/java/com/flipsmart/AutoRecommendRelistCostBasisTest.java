package com.flipsmart;

import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

/**
 * A cancelled and re-listed sell must keep its original buy cost as the breakeven basis.
 * Falling back to the new list price makes every adjustment check hold, so Auto never re-prices.
 */
public class AutoRecommendRelistCostBasisTest
{
	private static final int ITEM = 21;
	private static final long BUY_COST = 1110;
	private static final long LIST_PRICE = 1450;

	@Mock private FlipSmartConfig config;
	@Mock private FlipSmartPlugin plugin;
	@Mock private FlipSmartApiClient apiClient;
	private OfferStore offerStore;
	private PlayerSession session;
	private AutoRecommendService service;

	private static FlipRecommendation rec(int itemId, long buy)
	{
		FlipRecommendation r = new FlipRecommendation();
		r.setItemId(itemId);
		r.setItemName("item-" + itemId);
		r.setRecommendedBuyPrice(buy);
		r.setRecommendedSellPrice(buy * 2);
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
		when(plugin.isClientThread()).thenReturn(true);
		when(config.priceOffset()).thenReturn(0);
		when(config.minimumProfit()).thenReturn(1);
		when(config.flipTimeframe()).thenReturn(FlipSmartConfig.FlipTimeframe.ACTIVE);
		service = new AutoRecommendService(config, plugin, offerStore);
		service.start(Collections.singletonList(rec(ITEM, BUY_COST)));
	}

	private void listSell(long offerId)
	{
		offerStore.importRecords(Collections.singletonList(
			OfferRecord.newOffer(offerId, 0, ITEM, "item-" + ITEM, false, 10, LIST_PRICE, 0L)));
		service.onSellOrderPlaced(ITEM);
	}

	private void cancelAndCollectSell(long offerId)
	{
		offerStore.importRecords(Collections.singletonList(
			OfferRecord.newOffer(offerId, 0, ITEM, "item-" + ITEM, false, 10, LIST_PRICE, 0L)
				.withState(OfferState.CANCELLED_EMPTY, 1L)));
		service.onOfferCancelled(ITEM, "item-" + ITEM, false, 0, 10);
		service.onOfferCollected(ITEM, false, "item-" + ITEM, 0);
	}

	private long sellBasis()
	{
		return service.adjustments.getSellState(ITEM).averageBuyPrice;
	}

	@Test
	public void relistedSellKeepsBuyCostAfterRecommendationRotatesOut()
	{
		service.onBuyOrderPlaced(ITEM);
		service.refreshQueue(Collections.singletonList(rec(99, 50)));

		listSell(2);
		assertEquals(BUY_COST, sellBasis());

		cancelAndCollectSell(2);
		listSell(3);

		assertEquals("re-list must not fall back to the list price", BUY_COST, sellBasis());
	}

	@Test
	public void basisResolvedFromQueueSurvivesRelist()
	{
		// No buy placed through Auto: the first listing resolves the basis from the queue.
		listSell(2);
		service.refreshQueue(Collections.singletonList(rec(99, 50)));

		cancelAndCollectSell(2);
		listSell(3);

		assertEquals(BUY_COST, sellBasis());
	}

	@Test
	public void recordedBuyCostIsUsedBeforeListPrice()
	{
		service.refreshQueue(Collections.singletonList(rec(99, 50)));
		when(plugin.getRecordedBuyCost(ITEM)).thenReturn(BUY_COST);

		listSell(2);

		assertEquals(BUY_COST, sellBasis());
	}

	@Test
	public void listPriceIsLastResortAndNotStored()
	{
		service.refreshQueue(Collections.singletonList(rec(99, 50)));

		listSell(2);

		assertEquals(LIST_PRICE, sellBasis());
		assertNull(service.adjustments.getBuyPrice(ITEM));
	}

	@Test
	public void fullySoldCollectionStillClearsBuyCost()
	{
		service.onBuyOrderPlaced(ITEM);
		listSell(2);

		service.onOfferCollected(ITEM, false, "item-" + ITEM, 10);

		assertNull(service.adjustments.getBuyPrice(ITEM));
	}
}
