package com.flipsmart.exit;

import com.flipsmart.FocusedFlip;
import com.flipsmart.api.dto.Dtos.WikiPrice;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns an in-progress Exit Trades run: a mode plus an ordered per-slot queue the player
 * unwinds one slot at a time. Re-targets the existing sell-prompt system; never drives the GE.
 */
@Slf4j
public final class ExitTradesController
{
	private static final int GE_SLOTS = 8;
	private static final int NO_SLOT = -1; // slot-less target: stock held in inventory, no live GE offer

	private final OfferStore offerStore;
	@Setter
	private IntUnaryOperator buyBasisSupplier = itemId -> 0;
	@Setter
	private IntUnaryOperator backendSellPriceSupplier = itemId -> 0;
	@Setter
	private IntFunction<WikiPrice> wikiPriceSupplier = itemId -> null;
	@Setter
	private IntUnaryOperator inventoryQtySupplier = itemId -> 0;
	@Setter
	private IntFunction<String> itemNameSupplier = itemId -> "";
	@Setter
	private Supplier<List<Integer>> heldSellItemIdsSupplier = Collections::emptyList;
	@Setter
	private Consumer<FocusedFlip> onFocusTarget = f -> { };
	@Setter
	private BiConsumer<String, Integer> onStatusMessage = (m, id) -> { };
	@Setter
	private IntConsumer onHighlightSlotForItem = itemId -> { };
	@Setter
	private Runnable onClearHighlights = () -> { };
	@Setter
	private Runnable onComplete = () -> { };

	// active/mode are read across threads — the client thread (resolver, overlay focus), the Swing
	// EDT (cog button, panel focus guard), and the render thread (isModifyingActiveOffer) — so the
	// buy-suppression / ownsOverlay checks see a consistent value without a one-tick stale read.
	@Getter
	private volatile boolean active;
	@Getter
	private volatile ExitTradesMode mode;
	private volatile boolean modifyingActiveOffer;
	private final List<ExitSlotTarget> targets = new ArrayList<>();

	public ExitTradesController(OfferStore offerStore)
	{
		this.offerStore = offerStore;
	}





	public void start(ExitTradesMode mode)
	{
		this.mode = mode;
		targets.clear();
		modifyingActiveOffer = false;
		if (mode == ExitTradesMode.REGULAR)
		{
			// Latched sell-only: no queue, no re-pricing. Just suppress buys and let the normal
			// sell flow run until the player toggles back to Buy/Sell mode.
			active = true;
			log.debug("Exit Trades started: mode=REGULAR (buys suppressed, normal sell flow)");
			return;
		}
		for (int slot = 0; slot < GE_SLOTS; slot++)
		{
			OfferRecord r = offerStore.bySlot(slot);
			if (r == null)
			{
				continue;
			}
			int basis = buyBasisSupplier.applyAsInt(r.getItemId());
			targets.add(r.isBuy()
				? ExitSlotTarget.forBuy(slot, r.getItemId(), r.getItemName(), basis)
				: ExitSlotTarget.sell(slot, r.getItemId(), r.getItemName(), basis));
		}
		int slotTargets = targets.size();
		seedHeldInventory();
		active = !targets.isEmpty();
		log.debug("Exit Trades started: mode={} slots={} inventoryHeld={}",
			mode, slotTargets, targets.size() - slotTargets);
	}

	/**
	 * Seed sell targets for stock already collected into inventory with no live GE slot.
	 * Without this a fully-collected position builds an empty slot queue and silently no-ops, so the
	 * exit prompt never fires until an unrelated action advances the queue.
	 */
	private void seedHeldInventory()
	{
		Set<Integer> alreadyQueued = new HashSet<>();
		for (ExitSlotTarget t : targets)
		{
			alreadyQueued.add(t.getItemId());
		}
		for (int itemId : heldSellItemIdsSupplier.get())
		{
			if (!alreadyQueued.add(itemId))
			{
				continue; // already covered by a live slot (or a duplicate id)
			}
			int held = inventoryQtySupplier.applyAsInt(itemId);
			if (held <= 0)
			{
				continue; // collected set is stale — nothing actually in inventory
			}
			int basis = buyBasisSupplier.applyAsInt(itemId);
			ExitSlotTarget target = ExitSlotTarget.sell(NO_SLOT, itemId, itemNameSupplier.apply(itemId), basis);
			target.setPhase(ExitPhase.CANCELLED_HOLDING);
			target.setHeldQuantity(held);
			targets.add(target);
			log.debug("Exit Trades: seeded inventory-held sell target item {} qty {}", itemId, held);
		}
	}


	/**
	 * Whether the exit flow owns the trading overlay (queue-driven breakeven/instant). REGULAR mode
	 * is active for buy-suppression but hands the overlay to the normal sell flow, so it returns false.
	 */
	public boolean ownsOverlay()
	{
		return active && mode != ExitTradesMode.REGULAR;
	}


	public List<ExitSlotTarget> getTargets()
	{
		return Collections.unmodifiableList(targets);
	}

	public ExitSlotTarget currentTarget()
	{
		for (ExitSlotTarget t : targets)
		{
			if (t.getPhase() != ExitPhase.DONE)
			{
				return t;
			}
		}
		return null;
	}

	public void clear()
	{
		active = false;
		mode = null;
		modifyingActiveOffer = false;
		targets.clear();
	}

	/**
	 * True while the current prompt re-lists an offer that still occupies its own slot. The overlay
	 * uses this to suppress the "click SELL on an empty slot" glow, which only applies to placing
	 * freshly-collected stock — not to modifying an offer that already has a slot highlighted.
	 */
	public boolean isModifyingActiveOffer()
	{
		return active && modifyingActiveOffer;
	}

	/**
	 * Advance the queue for {@code record}. Pure: only mutates target phases. Returns true
	 * when a phase changed so the caller can re-surface the next prompt (see the wiring layer).
	 */
	public boolean onOfferChanged(OfferRecord record)
	{
		if (!active || record == null)
		{
			return false;
		}
		for (ExitSlotTarget t : targets)
		{
			if (t.getItemId() != record.getItemId())
			{
				continue;
			}
			// A record that still occupies a slot belongs to the target in that exact slot — this
			// disambiguates the same item listed across two slots. A fresh re-list (NEW) can land in
			// any slot, and terminal records (COLLECTED/CANCELLED_EMPTY) carry no slot, so both fall
			// back to item-only matching.
			if (record.getSlot() != null && !isFreshSell(record)
				&& record.getSlot().intValue() != t.getSlot())
			{
				continue;
			}
			// A finished sell (re-listed at the exit price, or sold on its own) closes a sell target.
			if (t.getPhase() == ExitPhase.PENDING && !t.isBuy() && isFreshSell(record))
			{
				t.setPhase(ExitPhase.DONE);
				log.debug("Exit Trades: slot {} item {} sell re-listed -> DONE", t.getSlot(), t.getItemId());
				return true;
			}
			// Held stock re-listed closes a holding target.
			if (t.getPhase() == ExitPhase.CANCELLED_HOLDING && isFreshSell(record))
			{
				t.setPhase(ExitPhase.DONE);
				log.debug("Exit Trades: slot {} item {} held stock re-listed -> DONE", t.getSlot(), t.getItemId());
				return true;
			}
			// Buy lifecycle: cancel/fill -> collect -> hold. Always re-surface so the prompt tracks it.
			if (t.isBuy() && record.isBuy()
				&& (t.getPhase() == ExitPhase.PENDING_CANCEL || t.getPhase() == ExitPhase.AWAITING_COLLECT))
			{
				ExitPhase next = nextBuyPhase(t.getPhase(), record);
				if (next == ExitPhase.CANCELLED_HOLDING)
				{
					t.setHeldQuantity(record.getFilledQuantity()); // survive the inventory-update lag
				}
				if (next != t.getPhase())
				{
					t.setPhase(next);
					log.debug("Exit Trades: slot {} item {} buy {} (filled {}) -> {}",
						t.getSlot(), t.getItemId(), record.getState(), record.getFilledQuantity(), next);
				}
				return true;
			}
			// A sell that fully sold on its own needs its profit collected before the slot frees.
			if (!t.isBuy() && !record.isBuy()
				&& (t.getPhase() == ExitPhase.PENDING || t.getPhase() == ExitPhase.AWAITING_COLLECT))
			{
				if (record.getState() == OfferState.FILLED && t.getPhase() != ExitPhase.AWAITING_COLLECT)
				{
					t.setPhase(ExitPhase.AWAITING_COLLECT);
					log.debug("Exit Trades: slot {} item {} sell filled -> AWAITING_COLLECT",
						t.getSlot(), t.getItemId());
					return true;
				}
				if (record.getState() == OfferState.COLLECTED)
				{
					if (t.getHeldQuantity() > 0)
					{
						// Cancelled-sell stock now in inventory: re-list it rather than finishing.
						t.setPhase(ExitPhase.CANCELLED_HOLDING);
						log.debug("Exit Trades: slot {} item {} cancelled-sell stock collected -> CANCELLED_HOLDING",
							t.getSlot(), t.getItemId());
						return true;
					}
					t.setPhase(ExitPhase.DONE);
					log.debug("Exit Trades: slot {} item {} sell profit collected -> DONE",
						t.getSlot(), t.getItemId());
					return true;
				}
				if (record.getState() == OfferState.CANCELLED_EMPTY
					|| record.getState() == OfferState.CANCELLED_PARTIAL)
				{
					// Player cancelled the sell to re-price it. Unsold stock goes to the GE collection
					// box and must be collected before it can be re-listed (confirmed in-game), so mirror
					// the buy path: await the collect, then re-list. Capturing the unsold quantity also
					// distinguishes this from a sold-out sell when the collect lands.
					if (t.getPhase() != ExitPhase.AWAITING_COLLECT)
					{
						int unsold = Math.max(0, record.getTotalQuantity() - record.getFilledQuantity());
						t.setHeldQuantity(unsold);
						t.setPhase(ExitPhase.AWAITING_COLLECT);
						log.debug("Exit Trades: slot {} item {} sell cancelled — {} unsold -> AWAITING_COLLECT",
							t.getSlot(), t.getItemId(), unsold);
					}
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isFreshSell(OfferRecord r)
	{
		return !r.isBuy() && r.getState() == OfferState.NEW;
	}

	/** Next phase for a buy target as its offer progresses cancel/fill -> collect -> hold. */
	private static ExitPhase nextBuyPhase(ExitPhase current, OfferRecord r)
	{
		switch (r.getState())
		{
			case CANCELLED_EMPTY:
				return ExitPhase.DONE; // cancelled with nothing bought
			case COLLECTED:
				return r.getFilledQuantity() > 0 ? ExitPhase.CANCELLED_HOLDING : ExitPhase.DONE;
			case FILLED:
			case CANCELLED_PARTIAL:
				return ExitPhase.AWAITING_COLLECT; // bought items sit in the slot awaiting collection
			default:
				return current; // NEW / PARTIAL_FILL: still filling, keep prompting cancel
		}
	}

	public int actedCount()
	{
		int n = 0;
		for (ExitSlotTarget t : targets)
		{
			ExitPhase p = t.getPhase();
			if (p == ExitPhase.DONE || p == ExitPhase.CANCELLED_HOLDING || p == ExitPhase.AWAITING_COLLECT)
			{
				n++;
			}
		}
		return n;
	}

	public void surfaceCurrent()
	{
		if (!active || mode == ExitTradesMode.REGULAR)
		{
			return; // REGULAR leaves the overlay to the normal sell flow
		}
		ExitSlotTarget t = currentTarget();
		if (t == null)
		{
			// Run finished: deactivate but keep DONE targets so a logout snapshot correctly
			// resolves to "nothing to resume" (getStateForPersistence returns null when inactive).
			active = false;
			onClearHighlights.run();
			log.debug("Exit Trades: run complete");
			onComplete.run();
			return;
		}

		modifyingActiveOffer = false; // set true only when re-listing an offer that owns its slot

		// Re-validate against live state: the snapshot is taken at start(), but by the time we
		// reach a slot the offer may have sold, been cancelled, or been collected on its own.
		OfferRecord live = offerStore.bySlot(t.getSlot());
		boolean liveMatches = live != null && live.getItemId() == t.getItemId();

		// Buy still occupying its slot: cancel it (still filling) or collect it (filled / cancelled-partial).
		if (t.isBuy() && liveMatches
			&& (t.getPhase() == ExitPhase.PENDING_CANCEL || t.getPhase() == ExitPhase.AWAITING_COLLECT))
		{
			onFocusTarget.accept(null);
			onHighlightSlotForItem.accept(t.getItemId());
			boolean collect = t.getPhase() == ExitPhase.AWAITING_COLLECT
				|| live.getState() == OfferState.FILLED
				|| live.getState() == OfferState.CANCELLED_PARTIAL
				|| live.getFilledQuantity() >= live.getTotalQuantity();
			log.debug("Exit Trades: prompt {} slot {} item {}",
				collect ? "collect" : "cancel", t.getSlot(), t.getItemId());
			onStatusMessage.accept("Exit Trades: " + (collect ? "collect the bought " : "cancel the buy offer for ")
				+ t.getItemName(), t.getItemId());
			return;
		}

		// Sell offer still occupying its slot.
		if (!t.isBuy() && liveMatches)
		{
			boolean sold = t.getPhase() == ExitPhase.AWAITING_COLLECT
				|| live.getState() == OfferState.FILLED
				|| live.getFilledQuantity() >= live.getTotalQuantity();
			if (sold)
			{
				// Sold on its own (collect the profit) or cancelled with unsold stock (collect it to
				// re-list). Either way the collect must happen before the slot frees.
				onFocusTarget.accept(null);
				onHighlightSlotForItem.accept(t.getItemId());
				if (t.getPhase() != ExitPhase.AWAITING_COLLECT)
				{
					t.setPhase(ExitPhase.AWAITING_COLLECT);
				}
				boolean cancelledStock = t.getHeldQuantity() > 0;
				log.debug("Exit Trades: prompt collect ({}) slot {} item {}",
					cancelledStock ? "cancelled" : "profit", t.getSlot(), t.getItemId());
				onStatusMessage.accept("Exit Trades: collect the "
					+ (cancelledStock ? "cancelled " : "profit for ") + t.getItemName(), t.getItemId());
				return;
			}
			// Still listed: re-list at the exit price — unless it's already at it.
			int price = resolveExitPrice(t);
			if (price > 0 && price == live.getPrice())
			{
				log.debug("Exit Trades: slot {} item {} already at exit price {} — skipping",
					t.getSlot(), t.getItemId(), price);
				t.setPhase(ExitPhase.DONE);
				surfaceCurrent();
				return;
			}
			modifyingActiveOffer = true; // offer owns its slot; suppress the empty-slot sell glow
			surfaceSell(t, price, Math.max(1, live.getTotalQuantity()), true);
			return;
		}

		// Cancelled sell whose slot already freed: the unsold stock sits in the GE collection box, not
		// inventory. Keep prompting the collect — do NOT fall through to the held-in-inventory check,
		// which reads 0 (nothing in inventory yet) and would wrongly mark the target done.
		if (!t.isBuy() && t.getPhase() == ExitPhase.AWAITING_COLLECT)
		{
			onFocusTarget.accept(null);
			onClearHighlights.run();
			log.debug("Exit Trades: prompt collect (cancelled sell, slot freed) item {}", t.getItemId());
			onStatusMessage.accept("Exit Trades: collect the cancelled " + t.getItemName(), t.getItemId());
			return;
		}

		// Bought stock collected into inventory: always prompt the sell. Don't skip on a momentary
		// held==0 read — the inventory container can lag the collect event by a tick.
		if (t.getPhase() == ExitPhase.CANCELLED_HOLDING)
		{
			int held = Math.max(inventoryQtySupplier.applyAsInt(t.getItemId()), t.getHeldQuantity());
			surfaceSell(t, resolveExitPrice(t), Math.max(1, held), false);
			return;
		}

		// No live offer here. If stock is held (buy collected out-of-band, or a sell cancelled leaving
		// stock), sell it; otherwise the trade already resolved itself — skip.
		int held = Math.max(0, inventoryQtySupplier.applyAsInt(t.getItemId()));
		if (held > 0)
		{
			if (t.isBuy())
			{
				t.setPhase(ExitPhase.CANCELLED_HOLDING);
				t.setHeldQuantity(held);
			}
			surfaceSell(t, resolveExitPrice(t), held, false);
			return;
		}

		log.debug("Exit Trades: slot {} item {} no longer live and none held — skipping",
			t.getSlot(), t.getItemId());
		t.setPhase(ExitPhase.DONE);
		surfaceCurrent();
	}

	/**
	 * The player opened a sell setup screen for {@code itemId}. If it's a pending exit target, scope
	 * the prompt to it so the on-screen item and the surfaced price match — this lets slots be worked
	 * in any order and stops a stale queue-pointer prompt from showing the wrong item. Returns true
	 * when handled. Runs on the client thread (reads offer/inventory state).
	 */
	public boolean onSellScreenOpened(int itemId)
	{
		if (!active)
		{
			return false;
		}
		for (ExitSlotTarget t : targets)
		{
			if (t.getItemId() != itemId
				|| t.getPhase() == ExitPhase.DONE
				|| t.getPhase() == ExitPhase.PENDING_CANCEL)
			{
				continue;
			}
			int price = resolveExitPrice(t);
			if (price <= 0)
			{
				return false;
			}
			OfferRecord live = offerStore.bySlot(t.getSlot());
			boolean ownsSlot = live != null && live.getItemId() == itemId && !live.isBuy();
			int qty = ownsSlot
				? Math.max(1, live.getTotalQuantity())
				: Math.max(1, Math.max(inventoryQtySupplier.applyAsInt(itemId), t.getHeldQuantity()));
			modifyingActiveOffer = ownsSlot;
			if (ownsSlot)
			{
				onHighlightSlotForItem.accept(itemId);
			}
			else
			{
				onClearHighlights.run();
			}
			onFocusTarget.accept(FocusedFlip.forSell(itemId, t.getItemName(), price, qty));
			onStatusMessage.accept("Exit Trades: re-list " + t.getItemName() + " at " + price, itemId);
			log.debug("Exit Trades: scoped sell prompt to on-screen item {} @ {} (mode {})", itemId, price, mode);
			return true;
		}
		return false;
	}

	private int resolveExitPrice(ExitSlotTarget t)
	{
		return ExitPriceResolver.resolve(mode, t.getItemId(), t.getBuyBasis(),
			backendSellPriceSupplier.applyAsInt(t.getItemId()), wikiPriceSupplier.apply(t.getItemId()));
	}

	private void surfaceSell(ExitSlotTarget t, int price, int quantity, boolean highlightSlot)
	{
		if (price <= 0)
		{
			log.debug("Exit Trades: no price data for item {} slot {} — skipping", t.getItemId(), t.getSlot());
			onStatusMessage.accept("Exit Trades: no price data — skipping " + t.getItemName(), t.getItemId());
			t.setPhase(ExitPhase.DONE);
			surfaceCurrent();
			return;
		}
		if (highlightSlot)
		{
			onHighlightSlotForItem.accept(t.getItemId());
		}
		else
		{
			onClearHighlights.run();
		}
		log.debug("Exit Trades: prompt re-list slot {} item {} phase {} @ {} (mode {})",
			t.getSlot(), t.getItemId(), t.getPhase(), price, mode);
		onFocusTarget.accept(FocusedFlip.forSell(t.getItemId(), t.getItemName(), price, Math.max(1, quantity)));
		onStatusMessage.accept("Exit Trades: re-list " + t.getItemName() + " at " + price, t.getItemId());
	}

	public static class PersistedTarget
	{
		int slot;
		int itemId;
		String itemName;
		boolean buy;
		int buyBasis;
		String phase;
	}

	public static class PersistedState
	{
		ExitTradesMode mode;
		List<PersistedTarget> pending;
		long savedAtMillis;
	}

	/** Snapshot for logout persistence, or null when nothing was acted (AC6 discard). */
	public PersistedState getStateForPersistence(long nowMillis)
	{
		if (!active || actedCount() == 0)
		{
			return null;
		}
		PersistedState s = new PersistedState();
		s.mode = mode;
		s.savedAtMillis = nowMillis;
		s.pending = new ArrayList<>();
		for (ExitSlotTarget t : targets)
		{
			if (t.getPhase() == ExitPhase.DONE)
			{
				continue; // DONE slots are real GE offers; nothing to resume
			}
			PersistedTarget p = new PersistedTarget();
			p.slot = t.getSlot();
			p.itemId = t.getItemId();
			p.itemName = t.getItemName();
			p.buy = t.isBuy();
			p.buyBasis = t.getBuyBasis();
			p.phase = t.getPhase().name();
			s.pending.add(p);
		}
		return s;
	}

	public boolean restoreState(PersistedState state, long nowMillis, long maxAgeMs)
	{
		if (state == null || state.pending == null || state.pending.isEmpty()
			|| nowMillis - state.savedAtMillis > maxAgeMs)
		{
			return false;
		}
		targets.clear();
		mode = state.mode;
		for (PersistedTarget p : state.pending)
		{
			ExitSlotTarget t = p.buy
				? ExitSlotTarget.forBuy(p.slot, p.itemId, p.itemName, p.buyBasis)
				: ExitSlotTarget.sell(p.slot, p.itemId, p.itemName, p.buyBasis);
			t.setPhase(ExitPhase.valueOf(p.phase));
			targets.add(t);
		}
		active = true;
		return true;
	}
}
