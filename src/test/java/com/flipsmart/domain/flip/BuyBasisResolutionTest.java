package com.flipsmart.domain.flip;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * #1237: the GE offer screen and slot hover priced a position off a cost basis that had nothing to
 * do with the stock the player was holding. Both reported cases are pinned here with the real
 * figures from production.
 *
 * <p>The offer store never evicts, so a buy record outlives the round trip it belongs to. Taking
 * the most recent one produced a basis from a position already sold, and when no record had filled
 * at all the price fell through to the offer's LISTED price — a plan, never a cost.</p>
 */
public class BuyBasisResolutionTest
{
	private static final int BRACELET = 19544;
	private static final String BRACELET_NAME = "Tormented bracelet";

	/** The 8/7 buy, whose flip closed on 8/10 — still sitting in the store on 8/12. */
	private static final int STALE_CLOSED_CYCLE_BASIS = 19_138_141;
	/** The 8/12 buy the player actually held. */
	private static final int OPEN_CYCLE_BASIS = 18_971_135;
	/** A soulreaper axe offer listed on 8/10 that never bought a single item. */
	private static final int NEVER_FILLED_LISTED_PRICE = 426_136_723;

	private static OfferRecord filledBuy(int price, int filled, long activityMillis)
	{
		return OfferRecord.newOffer(activityMillis, 0, BRACELET, BRACELET_NAME, true, filled, price, 0L)
			.withCreatedAtMillis(activityMillis)
			.withFill(filled, (long) price * filled, OfferState.COLLECTED, activityMillis);
	}

	private static OfferRecord unfilledBuy(int listedPrice, long activityMillis)
	{
		return OfferRecord.newOffer(activityMillis, 0, BRACELET, BRACELET_NAME, true, 1, listedPrice, 0L)
			.withCreatedAtMillis(activityMillis)
			.withActivityAtMillis(activityMillis);
	}

	@Test
	public void ledgerCycleBasisWinsOverARecordFromAClosedRoundTrip()
	{
		List<OfferRecord> buys = asList(
			filledBuy(STALE_CLOSED_CYCLE_BASIS, 1, 1_000L),
			filledBuy(OPEN_CYCLE_BASIS, 1, 2_000L));

		AwaitingSaleLots.BuyBasis basis = AwaitingSaleLots.resolveBuyBasis(buys, OPEN_CYCLE_BASIS);

		assertNotNull(basis);
		assertEquals(OPEN_CYCLE_BASIS, basis.avgBuyPrice);
	}

	@Test
	public void ledgerWinsEvenWhenTheStaleRecordIsTheMostRecentOne()
	{
		// The store's ordering is not a reliable proxy for which position is open: a re-detected or
		// re-touched old offer can carry the latest activity stamp.
		List<OfferRecord> buys = Collections.singletonList(
			filledBuy(STALE_CLOSED_CYCLE_BASIS, 1, 9_000L));

		AwaitingSaleLots.BuyBasis basis = AwaitingSaleLots.resolveBuyBasis(buys, OPEN_CYCLE_BASIS);

		assertNotNull(basis);
		assertEquals(OPEN_CYCLE_BASIS, basis.avgBuyPrice);
	}

	@Test
	public void anOfferThatNeverFilledSuppliesNoPrice()
	{
		List<OfferRecord> buys = Collections.singletonList(
			unfilledBuy(NEVER_FILLED_LISTED_PRICE, 1_000L));

		AwaitingSaleLots.BuyBasis basis = AwaitingSaleLots.resolveBuyBasis(buys, null);

		assertNotNull(basis);
		assertEquals(0, basis.avgBuyPrice);
	}

	@Test
	public void aFilledRecordStillPricesTheLotWhenTheLedgerIsSilent()
	{
		List<OfferRecord> buys = Collections.singletonList(filledBuy(1_000, 5, 1_000L));

		AwaitingSaleLots.BuyBasis basis = AwaitingSaleLots.resolveBuyBasis(buys, null);

		assertNotNull(basis);
		assertEquals(1_000, basis.avgBuyPrice);
	}

	@Test
	public void aFilledRecordOutranksAMoreRecentUnfilledOffer()
	{
		List<OfferRecord> buys = asList(
			filledBuy(1_000, 5, 1_000L),
			unfilledBuy(NEVER_FILLED_LISTED_PRICE, 9_000L));

		AwaitingSaleLots.BuyBasis basis = AwaitingSaleLots.resolveBuyBasis(buys, null);

		assertNotNull(basis);
		assertEquals(1_000, basis.avgBuyPrice);
	}

	@Test
	public void identityStillComesFromTheStoreWhenTheLedgerPricesTheLot()
	{
		List<OfferRecord> buys = Collections.singletonList(filledBuy(1_000, 5, 4_000L));

		AwaitingSaleLots.BuyBasis basis = AwaitingSaleLots.resolveBuyBasis(buys, OPEN_CYCLE_BASIS);

		assertNotNull(basis);
		assertEquals(BRACELET_NAME, basis.itemName);
		assertEquals("1970-01-01T00:00:04Z", basis.firstBuyTimeIso);
	}

	@Test
	public void noBuyRecordsMeansNoLot()
	{
		assertNull(AwaitingSaleLots.resolveBuyBasis(Collections.emptyList(), OPEN_CYCLE_BASIS));
		assertNull(AwaitingSaleLots.resolveBuyBasis(null, OPEN_CYCLE_BASIS));
	}

	@Test
	public void aNonPositiveLedgerBasisIsNotTreatedAsAPrice()
	{
		List<OfferRecord> buys = Collections.singletonList(filledBuy(1_000, 5, 1_000L));

		AwaitingSaleLots.BuyBasis basis = AwaitingSaleLots.resolveBuyBasis(buys, 0);

		assertNotNull(basis);
		assertEquals(1_000, basis.avgBuyPrice);
	}
}
