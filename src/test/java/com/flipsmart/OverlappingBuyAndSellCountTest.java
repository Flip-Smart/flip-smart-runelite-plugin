package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1116 AC1/AC3: collecting part of a buy and relisting it to sell before the buy finishes puts
 * one recommended item on two GE slots at once. That pair must consume one of a free user's flip
 * slots, not two.
 *
 * <p>The collapse happens in {@link FlipSmartPlugin#getActiveFlipItemIds()}, which folds every
 * live offer into a set keyed by item id. Asserting it against {@code ActiveFlipItemIds.derive}
 * in isolation proves nothing — that method's caller has already deduplicated, so the two legs
 * have to enter as two separate offers for the assertion to carry any weight.</p>
 */
public class OverlappingBuyAndSellCountTest
{
	private static final int RUNE_SCIMITAR = 4151;
	private static final long T0 = 1_000_000L;

	// Mirrors PlayerSession.FLIP_FINDER_SOURCED_GRACE_MS. Counting past the grace window is what
	// makes the count assertion meaningful: within grace a marked item counts whether or not it
	// is actually active, so the collapse would go unmeasured.
	private static final long PAST_GRACE = T0 + 8001;

	private static void inject(FlipSmartPlugin plugin, String field, Object value) throws Exception
	{
		Field declared = FlipSmartPlugin.class.getDeclaredField(field);
		declared.setAccessible(true);
		declared.set(plugin, value);
	}

	private static OfferRecord legOn(int itemId)
	{
		OfferRecord leg = mock(OfferRecord.class);
		when(leg.getItemId()).thenReturn(itemId);
		return leg;
	}

	@Test
	public void anEarlyResaleOfARecommendedItemCountsOnceNotTwice() throws Exception
	{
		// Two live offers, one item: the buy still working, and the sell of the part collected.
		// Built before the store is stubbed — stubbing a mock inside an open when() is an error.
		OfferRecord buyLeg = legOn(RUNE_SCIMITAR);
		OfferRecord sellLeg = legOn(RUNE_SCIMITAR);

		OfferStore offerStore = mock(OfferStore.class);
		when(offerStore.liveOffers()).thenReturn(Arrays.asList(buyLeg, sellLeg));

		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(RUNE_SCIMITAR, T0);

		FlipSmartPlugin plugin = new FlipSmartPlugin();
		inject(plugin, "offerStore", offerStore);
		inject(plugin, "session", session);
		inject(plugin, "inventoryFlipItemCounts", Collections.singletonMap(RUNE_SCIMITAR, 5));
		inject(plugin, "inventorySnapshotKnown", true);

		Set<Integer> active = plugin.getActiveFlipItemIds();

		assertEquals("two legs of one item are one active flip", 1, active.size());
		assertEquals("the free-tier counter charges the item once", 1,
			session.retainAndCountFlipFinderActive(active, PAST_GRACE));
	}

	@Test
	public void twoDifferentRecommendedItemsStillCountSeparately() throws Exception
	{
		final int ABYSSAL_WHIP = 4151 + 1;
		OfferRecord scimitarLeg = legOn(RUNE_SCIMITAR);
		OfferRecord whipLeg = legOn(ABYSSAL_WHIP);

		OfferStore offerStore = mock(OfferStore.class);
		when(offerStore.liveOffers()).thenReturn(Arrays.asList(scimitarLeg, whipLeg));

		PlayerSession session = new PlayerSession();
		session.markFlipFinderSourced(RUNE_SCIMITAR, T0);
		session.markFlipFinderSourced(ABYSSAL_WHIP, T0);

		FlipSmartPlugin plugin = new FlipSmartPlugin();
		inject(plugin, "offerStore", offerStore);
		inject(plugin, "session", session);
		inject(plugin, "inventoryFlipItemCounts", Collections.emptyMap());
		inject(plugin, "inventorySnapshotKnown", true);

		Set<Integer> active = plugin.getActiveFlipItemIds();

		assertEquals("distinct items are distinct flips", 2, active.size());
		assertEquals(2, session.retainAndCountFlipFinderActive(active, PAST_GRACE));
	}
}
