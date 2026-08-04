package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferEventMapper;
import com.flipsmart.trading.OfferReconciler;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.trading.RoundTripLedger;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * Handles offline fill detection, offer state persistence, and collected item tracking.
 * Responsible for syncing GE offer state across login/logout cycles.
 */
@Slf4j
@Singleton
public class OfflineSyncService
{
	private static final int INVENTORY_CONTAINER_ID = 93;
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String UNKNOWN_RSN_FALLBACK = "unknown";
	private static final String PERSISTED_OFFERS_KEY_PREFIX = "persistedOffers_";
	private static final String PERSISTED_OFFERS_FALLBACK_KEY = "persistedOffers_lastSession";
	private static final String FLIP_FINDER_SOURCED_KEY_PREFIX = "flipFinderSourced_";
	private static final String ROUND_TRIP_LEDGER_KEY_PREFIX = "roundTripLedger_";
	private static final String LAST_KNOWN_RSN_KEY = "lastKnownRsn";
	// High-water offer id, per RSN. The store derives its counter from the records it imports, and
	// retention pruning drops the oldest terminal ones — so across a client restart the counter
	// fell back and could remint an id the backend still holds fills under. Persisting the mark
	// separately keeps it monotonic even when the records backing it are long gone.
	private static final String NEXT_OFFER_ID_KEY_PREFIX = "nextOfferId_";

	/**
	 * Config keys that held the collected set before it became derived state. Unset on the first
	 * sync of each session so upgrading clients do not leave orphaned blobs behind.
	 */
	private static final String[] LEGACY_COLLECTED_KEY_PREFIXES = {
		"collectedItems_", "collectedQuantities_", "collectedItemsSavedAt_"
	};

	/**
	 * Terminal (already-collected/cancelled) offer records this old are no longer carried across a
	 * reconcile. They keep the cost basis of a position the player still holds.
	 *
	 * <p>Matches the seven days the {@code collectedItems_<rsn>} blob used to retain for, because
	 * the collected set is now derived from these records rather than stored separately. A shorter
	 * window is fine while {@link RoundTripLedger} knows the held quantity — that exemption keeps a
	 * backing record alive regardless of age — but a client with no persisted ledger cold-starts
	 * from live unmatched buys only, so an already-collected holding gets {@code heldQuantity 0} and
	 * would age out. At 24 hours that silently dropped positions bought the previous day on the
	 * first login after upgrading.</p>
	 */
	static final long TERMINAL_HISTORY_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

	/** Hard ceiling on retained terminal records so the persisted blob stays bounded. */
	static final int MAX_RETAINED_TERMINAL_RECORDS = 300;

	/** Per-RSN key holding the high-water fill marks. */
	private static final String WATERMARKS_KEY_PREFIX = "fillWatermarks_";

	/**
	 * How long to wait for the GE snapshot before abandoning a sync — generous enough for a cold
	 * start, bounded so a login the player has already left cannot wait forever.
	 *
	 * <p>Deliberately wall-clock rather than a retry count. {@code ClientThread} re-runs a deferred
	 * task on every {@code invoke()}, which is per client callback and not per 600ms game tick, so
	 * a count of N retries buys an interval that varies with frame rate — measured at over 15 in a
	 * single second during QA. Only a deadline expresses the wait that actually matters.</p>
	 */
	static final long GE_SNAPSHOT_WAIT_MS = 30_000L;

	/** Set while a scheduled sync waits on the client thread, so a second cannot start. */
	private volatile boolean syncInFlight;

	/** Test seam: deterministic clock for the snapshot wait. */
	java.util.function.LongSupplier clock = System::currentTimeMillis;

	private final PlayerSession session;
	private final ConfigManager configManager;
	private final Gson gson;
	private final Client client;
	private final ClientThread clientThread;
	private final ActiveFlipTracker activeFlipTracker;
	private final GEHistoryService geHistoryService;
	private final OfferStore offerStore;
	private final ItemManager itemManager;
	private final RoundTripLedger roundTripLedger;

	/** Callback invoked after sync is complete (for scheduling post-sync tasks) */
	@Setter
	private Runnable onSyncComplete;

	@Inject
	public OfflineSyncService(
		PlayerSession session,
		ConfigManager configManager,
		Gson gson,
		Client client,
		ClientThread clientThread,
		ActiveFlipTracker activeFlipTracker,
		GEHistoryService geHistoryService,
		OfferStore offerStore,
		ItemManager itemManager,
		RoundTripLedger roundTripLedger)
	{
		this.session = session;
		this.configManager = configManager;
		this.gson = gson;
		this.client = client;
		this.clientThread = clientThread;
		this.activeFlipTracker = activeFlipTracker;
		this.geHistoryService = geHistoryService;
		this.offerStore = offerStore;
		this.itemManager = itemManager;
		this.roundTripLedger = roundTripLedger;
	}

	/**
	 * Restore the Flip Finder-sourced set for the current RSN on login, so the free-tier cap
	 * counts flips that were in progress before a client restart. Stale entries (items no
	 * longer an active flip) are pruned by {@code retainAndCountFlipFinderActive} on the first
	 * count, so no separate staleness handling is needed here.
	 */
	public void restoreFlipFinderSourcedItems()
	{
		Set<Integer> persisted = loadPersistedFlipFinderSourced();
		session.restoreFlipFinderSourced(persisted, System.currentTimeMillis());
		if (!persisted.isEmpty())
		{
			log.debug("Restored {} Flip Finder-sourced items for {}", persisted.size(), session.getRsn());
		}
	}

	private Set<Integer> loadPersistedFlipFinderSourced()
	{
		String key = session.getRsn() == null || session.getRsn().isEmpty()
			? FLIP_FINDER_SOURCED_KEY_PREFIX + UNKNOWN_RSN_FALLBACK
			: FLIP_FINDER_SOURCED_KEY_PREFIX + session.getRsn();
		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, key);
			if (json == null || json.isEmpty())
			{
				return new HashSet<>();
			}
			Type type = new TypeToken<List<Integer>>(){}.getType();
			List<Integer> items = gson.fromJson(json, type);
			return items != null ? new HashSet<>(items) : new HashSet<>();
		}
		catch (Exception e)
		{
			log.error("Failed to load Flip Finder-sourced set for {}: {}", session.getRsn(), e.getMessage());
			return new HashSet<>();
		}
	}

	/**
	 * Persist the current GE offer state to config for offline tracking.
	 * Called when the player logs out or plugin shuts down.
	 */
	public void persistOfferState()
	{
		String rsn = resolvePersistenceRsn();
		if (rsn == null)
		{
			log.debug("Skipping offer persistence: no resolvable RSN (logout window)");
			return;
		}

		String offersKey = PERSISTED_OFFERS_KEY_PREFIX + rsn;

		persistWatermarks(rsn);

		List<OfferRecord> offersToSave = offerStore.export();
		if (offersToSave.isEmpty())
		{
			// The store is transiently empty during the logout/hop transition. Unsetting
			// the persisted keys here would wipe still-valid saved offers; instead preserve
			// them. Stale entries (slots no longer live) are downgraded to terminal history
			// by OfferReconciler on restore, so leaving them is benign.
			log.debug("Offer store empty — preserving existing persisted offers for {}", rsn);
		}
		else
		{
			try
			{
				String json = gson.toJson(offersToSave);
				configManager.setConfiguration(CONFIG_GROUP, offersKey, json);
				configManager.setConfiguration(CONFIG_GROUP, PERSISTED_OFFERS_FALLBACK_KEY, json);
				log.debug("Persisted {} offer records for {} (offline sync)", offersToSave.size(), rsn);
			}
			catch (Exception e)
			{
				log.error("Failed to persist offer state for {}: {}", rsn, e.getMessage());
			}
		}

		// After the records, so a failure here can never cost us the blob itself.
		persistNextOfferId(rsn);

		// The collected set is NOT persisted. It is derived state — "buys I hold that no longer
		// occupy a slot" — which the persisted offer records plus live inventory already answer,
		// and rebuildCollectedItems reconstructs it on each sync. Storing it separately meant a
		// cache that drifted from its own source, which pruneStaleCollectedItems then existed to
		// repair; the repair in turn re-fired the GE History prompt for entries the reconciler had
		// deliberately suppressed as already-known history.

		// Persist the Flip Finder-sourced set so the free-tier cap survives a client restart.
		Set<Integer> sourced = session.getFlipFinderSourcedItems();
		if (sourced.isEmpty())
		{
			// Same don't-destroy-on-empty guard as collected items: a transient empty set
			// during the logout/hop window must not wipe the saved data.
			log.debug("Flip Finder-sourced set empty — preserving persisted set for {}", rsn);
		}
		else
		{
			try
			{
				configManager.setConfiguration(CONFIG_GROUP, FLIP_FINDER_SOURCED_KEY_PREFIX + rsn,
					gson.toJson(new ArrayList<>(sourced)));
			}
			catch (Exception e)
			{
				log.error("Failed to persist Flip Finder-sourced set for {}: {}", rsn, e.getMessage());
			}
		}

		persistLedgerState(rsn);
	}

	/**
	 * Persist the round-trip ledger's held-quantity/cycle state for {@code rsn} alongside
	 * the offer state, so a restart resumes mid-cycle instead of losing the id and
	 * splitting an in-progress position into a spurious new round trip.
	 */
	private void persistLedgerState(String rsn)
	{
		Map<Integer, RoundTripLedger.Entry> entries = roundTripLedger.export(rsn);
		if (entries.isEmpty())
		{
			return;
		}
		try
		{
			configManager.setConfiguration(CONFIG_GROUP, ROUND_TRIP_LEDGER_KEY_PREFIX + rsn, gson.toJson(entries));
		}
		catch (Exception e)
		{
			log.error("Failed to persist round-trip ledger for {}: {}", rsn, e.getMessage());
		}
	}

	/**
	 * Restore the round-trip ledger's per-item state for {@code rsn}. When nothing was ever
	 * persisted (first run of this feature on an account with an existing GE position), seed
	 * conservatively from the store's currently-live unmatched buy fills instead of starting
	 * blind at zero, so a restart doesn't collide a fresh cycle onto a stale in-flight buy.
	 */
	private void preloadLedgerState(String rsn)
	{
		if (importPersistedLedger(rsn))
		{
			return;
		}
		if (rsn == null || !roundTripLedger.isEmpty(rsn))
		{
			return;
		}
		roundTripLedger.seedColdStart(rsn, liveUnmatchedBuys());
	}

	/**
	 * Restore a saved ledger, reporting whether anything was imported.
	 *
	 * <p>Separated from the cold-start seeding around it because the two want opposite orderings:
	 * seeding reads live buys and so must follow the reconcile, while retention asks the ledger
	 * which items still back an open position and so must precede it. An empty ledger at that
	 * point reads every position as closed and ages out the very records the exemption exists to
	 * keep.</p>
	 */
	private boolean importPersistedLedger(String rsn)
	{
		if (rsn == null || !roundTripLedger.isEmpty(rsn))
		{
			return false;
		}
		String json = configManager.getConfiguration(CONFIG_GROUP, ROUND_TRIP_LEDGER_KEY_PREFIX + rsn);
		if (json != null && !json.isEmpty())
		{
			try
			{
				Type type = new TypeToken<Map<Integer, RoundTripLedger.Entry>>(){}.getType();
				Map<Integer, RoundTripLedger.Entry> entries = gson.fromJson(json, type);
				roundTripLedger.importState(rsn, entries);
				return true;
			}
			catch (Exception e)
			{
				log.debug("Ignoring unreadable persisted round-trip ledger for {} ({})", rsn, e.getMessage());
			}
		}
		return false;
	}

	/** Currently-live buy offers with at least one filled unit — a stale unmatched position. */
	private List<OfferRecord> liveUnmatchedBuys()
	{
		List<OfferRecord> out = new ArrayList<>();
		for (OfferRecord r : offerStore.liveOffers())
		{
			if (r.isBuy() && r.getFilledQuantity() > 0)
			{
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * Preload persisted offer state before the login burst fires, so the store
	 * holds the correct reconciled state and createWithPreservedTimestamps finds
	 * the original timestamp.
	 *
	 * Persisted {@link OfferRecord}s are reconciled against the live GE slots:
	 * reattached records are imported into the store (preserving offerId and
	 * timestamps so the burst event records only a delta), offline-collected
	 * records are imported as terminal history, and live slots with no persisted
	 * match are left for the normal event path to mint.
	 */
	public void preloadPersistedOffers()
	{
		List<OfferRecord> persistedRecords = loadPersistedOfferRecords();
		if (persistedRecords.isEmpty())
		{
			preloadLedgerState(resolvePersistenceRsn());
			return;
		}

		// Raise the marks before the records land. Seeding from the records covers a client that
		// has never persisted marks; merging the saved blob then adds anything the records no
		// longer show. Both only ever raise, so a stale blob cannot rewind this session.
		// Before the reconcile: retention asks the ledger which items still back an open position,
		// and on a cold start that answer lives in config rather than memory. Cold-start seeding
		// stays after the reconcile below, because it reads live buys from the reconciled store.
		importPersistedLedger(resolvePersistenceRsn());

		offerStore.watermarks().seedFrom(persistedRecords);
		offerStore.watermarks().mergeFrom(loadPersistedWatermarks());

		// Ahead of the reconcile, which mints ids for live slots no persisted record claims.
		offerStore.raiseNextOfferId(loadPersistedNextOfferId(resolvePersistenceRsn()));

		reconcilePersistedIntoStore(persistedRecords);
		// Runs after reconciliation so cold-start seeding (when there is no persisted
		// ledger at all) sees the just-reattached live buys, not a pre-reconcile snapshot.
		preloadLedgerState(resolvePersistenceRsn());

		log.debug("Preloaded {} persisted offer records into store for timestamp preservation",
			persistedRecords.size());
	}

	/**
	 * Reconcile persisted records against the live GE slots and apply the plan to
	 * the store: reattached records are imported with their slot restored; minted
	 * live slots are left to the normal event burst; offline-collected records are
	 * imported as terminal history so the store carries the full set.
	 */
	private void reconcilePersistedIntoStore(List<OfferRecord> persistedRecords)
	{
		long now = System.currentTimeMillis();
		List<OfferSignal> liveSlots = buildLiveSlotSignals();

		// The GE snapshot is not loaded yet (null, or present but every slot still EMPTY — e.g. the
		// LOGGED_IN tick after a world hop) while the store already holds live offers. A destructive
		// import would wipe them and no later step re-seeds the slot map, so preserve and let the
		// login GE burst (or the next readable pass) reconcile. On a genuine cold login the store is
		// empty, so this does not skip and the burst seeds it.
		if (liveSlots.isEmpty() && !offerStore.liveOffers().isEmpty())
		{
			return;
		}
		OfferReconciler.Plan plan = OfferReconciler.reconcile(persistedRecords, liveSlots, now);

		List<OfferRecord> toImport = new ArrayList<>();
		toImport.addAll(plan.reattached);
		// Terminalize offline-collected records to COLLECTED only when we can actually read the live
		// slots. At preload time the GE slots are usually NOT loaded yet (empty snapshot), and
		// reconciling against that would misclassify every still-live offer as offline-collected and
		// import a COLLECTED duplicate — growing the persisted blob every login. When the snapshot is
		// unreadable, defer classification to the +2s offline sync, which runs once slots are loaded.
		// When slots ARE readable, terminalizing keeps genuinely-gone records (often CANCELLED_PARTIAL,
		// non-terminal) from masquerading as live offers (auto-mode stale-prompt flap) and lets pruning
		// drop them. plan.reattached (still-live slots) is left untouched.
		if (!liveSlots.isEmpty())
		{
			for (OfferRecord collected : plan.offlineCollected)
			{
				toImport.add(collected.withState(OfferState.COLLECTED, now));
			}
		}
		else
		{
			// Deferring classification means carrying the records, not discarding them. They are
			// non-terminal, so retainRecentTerminalHistory below will not pick them up, and
			// importRecords replaces the store wholesale — leaving them out drops them, and the
			// persist that follows writes the truncated set back over the saved blob. That erased
			// the cost basis of a position the player still holds, and with the collected set now
			// derived from these records rather than stored separately, it erased the position too.
			toImport.addAll(plan.offlineCollected);
		}
		// A collected buy is the cost basis for the sell that follows it. The reconcile plan
		// carries only live and offline-collected records, so importing just those erased every
		// already-collected buy on each LOGGED_IN — which fires on every world hop, not only
		// login — leaving breakeven and profit unresolvable for the rest of the session. Carry
		// the recent terminal history across, bounded by age and count so the persisted blob
		// cannot grow without limit.
		List<OfferRecord> retainedHistory = retainRecentTerminalHistory(persistedRecords, now);
		toImport.addAll(retainedHistory);
		offerStore.importRecords(toImport);

		if (log.isDebugEnabled())
		{
			log.debug("Reconciled persisted offers into store: {} reattached, {} minted, {} offline-collected, {} terminal history retained (slots readable: {})",
				plan.reattached.size(), plan.minted.size(), plan.offlineCollected.size(),
				retainedHistory.size(), !liveSlots.isEmpty());
		}
	}

	/**
	 * Save the high-water fill marks. Written unconditionally: unlike the offer records, an empty
	 * mark set carries no risk of wiping useful state, because a restore only ever raises marks.
	 */
	private void persistWatermarks(String rsn)
	{
		try
		{
			configManager.setConfiguration(CONFIG_GROUP, WATERMARKS_KEY_PREFIX + rsn,
				gson.toJson(offerStore.watermarks().export()));
		}
		catch (Exception e)
		{
			log.error("Failed to persist fill watermarks for {}", rsn, e);
		}
	}

	/**
	 * Save the high-water offer id, but only when it would rise. The same don't-destroy-on-empty
	 * guard the offer records carry: the store is transiently empty during the logout/hop
	 * transition and its counter reads 1, and writing that would erase the only surviving record
	 * of ids whose offers have since aged out of retention — exactly what this mark exists to
	 * keep. Comparing rather than checking for an empty store keeps the guard independent of the
	 * order in which the counter is seeded.
	 */
	private void persistNextOfferId(String rsn)
	{
		try
		{
			long current = offerStore.nextOfferId();
			if (current > loadPersistedNextOfferId(rsn))
			{
				configManager.setConfiguration(CONFIG_GROUP, NEXT_OFFER_ID_KEY_PREFIX + rsn,
					Long.toString(current));
			}
		}
		catch (Exception e)
		{
			log.error("Failed to persist next offer id for {}", rsn, e);
		}
	}

	/** Saved high-water offer id for {@code rsn}, or 0 when none is stored or it is unreadable. */
	private long loadPersistedNextOfferId(String rsn)
	{
		String raw = rsn == null ? null : configManager.getConfiguration(CONFIG_GROUP, NEXT_OFFER_ID_KEY_PREFIX + rsn);
		try
		{
			// Absent, blank and corrupt all mean the same thing: no usable mark, so do not raise.
			return raw == null ? 0L : Long.parseLong(raw.trim());
		}
		catch (NumberFormatException e)
		{
			return 0L;
		}
	}

	/** Saved high-water marks for the current RSN, or an empty map when none are stored. */
	private Map<String, long[]> loadPersistedWatermarks()
	{
		String rsn = resolvePersistenceRsn();
		if (rsn == null)
		{
			return Collections.emptyMap();
		}
		String json = configManager.getConfiguration(CONFIG_GROUP, WATERMARKS_KEY_PREFIX + rsn);
		if (json == null || json.isEmpty())
		{
			return Collections.emptyMap();
		}
		try
		{
			Map<String, long[]> restored = gson.fromJson(json,
				new TypeToken<Map<String, long[]>>() { }.getType());
			return restored == null ? Collections.emptyMap() : restored;
		}
		catch (Exception e)
		{
			log.error("Failed to read fill watermarks for {}: {}", rsn, e.getMessage());
			return Collections.emptyMap();
		}
	}

	/**
	 * The most recent already-terminal persisted records, newest first, capped by
	 * {@link #TERMINAL_HISTORY_RETENTION_MS} and {@link #MAX_RETAINED_TERMINAL_RECORDS}.
	 * Non-terminal records are the reconciler's business and are never returned here.
	 */
	List<OfferRecord> retainRecentTerminalHistory(List<OfferRecord> persisted, long now)
	{
		String rsn = resolvePersistenceRsn();
		// Held-position records are exempt from both caps below, not just the age window: sorting
		// everything together and count-capping the merged list would still let a record backing an
		// open position fall past the cutoff behind 300 more-recent, unrelated terminal records.
		List<OfferRecord> heldPosition = new ArrayList<>();
		List<OfferRecord> windowed = new ArrayList<>();
		for (OfferRecord r : persisted)
		{
			if (r == null || !r.getState().isTerminal())
			{
				continue;
			}
			boolean backsOpenPosition = rsn != null && roundTripLedger.heldQuantity(rsn, r.getItemId()) > 0;
			if (backsOpenPosition)
			{
				heldPosition.add(r);
				continue;
			}
			if (now - r.getEffectiveLastActivityAtMillis() <= TERMINAL_HISTORY_RETENTION_MS)
			{
				windowed.add(r);
			}
		}
		if (windowed.size() > MAX_RETAINED_TERMINAL_RECORDS)
		{
			windowed.sort(java.util.Comparator
				.comparingLong(OfferRecord::getEffectiveLastActivityAtMillis).reversed());
			windowed = windowed.subList(0, MAX_RETAINED_TERMINAL_RECORDS);
		}
		List<OfferRecord> terminal = new ArrayList<>(heldPosition.size() + windowed.size());
		terminal.addAll(heldPosition);
		terminal.addAll(windowed);
		terminal.sort(java.util.Comparator
			.comparingLong(OfferRecord::getEffectiveLastActivityAtMillis).reversed());
		return terminal;
	}

	/** Reduce the live GE slots to {@link OfferSignal}s for reconciliation. */
	private List<OfferSignal> buildLiveSlotSignals()
	{
		List<OfferSignal> signals = new ArrayList<>();
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return signals;
		}
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			int itemId = offer.getItemId();
			String itemName = itemManager != null ? itemManager.getItemComposition(itemId).getName() : "";
			signals.add(OfferEventMapper.toSignal(
				slot,
				offer.getState(),
				itemId,
				itemName,
				offer.getTotalQuantity(),
				offer.getPrice(),
				offer.getQuantitySold(),
				offer.getSpent()));
		}
		return signals;
	}

	/**
	 * Sync fills that occurred while offline.
	 * Records current GE state to the backend.
	 */
	public void syncOfflineFills()
	{
		if (session.isOfflineSyncCompleted() || syncInFlight)
		{
			return;
		}
		syncInFlight = true;

		List<OfferRecord> persistedRecords = loadPersistedOfferRecords();

		// Live GE slots and item-name resolution must be read on the client thread — this method
		// is invoked from a Swing timer, and touching client/itemManager off-thread throws. The
		// offer array is null until GE data syncs, and reconciling against a null one reads every
		// held position as vanished, so wait for it across ticks rather than run once.
		long deadline = clock.getAsLong() + GE_SNAPSHOT_WAIT_MS;
		clientThread.invokeLater(() -> {
			if (client.getGrandExchangeOffers() == null)
			{
				if (clock.getAsLong() < deadline)
				{
					return false;
				}
				// Left unsynced on purpose: the next login clears offlineSyncCompleted and
				// schedules a fresh attempt, where marking it complete skipped the session.
				log.debug("GE snapshot unreadable after {}ms — leaving sync for next login",
					GE_SNAPSHOT_WAIT_MS);
				syncInFlight = false;
				return true;
			}
			try
			{
				reconcileOfflineFills(persistedRecords);
			}
			finally
			{
				syncInFlight = false;
			}
			return true;
		});
	}

	/**
	 * Drop a wait whose snapshot is never coming, so the login it belongs to cannot latch the
	 * service shut for the rest of the client's run.
	 */
	public void abandonPendingSync()
	{
		syncInFlight = false;
	}

	private void reconcileOfflineFills(List<OfferRecord> persistedRecords)
	{
		// Set here, not at schedule time: downstream readers take the flag to mean "this offer
		// state has been reconciled", which is not true until this method runs.
		session.setOfflineSyncCompleted(true);

		long now = System.currentTimeMillis();
		Map<Integer, OfferRecord> currentOffers = liveOffersBySlot();

		// Hand the snapshot to GEHistoryService so fully-completed offline trades
		// (whose live record no longer exists post-sync) can still be matched and
		// backfilled when the user opens the History tab.
		geHistoryService.setRecentlyPersistedOffers(persistedRecords);

		// Reconcile persisted records against live slots to determine which offers
		// completed or were cancelled while offline.
		List<OfferSignal> liveSlots = buildLiveSlotSignals();
		// liveSlots is the count that decides every classification below, and a zero-fill buy is
		// terminalised without being registered — so without it in the log there is no way to tell
		// a correct reattach from a silent misclassification after the fact.
		if (log.isDebugEnabled())
		{
			log.debug("Loaded {} persisted offers, comparing with {} store-live and {} client slots",
				persistedRecords.size(), currentOffers.size(), liveSlots.size());
		}
		OfferReconciler.Plan plan = OfferReconciler.reconcile(persistedRecords, liveSlots, now);

		// Restore original timestamps on still-live offers whose persisted record is older.
		for (OfferRecord reattached : plan.reattached)
		{
			OfferRecord live = findLiveRecordForSlot(reattached.getSlot(), currentOffers);
			if (live != null)
			{
				restoreTimestampIfOlder(live, reattached);
			}
		}

		// Rebuild the collected set before anything reads it. Driven by the whole persisted blob
		// rather than plan.offlineCollected, so a position the player has held across several
		// sessions — whose record long ago stopped being "recently vanished" — is still recovered.
		// Records that reattached are excluded: their fills are still sitting in the GE slot, not in
		// the player's inventory, so they have not been collected yet.
		rebuildCollectedItems(persistedRecords, plan.reattached);

		// An empty signal list here means "no live offers", not "unreadable" — the caller has
		// established the snapshot exists. A player logging in with everything already completed
		// looks exactly like this, and must still be offered.
		//
		// Each offline-collected record is an offer whose slot is gone on login. This is the
		// sole path that registers a History backfill.
		for (OfferRecord record : plan.offlineCollected)
		{
			if (!record.isBuy() || record.getFilledQuantity() > 0)
			{
				geHistoryService.registerOfflineFill(record.getItemId());
			}
		}
		// Offered once, so terminalise. A freshness cutoff could not tell "already offered" from
		// "never observed": a record's last-activity is when the plugin last SAW it change, and
		// an offline fill is by definition a change it did not see, so comparing that against a
		// marker written on every sync failed every genuine offline fill.
		terminaliseOffered(plan.offlineCollected, now);

		pruneStaleCollectedItems();
		clearLegacyCollectedKeys();
		persistOfferState();

		if (onSyncComplete != null)
		{
			onSyncComplete.run();
		}
	}

	/**
	 * Mark records we have just offered for backfill as collected — their slot is gone, which is
	 * what collected means. Terminal records are skipped by the reconciler, so this is what keeps
	 * the next login from offering the same record again.
	 */
	private void terminaliseOffered(List<OfferRecord> offered, long now)
	{
		if (offered.isEmpty())
		{
			return;
		}
		// Merged rather than replaced in place: the store holds these only if the preload managed
		// to import them, and the preload skips that when the GE slots were not yet readable. An
		// offered record that never reached the store would otherwise stay non-terminal in the
		// blob and be offered again on the next login.
		Map<Long, OfferRecord> byId = new LinkedHashMap<>();
		for (OfferRecord r : offerStore.export())
		{
			byId.put(r.getOfferId(), r);
		}
		for (OfferRecord r : offered)
		{
			OfferRecord current = byId.getOrDefault(r.getOfferId(), r);
			if (!current.getState().isTerminal())
			{
				byId.put(r.getOfferId(), current.withState(OfferState.COLLECTED, now));
			}
		}
		offerStore.importRecords(new ArrayList<>(byId.values()));
	}

	private OfferRecord findLiveRecordForSlot(Integer slot, Map<Integer, OfferRecord> currentOffers)
	{
		if (slot == null)
		{
			return null;
		}
		return currentOffers.get(slot);
	}

	/**
	 * Rebuild the collected set — items bought into inventory but not yet sold — from the persisted
	 * offer records and live inventory, replacing the config-backed restore this used to do.
	 *
	 * <p>Driven by the full persisted blob rather than the reconciler's offline-collected bucket, so
	 * a position held across several sessions is recovered even once its record has stopped being
	 * recently-vanished. Quantities are capped at the current inventory count, which makes a holding
	 * partly sold or used offline land at its true remainder instead of the figure recorded when it
	 * was first collected.</p>
	 *
	 * <p>Records in {@code reattached} are skipped: those offers still occupy a GE slot, so their
	 * fills are in the Exchange rather than the inventory and have not been collected. Without that
	 * exclusion a live partial-fill would contribute to the collected set whenever the player
	 * happened to hold units of the same item, stranding a phantom sell prompt.</p>
	 *
	 * <p>Adds only: a collect observed live earlier in this session keeps its entry, and
	 * {@link #pruneStaleCollectedItems} drops whatever has no backing. Must run on the client
	 * thread — it reads inventory.</p>
	 */
	private void rebuildCollectedItems(List<OfferRecord> persistedRecords, List<OfferRecord> reattached)
	{
		int rebuilt = 0;
		for (Map.Entry<Integer, Integer> entry
			: filledBuyQuantitiesByItem(excludingReattached(persistedRecords, reattached)).entrySet())
		{
			int inventory = inventoryCountOrZero(entry.getKey());
			if (inventory > 0)
			{
				int quantity = Math.min(inventory, entry.getValue());
				session.addCollectedItem(entry.getKey(), quantity);
				rebuilt++;
				// Per-item, because the aggregate count alone cannot show whether the inventory cap
				// actually applied — the whole point of deriving the quantity rather than replaying
				// the figure recorded at collect time.
				if (log.isDebugEnabled())
				{
					log.debug("Rebuilt collected item {} qty={} (inventory={}, persistedFilled={}) for {}",
						entry.getKey(), quantity, inventory, entry.getValue(), session.getRsn());
				}
			}
		}
		if (rebuilt > 0 && log.isDebugEnabled())
		{
			log.debug("Rebuilt {} collected item(s) for {} from persisted offers", rebuilt, session.getRsn());
		}
	}

	/**
	 * {@code records} minus the ones that reattached to a live GE slot. Those offers still occupy
	 * their slot, so their fills are in the Exchange rather than the player's inventory.
	 */
	private static List<OfferRecord> excludingReattached(List<OfferRecord> records, List<OfferRecord> reattached)
	{
		Set<Long> stillInSlot = new HashSet<>();
		for (OfferRecord r : reattached)
		{
			stillInSlot.add(r.getOfferId());
		}
		List<OfferRecord> out = new ArrayList<>(records.size());
		for (OfferRecord r : records)
		{
			if (r != null && !stillInSlot.contains(r.getOfferId()))
			{
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * Total quantity actually bought per item across {@code records}. Summed rather than replaced:
	 * a position built up over several offers of the same item is one holding, and taking only the
	 * last record's fill would under-report it.
	 */
	private static Map<Integer, Integer> filledBuyQuantitiesByItem(List<OfferRecord> records)
	{
		Map<Integer, Integer> filledByItem = new HashMap<>();
		for (OfferRecord r : records)
		{
			if (r != null && r.isBuy() && r.getFilledQuantity() > 0)
			{
				filledByItem.merge(r.getItemId(), r.getFilledQuantity(), Integer::sum);
			}
		}
		return filledByItem;
	}

	/** Remove the config keys the collected set used to be stored in. Idempotent. */
	private void clearLegacyCollectedKeys()
	{
		String rsn = resolvePersistenceRsn();
		for (String prefix : LEGACY_COLLECTED_KEY_PREFIXES)
		{
			configManager.unsetConfiguration(CONFIG_GROUP, prefix + UNKNOWN_RSN_FALLBACK);
			if (rsn != null)
			{
				configManager.unsetConfiguration(CONFIG_GROUP, prefix + rsn);
			}
		}
	}

	/**
	 * Drop collectedItems entries with no inventory, in-flight/uncollected buy,
	 * or active sell — they are "phantom" sell prompts from prior sessions (#451).
	 * Must be called from the client thread.
	 *
	 * <p>Deliberately silent: an entry losing its backing is not evidence of a fresh offline sell.
	 * Registering a History backfill here prompted for records the reconciler had already handled,
	 * on every login, because a set pruned to empty never cleared its own persisted blob. The
	 * genuine signal is the reconciler's offline-collected bucket, which is offered exactly once
	 * because offering terminalises the record.</p>
	 */
	int pruneStaleCollectedItems()
	{
		Set<Integer> currentIds = session.getCollectedItemIds();
		if (currentIds.isEmpty())
		{
			return 0;
		}

		List<Integer> toRemove = new ArrayList<>();
		for (int itemId : currentIds)
		{
			if (!isItemKnownPresent(itemId))
			{
				toRemove.add(itemId);
			}
		}
		for (int itemId : toRemove)
		{
			session.removeCollectedItem(itemId);
		}
		return toRemove.size();
	}


	/**
	 * Inventory count for the add-collected path: returns 0 when inventory is genuinely empty
	 * or unavailable. Unlike {@link #isItemKnownPresent} (which is conservative against pruning),
	 * the add path must err toward NOT adding, since the History backfill still fires regardless.
	 */
	private int inventoryCountOrZero(int itemId)
	{
		try
		{
			return Math.max(0, activeFlipTracker.getInventoryCountForItem(itemId));
		}
		catch (Exception | AssertionError e)
		{
			return 0;
		}
	}

	private boolean isItemKnownPresent(int itemId)
	{
		if (offerStore.hasLiveBuyOfferForItem(itemId)
			|| offerStore.hasActiveSellOfferForItem(itemId))
		{
			return true;
		}
		try
		{
			return activeFlipTracker.getInventoryCountForItem(itemId) > 0;
		}
		catch (Exception | AssertionError e)
		{
			// Conservative on inventory-unavailable: never wrongly prune.
			return true;
		}
	}

	private void restoreTimestampIfOlder(OfferRecord current, OfferRecord persisted)
	{
		if (persisted.getCreatedAtMillis() > 0
			&& persisted.getCreatedAtMillis() < current.getCreatedAtMillis())
		{
			offerStore.correctCreatedAt(current.getOfferId(), persisted.getCreatedAtMillis());
		}
		// The adjustment timer reads getEffectiveLastActivityAtMillis(), which prefers
		// lastActivityAtMillis — re-anchored to login on a fresh sighting — so restoring
		// createdAt alone leaves the offer's age reset across a relog.
		long persistedActivity = persisted.getEffectiveLastActivityAtMillis();
		if (persistedActivity > 0 && persistedActivity < current.getEffectiveLastActivityAtMillis())
		{
			offerStore.correctActivityAt(current.getOfferId(), persistedActivity);
		}
	}

	// =====================
	// Persistence Helpers
	// =====================

	/**
	 * Single resolution point for the RSN used to key persistence: live session
	 * RSN first, then the persisted last-known RSN. Returns null only when neither
	 * is available (e.g. mid-logout transition) so callers skip the write rather
	 * than emit a {@code null}-keyed config entry.
	 */
	String resolvePersistenceRsn()
	{
		String rsn = session.getRsn();
		if (rsn != null && !rsn.isEmpty())
		{
			return rsn;
		}
		String persisted = configManager.getConfiguration(CONFIG_GROUP, LAST_KNOWN_RSN_KEY);
		if (persisted != null && !persisted.isEmpty())
		{
			return persisted;
		}
		return null;
	}

	public String getPersistedOffersKey()
	{
		if (session.getRsn() == null || session.getRsn().isEmpty())
		{
			return PERSISTED_OFFERS_KEY_PREFIX + UNKNOWN_RSN_FALLBACK;
		}
		return PERSISTED_OFFERS_KEY_PREFIX + session.getRsn();
	}

	/**
	 * Load the raw persisted {@link OfferRecord} list (RSN key, then fallback /
	 * key-scan). This is the canonical persisted state restored into the store.
	 */
	List<OfferRecord> loadPersistedOfferRecords()
	{
		List<OfferRecord> records = tryLoadRecordsFromKey(getPersistedOffersKey());
		if (!records.isEmpty())
		{
			log.debug("Loaded {} persisted offer records for {}", records.size(), session.getRsn());
			return records;
		}
		return loadPersistedOfferRecordsByKeyScan();
	}

	/**
	 * Scan all config keys matching persistedOffers_* to find offer data
	 * when the RSN-specific key lookup failed (cold start, RSN not yet available).
	 */
	private List<OfferRecord> loadPersistedOfferRecordsByKeyScan()
	{
		try
		{
			List<OfferRecord> result = tryLoadRecordsFromKey(PERSISTED_OFFERS_FALLBACK_KEY);
			if (!result.isEmpty())
			{
				log.debug("Loaded {} offer records from fallback key", result.size());
				return result;
			}

			String prefix = CONFIG_GROUP + "." + PERSISTED_OFFERS_KEY_PREFIX;
			List<String> keys = configManager.getConfigurationKeys(prefix);
			for (String fullKey : keys)
			{
				String keyPart = fullKey.substring((CONFIG_GROUP + ".").length());
				if (keyPart.equals(PERSISTED_OFFERS_FALLBACK_KEY))
				{
					continue;
				}
				result = tryLoadRecordsFromKey(keyPart);
				if (!result.isEmpty())
				{
					log.debug("Loaded {} offer records via key scan (key: {})", result.size(), keyPart);
					return result;
				}
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load persisted offer records by key scan: {}", e.getMessage());
		}
		return new ArrayList<>();
	}

	private List<OfferRecord> tryLoadRecordsFromKey(String key)
	{
		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, key);
			if (json == null || json.isEmpty())
			{
				return new ArrayList<>();
			}
			Type type = new TypeToken<List<OfferRecord>>(){}.getType();
			List<OfferRecord> records = gson.fromJson(json, type);
			return records != null ? records : new ArrayList<>();
		}
		catch (Exception e)
		{
			// Expected one-time migration: the old persistence format stored a JSON
			// object, not a List<OfferRecord> array. Deserialization fails on first
			// login after upgrade — return empty without flooding the log at ERROR.
			if (log.isDebugEnabled())
			{
				log.debug("Ignoring legacy/unreadable persisted offers at key {} ({})", key, e.getMessage());
			}
			return new ArrayList<>();
		}
	}

	/** The store's current live offers indexed by GE slot. */
	private Map<Integer, OfferRecord> liveOffersBySlot()
	{
		Map<Integer, OfferRecord> out = new HashMap<>();
		for (OfferRecord r : offerStore.liveOffers())
		{
			if (r.getSlot() != null)
			{
				out.put(r.getSlot(), r);
			}
		}
		return out;
	}

}
