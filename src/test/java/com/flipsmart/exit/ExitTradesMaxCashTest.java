package com.flipsmart.exit;

import com.flipsmart.api.dto.Dtos.WikiPrice;
import com.flipsmart.trading.OfferStore;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExitTradesMaxCashTest
{
	private static final long PRICE_3B = 3_000_000_000L;

	@Test
	public void legacyIntBuyBasisStillRestores()
	{
		String legacy = "{\"mode\":\"BREAKEVEN\",\"savedAtMillis\":1000,\"pending\":[{\"slot\":0,\"itemId\":4151,"
			+ "\"itemName\":\"Whip\",\"buy\":false,\"buyBasis\":1500000,\"phase\":\"PENDING\"}]}";
		ExitTradesController.PersistedState state = new Gson().fromJson(legacy, ExitTradesController.PersistedState.class);
		ExitTradesController controller = new ExitTradesController(new OfferStore());

		assertTrue(controller.restoreState(state, 1000L, 60_000L));
		assertEquals(1_500_000L, controller.getTargets().get(0).getBuyBasis());
	}

	@Test
	public void buyBasisAboveInt32RoundTripsAndPricesBreakeven()
	{
		ExitTradesController.PersistedTarget t = new ExitTradesController.PersistedTarget();
		t.buyBasis = PRICE_3B;
		Gson gson = new Gson();
		assertEquals(PRICE_3B, gson.fromJson(gson.toJson(t), ExitTradesController.PersistedTarget.class).buyBasis);

		assertEquals(3_005_000_000L, ExitPriceResolver.resolve(ExitTradesMode.BREAKEVEN, 4151, PRICE_3B, 0, null));
		assertEquals(3_050_000_000L, ExitPriceResolver.resolve(ExitTradesMode.REGULAR, 4151, 0, 0,
			new WikiPrice(3_100_000_000L, PRICE_3B)));
	}
}
