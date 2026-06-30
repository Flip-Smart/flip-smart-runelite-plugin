package com.flipsmart;

import com.flipsmart.api.dto.ActiveFlipsResponse;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the deferred sell-focus tick-retry in {@link GrandExchangeTracker}, the
 * settle-race fix from #156. When {@code overrideFocusForSell} reports the local
 * collect/inventory/session state has not settled ({@code UNAVAILABLE}), the
 * tracker retries once per game tick for a bounded budget before falling back to
 * the backend active-flip lookup — the fallback restored in {@code 3c6e53d}.
 */
public class SellFocusRetryTest
{
	private static final int ITEM = 4151;
	private static final String RSN = "TestPlayer";
	private static final int RETRY_BUDGET_TICKS = 6;

	private PlayerSession session;
	private FlipSmartApiClient apiClient;
	private ActiveFlipTracker activeFlipTracker;
	private ItemManager itemManager;
	private TradeActivityLog tradeActivityLog;
	private AutoRecommendService autoRecommendService;
	private GrandExchangeTracker tracker;

	@Before
	public void setUp()
	{
		session = new PlayerSession();
		session.setRsn(RSN);
		apiClient = mock(FlipSmartApiClient.class);
		activeFlipTracker = mock(ActiveFlipTracker.class);
		itemManager = mock(ItemManager.class);
		tradeActivityLog = mock(TradeActivityLog.class);
		autoRecommendService = mock(AutoRecommendService.class);

		when(activeFlipTracker.getInventoryCountForItem(anyInt())).thenReturn(0);
		when(apiClient.getActiveFlipsAsync(anyString()))
			.thenReturn(CompletableFuture.<ActiveFlipsResponse>completedFuture(null));
		when(autoRecommendService.isActive()).thenReturn(true);

		tracker = new GrandExchangeTracker(
			session, apiClient, activeFlipTracker, itemManager, tradeActivityLog);
		tracker.setAutoRecommendService(autoRecommendService);
		tracker.setRsnSupplier(() -> Optional.of(RSN));
	}

	@Test
	public void settlesToFocusedWithinBudgetWithoutApiFallback()
	{
		// First attempt unsettled, the retry tick resolves it.
		when(autoRecommendService.overrideFocusForSell(anyInt(), anyString()))
			.thenReturn(AutoRecommendService.SellFocusResult.UNAVAILABLE,
				AutoRecommendService.SellFocusResult.FOCUSED);

		tracker.autoFocusOnActiveFlip(ITEM);   // UNAVAILABLE → schedules a retry
		tracker.retryPendingSellFocusTick();   // FOCUSED → settles, clears pending

		verify(apiClient, never()).getActiveFlipsAsync(anyString());

		// Pending is cleared: a further tick must be a no-op (no extra resolve attempt).
		tracker.retryPendingSellFocusTick();
		verify(autoRecommendService, times(2)).overrideFocusForSell(anyInt(), anyString());
	}

	@Test
	public void fallsBackToApiLookupAfterBudgetExhausted()
	{
		when(autoRecommendService.overrideFocusForSell(anyInt(), anyString()))
			.thenReturn(AutoRecommendService.SellFocusResult.UNAVAILABLE);

		tracker.autoFocusOnActiveFlip(ITEM);   // UNAVAILABLE → budget = 6 ticks

		// Exhaust all but the final tick — still no fallback.
		for (int i = 0; i < RETRY_BUDGET_TICKS - 1; i++)
		{
			tracker.retryPendingSellFocusTick();
		}
		verify(apiClient, never()).getActiveFlipsAsync(anyString());

		// The budget-exhausting tick falls back to the backend active-flip lookup once.
		tracker.retryPendingSellFocusTick();
		verify(apiClient, times(1)).getActiveFlipsAsync(anyString());

		// Pending cleared after fallback — no second API call on subsequent ticks.
		tracker.retryPendingSellFocusTick();
		verify(apiClient, times(1)).getActiveFlipsAsync(anyString());
	}

	@Test
	public void clearsPendingRetryWhenAutoRecommendTurnedOff()
	{
		when(autoRecommendService.overrideFocusForSell(anyInt(), anyString()))
			.thenReturn(AutoRecommendService.SellFocusResult.UNAVAILABLE);

		tracker.autoFocusOnActiveFlip(ITEM);   // schedules a retry while active

		when(autoRecommendService.isActive()).thenReturn(false);
		tracker.retryPendingSellFocusTick();   // auto-recommend off → drop the pending retry

		verify(apiClient, never()).getActiveFlipsAsync(anyString());
		// Only the initial autoFocus attempt ran; the off-tick must not re-resolve.
		verify(autoRecommendService, times(1)).overrideFocusForSell(anyInt(), anyString());

		// Pending was actually cleared: re-enabling and ticking again is a no-op.
		when(autoRecommendService.isActive()).thenReturn(true);
		tracker.retryPendingSellFocusTick();
		verify(autoRecommendService, times(1)).overrideFocusForSell(anyInt(), anyString());
	}
}
