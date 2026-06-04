package com.flipsmart;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Pushes the player's current open Grand Exchange slot item IDs to the
 * FlipSmart backend so the "Import from RuneLite" button on the web Trade
 * Station page (issue #683 AC7) can read them back.
 *
 * Triggered every time the plugin observes a {@code GrandExchangeOfferChanged}
 * event. Debounced because individual slot fills produce a burst of events
 * (one per state transition) — we only need to push the final state of the
 * snapshot, not every intermediate one. Failed pushes are dropped silently;
 * this is best-effort cache warmth, not a critical signal.
 *
 * <p>Runs unconditionally — does not consult the {@code ff_trade_station}
 * feature flag that gates the web page. The plugin has no user-visible
 * Trade Station UI, so players see nothing whether the flag is on or off,
 * and pre-warming the cache means the web Import button works immediately
 * the moment the flag flips on for them (no "open RuneLite and wait for a
 * trade to register" experience on first use).
 */
@Slf4j
@Singleton
public class TradeStationSlotPushService
{
	private static final long DEBOUNCE_MS = 1_500L;

	private final Client client;
	private final FlipSmartApiClient apiClient;
	private final PlayerSession session;
	private final ScheduledExecutorService scheduler;

	private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
	private final AtomicReference<List<Integer>> lastPushed = new AtomicReference<>();

	@Inject
	public TradeStationSlotPushService(
		Client client,
		FlipSmartApiClient apiClient,
		PlayerSession session)
	{
		this.client = client;
		this.apiClient = apiClient;
		this.session = session;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "flipsmart-trade-station-push");
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Schedule a debounced push. Safe to call from any thread, including the
	 * client thread, because the actual GE-slot read is re-scheduled onto its
	 * own executor and we don't touch the client API there — only the slot
	 * snapshot the event handler captured.
	 *
	 * @param itemIds the snapshot of currently open slot item IDs at the time
	 *                of the event; pass {@code null} to read from the client
	 *                on the executor thread (only safe if called from the
	 *                client thread originally).
	 */
	public void scheduleSnapshotPush(List<Integer> itemIds)
	{
		List<Integer> snapshot = itemIds != null ? itemIds : readCurrentSlotIds();
		ScheduledFuture<?> existing = pending.get();
		if (existing != null)
		{
			existing.cancel(false);
		}
		ScheduledFuture<?> next = scheduler.schedule(
			() -> doPush(snapshot),
			DEBOUNCE_MS,
			TimeUnit.MILLISECONDS);
		pending.set(next);
	}

	/**
	 * Force an immediate push (no debounce). Intended for plugin shutdown so
	 * the cache reflects the last-known state.
	 */
	public void pushNow(List<Integer> itemIds)
	{
		List<Integer> snapshot = itemIds != null ? itemIds : readCurrentSlotIds();
		ScheduledFuture<?> existing = pending.getAndSet(null);
		if (existing != null)
		{
			existing.cancel(false);
		}
		scheduler.execute(() -> doPush(snapshot));
	}

	public void shutdown()
	{
		ScheduledFuture<?> existing = pending.getAndSet(null);
		if (existing != null)
		{
			existing.cancel(false);
		}
		scheduler.shutdownNow();
	}

	/**
	 * Read the current GE slot item IDs. MUST be called from the client thread.
	 * Returns a deduped list preserving the slot order the player sees.
	 */
	public List<Integer> readCurrentSlotIds()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return new ArrayList<>();
		}
		Set<Integer> seen = new LinkedHashSet<>();
		for (GrandExchangeOffer offer : offers)
		{
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			int id = offer.getItemId();
			if (id > 0)
			{
				seen.add(id);
			}
		}
		return new ArrayList<>(seen);
	}

	private void doPush(List<Integer> itemIds)
	{
		String rsn = session.getRsnSafe().orElse(null);
		if (rsn == null)
		{
			log.debug("Skipping trade-station slot push: no RSN");
			return;
		}
		List<Integer> previous = lastPushed.get();
		if (previous != null && previous.equals(itemIds))
		{
			log.debug("Skipping trade-station slot push: snapshot unchanged ({} items)",
				itemIds.size());
			return;
		}
		try
		{
			apiClient.pushTradeStationSlotsAsync(rsn, itemIds)
				.exceptionally(e ->
				{
					log.debug("Trade-station slot push failed: {}", e.getMessage());
					return false;
				});
			lastPushed.set(itemIds);
		}
		catch (RuntimeException e)
		{
			log.debug("Trade-station slot push threw: {}", e.getMessage());
		}
	}
}
