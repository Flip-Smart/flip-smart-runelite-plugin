package com.flipsmart;

import com.flipsmart.api.dto.WikiPrice;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WikiPriceMidPriceTest
{
	@Test
	public void midpointOfBothSides()
	{
		assertEquals(150, new WikiPrice(200, 100).midPrice()); // (200+100)/2
	}

	@Test
	public void fallsBackToPresentSideWhenOtherMissing()
	{
		assertEquals(100, new WikiPrice(0, 100).midPrice());
		assertEquals(200, new WikiPrice(200, 0).midPrice());
	}

	@Test
	public void zeroWhenBothMissing()
	{
		assertEquals(0, new WikiPrice(0, 0).midPrice());
	}
}
