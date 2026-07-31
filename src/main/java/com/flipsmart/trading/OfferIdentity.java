package com.flipsmart.trading;

import lombok.Getter;

/**
 * Identity of one Grand Exchange order.
 *
 * <p>Deliberately excludes price, total quantity and fill count. Those drift while an order is
 * live — a partial buy re-reads a different total, a relist changes price — and keying on them
 * made a still-live order fail to match itself. Generation separates successive orders that
 * reuse the same slot, so a new order never inherits the previous one's progress.</p>
 */
@Getter
public final class OfferIdentity
{
	private final int slot;
	private final int itemId;
	private final boolean buy;
	private final int generation;

	private OfferIdentity(int slot, int itemId, boolean buy, int generation)
	{
		this.slot = slot;
		this.itemId = itemId;
		this.buy = buy;
		this.generation = generation;
	}

	public static OfferIdentity of(int slot, int itemId, boolean buy, int generation)
	{
		return new OfferIdentity(slot, itemId, buy, generation);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof OfferIdentity))
		{
			return false;
		}
		OfferIdentity other = (OfferIdentity) o;
		return slot == other.slot
			&& itemId == other.itemId
			&& buy == other.buy
			&& generation == other.generation;
	}

	@Override
	public int hashCode()
	{
		int result = slot;
		result = 31 * result + itemId;
		result = 31 * result + (buy ? 1 : 0);
		result = 31 * result + generation;
		return result;
	}

	/** Stable string form, used as the persisted watermark key. */
	@Override
	public String toString()
	{
		return slot + ":" + itemId + ":" + (buy ? "b" : "s") + ":" + generation;
	}
}
