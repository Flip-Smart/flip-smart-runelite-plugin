package com.flipsmart.plugin;

import com.flipsmart.ActiveFlipTracker;
import com.flipsmart.AutoRecommendService;
import com.flipsmart.FlipFinderPanel;
import com.flipsmart.FlipSmartPlugin;
import com.flipsmart.GEHistoryService;
import com.flipsmart.GrandExchangeTracker;
import com.flipsmart.OfflineSyncService;
import com.flipsmart.trading.OfferEvent;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.domain.offer.OfferSignal;

import javax.swing.SwingUtilities;

import net.runelite.api.GrandExchangeOfferState;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ServiceWiringRefreshRoutingTest
{
	private FlipSmartPlugin plugin;
	private FlipFinderPanel panel;
	private GrandExchangeTracker tracker;
	private PanelRefreshCoalescer coalescer;
	private OfferStore offerStore;

	@Before
	public void setUp()
	{
		plugin = mock(FlipSmartPlugin.class);
		panel = mock(FlipFinderPanel.class);
		tracker = mock(GrandExchangeTracker.class);
		coalescer = mock(PanelRefreshCoalescer.class);
		offerStore = new OfferStore();
		when(plugin.getFlipFinderPanel()).thenReturn(panel);
	}

	@Test
	public void trackerPanelRefreshRoutesThroughCoalescerAsFull()
	{
		new ServiceWiring().wireGrandExchangeTrackerCallbacks(plugin, tracker,
			mock(AutoRecommendService.class), mock(GEHistoryService.class), offerStore, coalescer);

		ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
		verify(tracker).setOnPanelRefresh(captor.capture());
		verify(coalescer, never()).request(true);

		captor.getValue().run();

		verify(coalescer).request(true);
		verify(panel, never()).refresh();
	}

	@Test
	public void trackerActiveFlipsRefreshRoutesThroughCoalescerAsPartial()
	{
		new ServiceWiring().wireGrandExchangeTrackerCallbacks(plugin, tracker,
			mock(AutoRecommendService.class), mock(GEHistoryService.class), offerStore, coalescer);

		ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
		verify(tracker).setOnActiveFlipsRefresh(captor.capture());

		captor.getValue().run();

		verify(coalescer).request(false);
		verify(panel).reevaluateSlotLimitDisplay();
		verify(panel, never()).refreshActiveFlips();
	}

	@Test
	public void activeFlipTrackerCallbacksRouteThroughCoalescer()
	{
		ActiveFlipTracker activeFlipTracker = mock(ActiveFlipTracker.class);
		new ServiceWiring().wireServiceCallbacks(plugin, mock(OfflineSyncService.class),
			activeFlipTracker, coalescer);

		ArgumentCaptor<Runnable> full = ArgumentCaptor.forClass(Runnable.class);
		ArgumentCaptor<Runnable> partial = ArgumentCaptor.forClass(Runnable.class);
		verify(activeFlipTracker).setOnPanelRefreshNeeded(full.capture());
		verify(activeFlipTracker).setOnActiveFlipsRefreshNeeded(partial.capture());

		full.getValue().run();
		partial.getValue().run();

		verify(coalescer).request(true);
		verify(coalescer).request(false);
	}

	@Test
	public void offerStoreEventTriggersLocalPanelUpdateWithoutCoalescer() throws Exception
	{
		new ServiceWiring().wireGrandExchangeTrackerCallbacks(plugin, tracker,
			mock(AutoRecommendService.class), mock(GEHistoryService.class), offerStore, coalescer);

		offerStore.apply(new OfferSignal(0, GrandExchangeOfferState.BUYING, 42, "item42", 10, 100, 0, 0L), 1_000L);
		SwingUtilities.invokeAndWait(() -> { });

		verify(panel).applyLocalOfferEvent(any(OfferEvent.class));
		verify(coalescer, never()).request(true);
		verify(coalescer, never()).request(false);
	}
}
