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
	public void loadIsSafeOnNullOrGarbage()
	{
		V9FlipStateStore store = new V9FlipStateStore(gson);
		store.load(null, 0L);
		assertTrue(store.snapshot().isEmpty());
		store.load("not json", 0L);
		assertTrue(store.snapshot().isEmpty());
	}
}
