package com.flipsmart;
import com.flipsmart.domain.offer.TrackedOffer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PlayerSession}. Focused on the RSN lifecycle that
 * underpins the multi-account contamination fix (issue #549 / #556).
 */
public class PlayerSessionTest
{
	@Test
	public void onLogout_clearsCachedRsnSoNextLoginCannotReuseIt()
	{
		PlayerSession session = new PlayerSession();
		session.setRsn("AccountA");
		assertEquals("AccountA", session.getRsnSafe().orElse(null));

		session.onLogout();

		assertFalse(
			"onLogout must drop the cached RSN; otherwise a subsequent login as a different "
				+ "account reuses the previous account's name (issue #549).",
			session.getRsnSafe().isPresent()
		);
	}

	@Test
	public void onLogout_alsoFlipsLoggedIntoRunescapeBackToFalse()
	{
		PlayerSession session = new PlayerSession();
		session.onLoggedIn();
		assertTrue(session.isLoggedIntoRunescape());

		session.onLogout();

		assertFalse(session.isLoggedIntoRunescape());
	}

	@Test
	public void clear_alsoNullsRsn()
	{
		PlayerSession session = new PlayerSession();
		session.setRsn("AccountA");

		session.clear();

		assertFalse(session.getRsnSafe().isPresent());
	}

	@Test
	public void getRsnSafe_returnsEmptyForNullOrBlank()
	{
		PlayerSession session = new PlayerSession();
		assertFalse(session.getRsnSafe().isPresent());

		session.setRsn("");
		assertFalse(session.getRsnSafe().isPresent());

		session.setRsn("Foo");
		assertEquals("Foo", session.getRsnSafe().orElse(null));
	}

	private static TrackedOffer inFlightBuy(int itemId)
	{
		return new TrackedOffer(itemId, "x", true, 100, 1, 5);
	}

	private static TrackedOffer completedBuy(int itemId)
	{
		TrackedOffer offer = new TrackedOffer(itemId, "x", true, 100, 1, 100);
		offer.setCompletedAtMillis(System.currentTimeMillis());
		return offer;
	}

	@Test
	public void hasInFlightBuyOffer_trueForBuyingPartialFill()
	{
		PlayerSession session = new PlayerSession();
		session.putTrackedOffer(0, inFlightBuy(3827));

		assertTrue(session.hasInFlightBuyOfferForItem(3827));
		assertFalse(session.hasUncollectedBuyOfferForItem(3827));
	}

	@Test
	public void hasUncollectedBuyOffer_trueForCompletedNotYetCollected()
	{
		PlayerSession session = new PlayerSession();
		session.putTrackedOffer(0, completedBuy(3827));

		assertTrue(session.hasUncollectedBuyOfferForItem(3827));
		assertFalse(session.hasInFlightBuyOfferForItem(3827));
	}

	@Test
	public void noBuyOfferState_bothHelpersFalse()
	{
		PlayerSession session = new PlayerSession();

		assertFalse(session.hasInFlightBuyOfferForItem(3827));
		assertFalse(session.hasUncollectedBuyOfferForItem(3827));
	}

	@Test
	public void sellOffer_doesNotCountAsBuyState()
	{
		PlayerSession session = new PlayerSession();
		TrackedOffer sellOffer = new TrackedOffer(3827, "x", false, 100, 1, 5);
		session.putTrackedOffer(0, sellOffer);

		assertFalse(session.hasInFlightBuyOfferForItem(3827));
		assertFalse(session.hasUncollectedBuyOfferForItem(3827));
	}
}
