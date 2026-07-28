package com.flipsmart;

import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Contract for {@link AutoRecommendService#resyncAfterExternalOverlay()} (#1114): resyncing when
 * Exit Trades hands the overlay back must never surface an action while auto-mode is off. The
 * gate-clear + re-resolve while active requires a fully-wired queue and is covered by manual QA,
 * same as the {@code invokeFocusCallback} gate.
 */
public class AutoRecommendResyncTest
{
	private AutoRecommendService service;
	private final AtomicInteger focusCallbacks = new AtomicInteger(0);

	@Before
	public void setUp()
	{
		FlipSmartConfig config = mock(FlipSmartConfig.class);
		FlipSmartPlugin plugin = mock(FlipSmartPlugin.class);
		service = new AutoRecommendService(config, plugin, new OfferStore());
		service.setOnFocusChanged(f -> focusCallbacks.incrementAndGet());
	}

	@Test
	public void resyncIsSafeNoOpWhenAutoModeInactive()
	{
		// active defaults to false (auto-mode off). Resync must not re-resolve or surface anything.
		service.resyncAfterExternalOverlay();
		assertEquals(0, focusCallbacks.get());
	}

	@Test
	public void resyncIsIdempotentWhenInactive()
	{
		service.resyncAfterExternalOverlay();
		service.resyncAfterExternalOverlay();
		assertEquals(0, focusCallbacks.get());
	}
}
