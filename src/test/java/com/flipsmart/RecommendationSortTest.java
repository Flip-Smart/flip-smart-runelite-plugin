package com.flipsmart;

import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.ui.panel.RecommendationSort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RecommendationSortTest
{
	private static FlipRecommendation rec(String name, long profit, int volume)
	{
		FlipRecommendation r = new FlipRecommendation();
		r.setItemName(name);
		r.setPotentialProfit(profit);
		r.setDailyVolume(volume);
		return r;
	}

	private static List<String> sorted(RecommendationSort sort)
	{
		List<FlipRecommendation> list = new ArrayList<>(Arrays.asList(
			rec("banana", 100, 900), rec("Cherry", 300, 100), rec("apple", 200, 500)));
		list.sort(sort.comparator());
		return list.stream().map(FlipRecommendation::getItemName).collect(Collectors.toList());
	}

	@Test
	public void rankKeepsTheAlgorithmOrder()
	{
		assertEquals(Arrays.asList("banana", "Cherry", "apple"), sorted(RecommendationSort.RANK));
	}

	@Test
	public void profitSortsHighToLow()
	{
		assertEquals(Arrays.asList("Cherry", "apple", "banana"), sorted(RecommendationSort.PROFIT));
	}

	@Test
	public void volumeSortsHighToLow()
	{
		assertEquals(Arrays.asList("banana", "apple", "Cherry"), sorted(RecommendationSort.VOLUME));
	}

	@Test
	public void alphabeticalIsCaseInsensitive()
	{
		assertEquals(Arrays.asList("apple", "banana", "Cherry"), sorted(RecommendationSort.ALPHABETICAL));
	}

	@Test
	public void rankIsTheFirstOption()
	{
		assertEquals(RecommendationSort.RANK, RecommendationSort.values()[0]);
	}
}
