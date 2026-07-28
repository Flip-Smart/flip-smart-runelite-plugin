package com.flipsmart.api.dto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Pins {@link LiveStateSnapshot}'s content-based equality: Task 9's push
 * debouncer dedups consecutive snapshots by comparing them, so two captures
 * with identical content must compare equal regardless of when each was built.
 */
public class LiveStateSnapshotEqualityTest
{
	private static LiveStateSnapshot snapshotWith(int filledQuantity)
	{
		LiveStateSnapshot.SlotState slot = new LiveStateSnapshot.SlotState(
			0, 4151, "Abyssal whip", true, "BUYING", 10, filledQuantity, 2_000_000);
		return new LiveStateSnapshot(
			Collections.singletonList(slot),
			new LinkedHashSet<>(Arrays.asList(4151)),
			Collections.emptySet());
	}

	@Test
	public void identicalContentIsEqualAndSameHashCode()
	{
		LiveStateSnapshot a = snapshotWith(5);
		LiveStateSnapshot b = snapshotWith(5);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	public void differingSlotContentIsNotEqual()
	{
		LiveStateSnapshot a = snapshotWith(5);
		LiveStateSnapshot b = snapshotWith(6);

		assertNotEquals(a, b);
	}

	@Test
	public void slotsAreDefensivelyCopiedFromSourceList()
	{
		LiveStateSnapshot.SlotState slot = new LiveStateSnapshot.SlotState(
			0, 4151, "Abyssal whip", true, "BUYING", 10, 5, 2_000_000);
		List<LiveStateSnapshot.SlotState> source = new ArrayList<>(Collections.singletonList(slot));

		LiveStateSnapshot snapshot = new LiveStateSnapshot(source, Collections.emptySet(), Collections.emptySet());
		source.clear();

		assertEquals(1, snapshot.getSlots().size());
	}
}
