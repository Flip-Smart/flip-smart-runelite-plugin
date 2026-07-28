package com.flipsmart.api.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable snapshot of live player state pushed to the backend. */
public final class LiveStateSnapshot
{
	public static final class SlotState
	{
		public final int slot;
		public final int itemId;
		public final String itemName;
		public final boolean isBuy;
		public final String state;
		public final int totalQuantity;
		public final int filledQuantity;
		public final int price;

		public SlotState(int slot, int itemId, String itemName, boolean isBuy, String state,
			int totalQuantity, int filledQuantity, int price)
		{
			this.slot = slot;
			this.itemId = itemId;
			this.itemName = itemName;
			this.isBuy = isBuy;
			this.state = state;
			this.totalQuantity = totalQuantity;
			this.filledQuantity = filledQuantity;
			this.price = price;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof SlotState))
			{
				return false;
			}
			SlotState other = (SlotState) o;
			return slot == other.slot && itemId == other.itemId && isBuy == other.isBuy
				&& totalQuantity == other.totalQuantity && filledQuantity == other.filledQuantity
				&& price == other.price && Objects.equals(itemName, other.itemName)
				&& Objects.equals(state, other.state);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(slot, itemId, itemName, isBuy, state, totalQuantity, filledQuantity, price);
		}
	}

	private final List<SlotState> slots;
	private final Set<Integer> inventoryItemIds;
	private final Set<Integer> collectedItemIds;

	public LiveStateSnapshot(List<SlotState> slots, Set<Integer> inventoryItemIds, Set<Integer> collectedItemIds)
	{
		this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
		this.inventoryItemIds = Collections.unmodifiableSet(new LinkedHashSet<>(inventoryItemIds));
		this.collectedItemIds = Collections.unmodifiableSet(new LinkedHashSet<>(collectedItemIds));
	}

	public List<SlotState> getSlots()
	{
		return slots;
	}

	public Set<Integer> getInventoryItemIds()
	{
		return inventoryItemIds;
	}

	public Set<Integer> getCollectedItemIds()
	{
		return collectedItemIds;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof LiveStateSnapshot))
		{
			return false;
		}
		LiveStateSnapshot other = (LiveStateSnapshot) o;
		return slots.equals(other.slots)
			&& inventoryItemIds.equals(other.inventoryItemIds)
			&& collectedItemIds.equals(other.collectedItemIds);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(slots, inventoryItemIds, collectedItemIds);
	}
}
