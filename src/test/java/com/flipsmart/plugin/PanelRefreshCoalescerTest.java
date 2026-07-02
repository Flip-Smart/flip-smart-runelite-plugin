package com.flipsmart.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PanelRefreshCoalescerTest
{
	/** Records scheduled one-shots so tests control time and firing explicitly. */
	private static final class FakeScheduler implements BiConsumer<Integer, Runnable>
	{
		final List<Integer> delays = new ArrayList<>();
		final AtomicReference<Runnable> pending = new AtomicReference<>();

		@Override
		public void accept(Integer delayMs, Runnable action)
		{
			delays.add(delayMs);
			pending.set(action);
		}

		void fire()
		{
			pending.getAndSet(null).run();
		}
	}

	private FakeScheduler scheduler;
	private long now;
	private int fullRefreshes;
	private int activeFlipsRefreshes;
	private PanelRefreshCoalescer coalescer;

	@Before
	public void setUp()
	{
		scheduler = new FakeScheduler();
		now = 0;
		fullRefreshes = 0;
		activeFlipsRefreshes = 0;
		coalescer = new PanelRefreshCoalescer(scheduler, () -> now,
			() -> fullRefreshes++, () -> activeFlipsRefreshes++);
	}

	@Test
	public void requestNeverRefreshesSynchronously()
	{
		coalescer.request(true);
		coalescer.request(false);

		assertEquals(0, fullRefreshes);
		assertEquals(0, activeFlipsRefreshes);
	}

	@Test
	public void singleFullRequestFiresOneFullRefreshAfterQuietWindow()
	{
		coalescer.request(true);
		assertEquals(1, scheduler.delays.size());
		assertEquals(PanelRefreshCoalescer.QUIET_WINDOW_MS, (int) scheduler.delays.get(0));

		now = PanelRefreshCoalescer.QUIET_WINDOW_MS;
		scheduler.fire();

		assertEquals(1, fullRefreshes);
		assertEquals(0, activeFlipsRefreshes);
		assertNull(scheduler.pending.get());
	}

	@Test
	public void burstCoalescesIntoOneRefresh()
	{
		coalescer.request(true);
		now = 1_000;
		coalescer.request(false);
		now = 2_000;
		coalescer.request(true);

		assertEquals(1, scheduler.delays.size());

		now = 5_000;
		scheduler.fire();
		assertEquals(0, fullRefreshes);
		assertNotNull(scheduler.pending.get());
		assertEquals(2_000, (int) scheduler.delays.get(1));

		now = 7_000;
		scheduler.fire();
		assertEquals(1, fullRefreshes);
		assertEquals(0, activeFlipsRefreshes);
	}

	@Test
	public void activeFlipsOnlyRequestFiresActiveFlipsRefresh()
	{
		coalescer.request(false);

		now = PanelRefreshCoalescer.QUIET_WINDOW_MS;
		scheduler.fire();

		assertEquals(0, fullRefreshes);
		assertEquals(1, activeFlipsRefreshes);
	}

	@Test
	public void fullRequestUpgradesPendingWindow()
	{
		coalescer.request(false);
		now = 1_000;
		coalescer.request(true);

		now = 6_000;
		scheduler.fire();

		assertEquals(1, fullRefreshes);
		assertEquals(0, activeFlipsRefreshes);
	}

	@Test
	public void continuousStreamFiresByMaxWait()
	{
		coalescer.request(false);
		now = 4_000;
		coalescer.request(false);

		now = 5_000;
		scheduler.fire();
		assertEquals(0, activeFlipsRefreshes);

		now = 8_000;
		coalescer.request(false);

		now = 9_000;
		scheduler.fire();
		assertEquals(0, activeFlipsRefreshes);
		assertEquals(1_000, (int) scheduler.delays.get(2));

		now = PanelRefreshCoalescer.MAX_WAIT_MS;
		scheduler.fire();
		assertEquals(1, activeFlipsRefreshes);
	}

	@Test
	public void windowReopensAfterFiring()
	{
		coalescer.request(true);
		now = 5_000;
		scheduler.fire();
		assertEquals(1, fullRefreshes);

		now = 20_000;
		coalescer.request(true);
		assertEquals(2, scheduler.delays.size());

		now = 25_000;
		scheduler.fire();
		assertEquals(2, fullRefreshes);
	}

	@Test
	public void fullFlagResetsBetweenWindows()
	{
		coalescer.request(true);
		now = 5_000;
		scheduler.fire();
		assertEquals(1, fullRefreshes);

		now = 20_000;
		coalescer.request(false);
		now = 25_000;
		scheduler.fire();

		assertEquals(1, fullRefreshes);
		assertEquals(1, activeFlipsRefreshes);
	}
}
