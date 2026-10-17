package com.flipsmart.v9;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V9FlipStateStoreTest
{
	private final Gson gson = new Gson();

	private V9FlipState state(int itemId)
	{
		V9FlipState s = new V9FlipState();
		s.setItemId(itemId);
		s.setTimeframe("2h");
		s.setBuyPrice(1000);
		s.setTotalQty(50);
		s.setRemainingQty(50);
		s.setOriginalTarget(1100);
		s.setScenario("A");
		s.setScenarioBMid(null);
		s.setLadderRung(0);
		return s;
	}

	@Test
	public void putGetRemoveRoundTrips()
	{
		V9FlipStateStore store = new V9FlipStateStore(gson);
		store.put(state(42));
		assertEquals(1000, store.get(42).getBuyPrice());
		store.remove(42);
		assertNull(store.get(42));
	}

	@Test
	public void serializeThenLoadPreservesFreshEntries()
	{
		V9FlipStateStore a = new V9FlipStateStore(gson);
		a.put(state(42));
		a.put(state(99));
		long now = 1_000_000L;
		String json = a.serialize(now);

		V9FlipStateStore b = new V9FlipStateStore(gson);
		b.load(json, now + 1000L);
		assertEquals(2, b.snapshot().size());
		assertEquals("A", b.get(42).getScenario());
		assertEquals("2h", b.get(99).getTimeframe());
	}

	@Test
	public void loadDropsEntriesOlderThanMaxAge()
	{
		V9FlipStateStore a = new V9FlipStateStore(gson);
		a.put(state(42));
		long saved = 1_000_000L;
		String json = a.serialize(saved);

		V9FlipStateStore b = new V9FlipStateStore(gson);
		b.load(json, saved + V9FlipStateStore.MAX_AGE_MS + 1L);
		assertTrue(b.snapshot().isEmpty());
	}

	@Test
	public void freezeShiftsLadderAnchorsByOfflineGap()
	{
		V9FlipState s = state(42);
		s.setListingTimestampMs(1_000_000L);
		s.setLadder1ResolvedAtMs(1_100_000L);
		s.setSavedAtMillis(2_000_000L);
		V9FlipStateStore.freezeLadderAcrossOffline(s, 2_500_000L); // 500k ms offline
		assertEquals(1_500_000L, s.getListingTimestampMs());
		assertEquals(1_600_000L, s.getLadder1ResolvedAtMs());
	}

	@Test
	public void freezeNoOpWhenNotListedOrNoGap()
	{
		V9FlipState notListed = state(1);
		notListed.setSavedAtMillis(1_000_000L);
		V9FlipStateStore.freezeLadderAcrossOffline(notListed, 2_000_000L);
		assertEquals(0L, notListed.getListingTimestampMs());

		V9FlipState skew = state(2);
		skew.setListingTimestampMs(1_000_000L);
		skew.setSavedAtMillis(5_000_000L);
		V9FlipStateStore.freezeLadderAcrossOffline(skew, 4_000_000L); // now < saved
		assertEquals(1_000_000L, skew.getListingTimestampMs());
	}

	@Test
	public void loadFreezesLadderSoOfflineTimeDoesNotCount()
	{
		// Listed, 10 min online, then logout; reload after a 2h offline gap. The ladder clock
		// should see only the 10 min that elapsed while online, not the 2h away (#1309).
		V9FlipStateStore a = new V9FlipStateStore(gson);
		V9FlipState s = state(42);
		long listed = 1_000_000L;
		s.setListingTimestampMs(listed);
		a.put(s);
		long saved = listed + 600_000L; // 10 min online before logout
		String json = a.serialize(saved);

		V9FlipStateStore b = new V9FlipStateStore(gson);
		long now = saved + 2L * 60 * 60 * 1000; // 2h offline
		b.load(json, now);
		assertEquals(600_000L, now - b.get(42).getListingTimestampMs());
	}

	@Test
	public void loadIsSafeOnNullOrGarbage()
	{
		V9FlipStateStore store = new V9FlipStateStore(gson);
		store.load(null, 0L);
		assertTrue(store.snapshot().isEmpty());
		store.load("not json", 0L);
		assertTrue(store.snapshot().isEmpty());
	}
}
