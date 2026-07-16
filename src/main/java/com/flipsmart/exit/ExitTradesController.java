package com.flipsmart.exit;

import com.flipsmart.FocusedFlip;
import com.flipsmart.api.dto.WikiPrice;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import com.flipsmart.trading.OfferStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Owns an in-progress Exit Trades run: a mode plus an ordered per-slot queue the player
 * unwinds one slot at a time. Re-targets the existing sell-prompt system; never drives the GE.
 */
public final class ExitTradesController
{
	private static final int GE_SLOTS = 8;

	private final OfferStore offerStore;
	private IntUnaryOperator buyBasisSupplier = itemId -> 0;
	private IntFunction<WikiPrice> wikiPriceSupplier = itemId -> null;
	private IntUnaryOperator inventoryQtySupplier = itemId -> 0;
	private Consumer<FocusedFlip> onFocusTarget = f -> { };
	private BiConsumer<String, Integer> onStatusMessage = (m, id) -> { };
	private Runnable onComplete = () -> { };

	private boolean active;
	private ExitTradesMode mode;
	private final List<ExitSlotTarget> targets = new ArrayList<>();

	public ExitTradesController(OfferStore offerStore)
	{
		this.offerStore = offerStore;
	}

	public void setBuyBasisSupplier(IntUnaryOperator supplier)
	{
		this.buyBasisSupplier = supplier;
	}

	public void setWikiPriceSupplier(IntFunction<WikiPrice> supplier)
	{
		this.wikiPriceSupplier = supplier;
	}

	public void setInventoryQtySupplier(IntUnaryOperator supplier)
	{
		this.inventoryQtySupplier = supplier;
	}

	public void setOnFocusTarget(Consumer<FocusedFlip> cb)
	{
		this.onFocusTarget = cb;
	}

	public void setOnStatusMessage(BiConsumer<String, Integer> cb)
	{
		this.onStatusMessage = cb;
	}

	public void setOnComplete(Runnable cb)
	{
		this.onComplete = cb;
	}

	public void start(ExitTradesMode mode)
	{
		this.mode = mode;
		targets.clear();
		for (int slot = 0; slot < GE_SLOTS; slot++)
		{
			OfferRecord r = offerStore.bySlot(slot);
			if (r == null)
			{
				continue;
			}
			int basis = buyBasisSupplier.applyAsInt(r.getItemId());
			targets.add(r.isBuy()
				? ExitSlotTarget.buy(slot, r.getItemId(), r.getItemName(), basis)
				: ExitSlotTarget.sell(slot, r.getItemId(), r.getItemName(), basis));
		}
		active = !targets.isEmpty();
	}

	public boolean isActive()
	{
		return active;
	}

	public ExitTradesMode getMode()
	{
		return mode;
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
		targets.clear();
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
			if (t.getPhase() == ExitPhase.PENDING && !t.isBuy() && isFreshSell(record))
			{
				t.setPhase(ExitPhase.DONE);
				return true;
			}
			if (t.getPhase() == ExitPhase.PENDING_CANCEL && t.isBuy()
				&& record.isBuy() && isCancelled(record.getState()))
			{
				t.setPhase(record.getFilledQuantity() > 0
					? ExitPhase.CANCELLED_HOLDING : ExitPhase.DONE);
				return true;
			}
			if (t.getPhase() == ExitPhase.CANCELLED_HOLDING && isFreshSell(record))
			{
				t.setPhase(ExitPhase.DONE);
				return true;
			}
		}
		return false;
	}

	private static boolean isFreshSell(OfferRecord r)
	{
		return !r.isBuy() && r.getState() == OfferState.NEW;
	}

	private static boolean isCancelled(OfferState s)
	{
		return s == OfferState.CANCELLED_EMPTY || s == OfferState.CANCELLED_PARTIAL;
	}

	public int actedCount()
	{
		int n = 0;
		for (ExitSlotTarget t : targets)
		{
			if (t.getPhase() == ExitPhase.DONE || t.getPhase() == ExitPhase.CANCELLED_HOLDING)
			{
				n++;
			}
		}
		return n;
	}

	public void surfaceCurrent()
	{
		if (!active)
		{
			return;
		}
		ExitSlotTarget t = currentTarget();
		if (t == null)
		{
			// Run finished: deactivate but keep DONE targets so a logout snapshot correctly
			// resolves to "nothing to resume" (getStateForPersistence returns null when inactive).
			active = false;
			onComplete.run();
			return;
		}
		if (t.getPhase() == ExitPhase.PENDING_CANCEL)
		{
			onFocusTarget.accept(null);
			onStatusMessage.accept("Exit Trades: cancel the buy offer for " + t.getItemName(), t.getItemId());
			return;
		}
		// PENDING (sell) or CANCELLED_HOLDING (resell bought stock)
		int price = ExitPriceResolver.resolve(mode, t.getItemId(), t.getBuyBasis(),
			wikiPriceSupplier.apply(t.getItemId()));
		if (price <= 0)
		{
			onStatusMessage.accept("Exit Trades: no price data — skipping " + t.getItemName(), t.getItemId());
			t.setPhase(ExitPhase.DONE);
			surfaceCurrent();
			return;
		}
		int qty = t.getPhase() == ExitPhase.CANCELLED_HOLDING
			? Math.max(1, inventoryQtySupplier.applyAsInt(t.getItemId()))
			: Math.max(1, offerQuantity(t.getSlot()));
		onFocusTarget.accept(FocusedFlip.forSell(t.getItemId(), t.getItemName(), price, qty));
		onStatusMessage.accept("Exit Trades: re-list " + t.getItemName() + " at " + price, t.getItemId());
	}

	private int offerQuantity(int slot)
	{
		OfferRecord r = offerStore.bySlot(slot);
		return r == null ? 0 : r.getTotalQuantity();
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
				? ExitSlotTarget.buy(p.slot, p.itemId, p.itemName, p.buyBasis)
				: ExitSlotTarget.sell(p.slot, p.itemId, p.itemName, p.buyBasis);
			t.setPhase(ExitPhase.valueOf(p.phase));
			targets.add(t);
		}
		active = true;
		return true;
	}
}
