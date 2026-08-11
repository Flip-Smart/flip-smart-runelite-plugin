package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Buy-limit cooldown window bookkeeping (#1115).
 *
 * <p>AC6 is the subtle requirement under test: the 4h window opens on the FIRST
 * unit bought on an offer, not when the limit finishes filling. The stock GE
 * plugin uses the same rule, and we deliberately mirror it — but we keep our own
 * record because that plugin can be switched off, in which case its copy never
 * gets written at all.</p>
 */
public class BuyLimitWindowTest
{
	private static final int CANNONBALL_ID = 2;
	private static final String OUR_GROUP = "flipsmart";
	private static final String STOCK_GROUP = "grandexchange";
	private static final String KEY = "buylimit." + CANNONBALL_ID;

	private ConfigManager configManager;
	private GeOfferDescriptionService service;

	@Before
	public void setUp()
	{
		configManager = mock(ConfigManager.class);
		service = new GeOfferDescriptionService(
			mock(Client.class),
			mock(ClientThread.class),
			mock(FlipSmartApiClient.class),
			mock(FlipSmartPlugin.class),
			mock(ItemManager.class),
			mock(FlipAssistOverlay.class),
			configManager);
	}

	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int quantitySold)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getState()).thenReturn(state);
		when(o.getQuantitySold()).thenReturn(quantitySold);
		when(o.getItemId()).thenReturn(CANNONBALL_ID);
		return o;
	}

	private void stubStored(String group, Instant value)
	{
		when(configManager.getRSProfileConfiguration(eq(group), eq(KEY), any()))
			.thenReturn(value);
	}

	// -----------------------------------------------------------------
	// AC6 — when the window opens
	// -----------------------------------------------------------------

	@Test
	public void aSinglePartialFillOpensTheWindow()
	{
		// One cannonball of an 11,000 offer has bought — the limit is nowhere near
		// filled, but the 4h clock is already running.
		service.recordBuyLimitWindow(offer(GrandExchangeOfferState.BUYING, 1));

		verify(configManager).setRSProfileConfiguration(eq(OUR_GROUP), eq(KEY), any(Instant.class));
	}

	@Test
	public void anUnfilledBuyOfferDoesNotOpenTheWindow()
	{
		// Offer placed, nothing bought yet: no purchase means no cooldown (AC5).
		service.recordBuyLimitWindow(offer(GrandExchangeOfferState.BUYING, 0));

		verify(configManager, never()).setRSProfileConfiguration(any(), any(), any());
	}

	@Test
	public void aSellOfferNeverOpensABuyLimitWindow()
	{
		service.recordBuyLimitWindow(offer(GrandExchangeOfferState.SELLING, 500));
		service.recordBuyLimitWindow(offer(GrandExchangeOfferState.SOLD, 500));

		verify(configManager, never()).setRSProfileConfiguration(any(), any(), any());
	}

	@Test
	public void aLaterFillOnTheSameOfferDoesNotPushTheResetBack()
	{
		// The window is already running. Subsequent fills must leave it alone,
		// otherwise a slowly-filling offer would show a permanently-refreshing 4h.
		stubStored(OUR_GROUP, Instant.now().plus(Duration.ofHours(3)));

		service.recordBuyLimitWindow(offer(GrandExchangeOfferState.BUYING, 250));

		verify(configManager, never()).setRSProfileConfiguration(any(), any(), any());
	}

	@Test
	public void anExpiredWindowIsReopenedByTheNextPurchase()
	{
		stubStored(OUR_GROUP, Instant.now().minus(Duration.ofMinutes(1)));

		service.recordBuyLimitWindow(offer(GrandExchangeOfferState.BOUGHT, 11_000));

		verify(configManager).setRSProfileConfiguration(eq(OUR_GROUP), eq(KEY), any(Instant.class));
	}

	// -----------------------------------------------------------------
	// Reading it back
	// -----------------------------------------------------------------

	@Test
	public void ourRecordIsUsedWhenTheStockGePluginIsDisabled()
	{
		// runelite.grandexchangeplugin=false — nothing is ever written to the
		// stock group, so only our own record can drive the timer.
		stubStored(STOCK_GROUP, null);
		stubStored(OUR_GROUP, Instant.now().plus(Duration.ofHours(2)));

		Long remaining = service.lookupLimitResetMillis(CANNONBALL_ID);

		assertNotNull("our own record must drive the timer on its own", remaining);
		assertTrue("roughly two hours remain", remaining > Duration.ofMinutes(119).toMillis());
	}

	@Test
	public void theStockRecordStillCountsForItemsBoughtBeforeThisFeatureShipped()
	{
		stubStored(OUR_GROUP, null);
		stubStored(STOCK_GROUP, Instant.now().plus(Duration.ofHours(1)));

		assertNotNull("stock record is honoured as a fallback",
			service.lookupLimitResetMillis(CANNONBALL_ID));
	}

	@Test
	public void theLaterOfTheTwoRecordsWins()
	{
		// Whichever source saw the earlier first-fill has the more accurate reset;
		// taking the later one avoids under-reporting the remaining cooldown.
		stubStored(OUR_GROUP, Instant.now().plus(Duration.ofHours(3)));
		stubStored(STOCK_GROUP, Instant.now().plus(Duration.ofMinutes(30)));

		Long remaining = service.lookupLimitResetMillis(CANNONBALL_ID);

		assertTrue("the longer window is reported", remaining > Duration.ofMinutes(150).toMillis());
	}

	@Test
	public void nothingIsReportedWithNoRecordAtAll()
	{
		stubStored(OUR_GROUP, null);
		stubStored(STOCK_GROUP, null);

		assertNull("AC5 — no purchase in the window means no timer",
			service.lookupLimitResetMillis(CANNONBALL_ID));
	}

	@Test
	public void anElapsedRecordReportsNothingRatherThanZero()
	{
		stubStored(OUR_GROUP, Instant.now().minus(Duration.ofMinutes(5)));
		stubStored(STOCK_GROUP, null);

		assertNull("an elapsed window must not render 0:00",
			service.lookupLimitResetMillis(CANNONBALL_ID));
	}

	@Test
	public void theWindowIsFourHoursLong()
	{
		assertEquals(Duration.ofHours(4), GeOfferDescriptionService.BUY_LIMIT_WINDOW);
	}
}
