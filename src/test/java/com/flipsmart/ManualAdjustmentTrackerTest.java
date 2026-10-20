package com.flipsmart;

import com.flipsmart.api.dto.Dtos.FlipAdjustmentResponse;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The adjust glow on a GE slot tracks that slot's own latest verdict, and only a real re-price lights it. */
public class ManualAdjustmentTrackerTest
{
	private static final long TEN_MINUTES_MS = 10 * 60_000L;
	private static final int FIRE_RUNE = 554;
	private static final int EARTH_RUNE = 557;

	private FlipSmartApiClient api;
	private OfferStore store;
	private ManualAdjustmentTracker tracker;
	private final List<String> events = new ArrayList<>();

	@Before
	public void setUp()
	{
		api = mock(FlipSmartApiClient.class);
		store = mock(OfferStore.class);
		tracker = new ManualAdjustmentTracker(api, mock(FlipSmartConfig.class, CALLS_REAL_METHODS), store);
		tracker.setOnHighlightSlot((slot, price) -> events.add("glow " + slot + " @" + price));
		tracker.setOnClearHighlight(slot -> events.add("clear " + slot));
	}

	private OfferRecord sell(int slot, int itemId, long price)
	{
		OfferRecord offer = OfferRecord.newOffer(slot + 1L, slot, itemId, "item" + itemId, false, 10_000, price,
			System.currentTimeMillis() - TEN_MINUTES_MS);
		when(store.bySlot(slot)).thenReturn(offer);
		return offer;
	}

	private static FlipAdjustmentResponse response(String action, Long price)
	{
		FlipAdjustmentResponse r = new FlipAdjustmentResponse();
		r.setAction(action);
		r.setRecommendedPrice(price);
		r.setNextCheckMinutes(-1);
		return r;
	}

	private void answer(FlipAdjustmentResponse response)
	{
		when(api.getFlipAdjustmentAsync(any())).thenReturn(CompletableFuture.completedFuture(response));
	}

	private void track(OfferRecord... offers)
	{
		when(store.liveOffers()).thenReturn(Arrays.asList(offers));
		tracker.ensureTimersForLiveOffers();
	}

	@Test
	public void readjustToTheListedPriceDoesNotGlow()
	{
		track(sell(2, FIRE_RUNE, 6));
		answer(response("readjust_sell", 6L));

		tracker.checkTimers();

		assertFalse(events.contains("glow 2 @6"));
	}

	@Test
	public void genuineReadjustStillGlows()
	{
		track(sell(2, FIRE_RUNE, 9));
		answer(response("readjust_sell", 6L));

		tracker.checkTimers();

		assertEquals(List.of("glow 2 @6"), events);
	}

	@Test
	public void holdClearsThatSlotsGlow()
	{
		track(sell(2, FIRE_RUNE, 6));
		answer(response("hold", 6L));

		tracker.checkTimers();

		assertEquals(List.of("clear 2"), events);
	}

	@Test
	public void partialFillClearsTheGlow()
	{
		track(sell(2, FIRE_RUNE, 9));
		answer(response("readjust_sell", 6L));
		tracker.checkTimers();

		tracker.resetTimer(2);

		assertEquals(List.of("glow 2 @6", "clear 2"), events);
	}

	@Test
	public void eachSlotGlowsOnItsOwnVerdict()
	{
		track(sell(1, FIRE_RUNE, 9), sell(2, EARTH_RUNE, 6));
		when(api.getFlipAdjustmentAsync(any())).thenAnswer(inv ->
		{
			boolean fire = inv.<com.flipsmart.api.dto.Dtos.FlipAdjustmentRequest>getArgument(0).itemId == FIRE_RUNE;
			return CompletableFuture.completedFuture(fire ? response("readjust_sell", 6L) : response("hold", 6L));
		});

		tracker.checkTimers();

		assertTrue(events.contains("glow 1 @6"));
		assertFalse(events.contains("clear 1"));
		assertFalse(events.stream().anyMatch(e -> e.startsWith("glow 2")));
	}
}
