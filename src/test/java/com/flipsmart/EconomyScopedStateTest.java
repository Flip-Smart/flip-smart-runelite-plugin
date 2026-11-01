package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.trading.RoundTripLedger;
import com.flipsmart.trading.TransactionLogger;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The same RSN can trade on a main world and a Hardcore World, so every piece of RSN-keyed local
 * state must also be keyed by economy. Main keeps the original key names so existing players lose
 * nothing; Hardcore state lives under suffixed keys.
 */
public class EconomyScopedStateTest
{
	private static final String GROUP = "flipsmart";

	private PlayerSession session;
	private OfferStore store;
	private RoundTripLedger ledger;
	private OfflineSyncService service;
	private Map<String, String> config;

	@Before
	public void setUp()
	{
		session = new PlayerSession();
		session.setRsn("Zezima");
		store = new OfferStore();
		ledger = new RoundTripLedger();
		config = new HashMap<>();
		ConfigManager configManager = mock(ConfigManager.class);
		doAnswer(inv -> config.put(inv.getArgument(1), inv.getArgument(2)))
			.when(configManager).setConfiguration(eq(GROUP), anyString(), any());
		doAnswer(inv -> config.remove(inv.<String>getArgument(1)))
			.when(configManager).unsetConfiguration(eq(GROUP), anyString());
		when(configManager.getConfiguration(eq(GROUP), anyString()))
			.thenAnswer(inv -> config.get(inv.<String>getArgument(1)));
		service = new OfflineSyncService(session, configManager, new Gson(), mock(Client.class),
			mock(ClientThread.class), mock(ActiveFlipTracker.class), mock(GEHistoryService.class),
			store, mock(ItemManager.class), ledger, mock(TransactionLogger.class));
	}

	private void buyFill(int itemId)
	{
		store.apply(new OfferSignal(0, GrandExchangeOfferState.BUYING, itemId, "i", 10, 100, 4, 400L), 1000L);
		ledger.recordFill("Zezima", itemId, true, 4);
	}

	@Test
	public void economyKeyLeavesMainUnchangedAndSuffixesHardcore()
	{
		assertEquals("persistedOffers_Zezima", PlayerSession.economyKey("persistedOffers_Zezima", false));
		assertEquals("persistedOffers_Zezima_hc", PlayerSession.economyKey("persistedOffers_Zezima", true));
		assertNull(PlayerSession.economyKey(null, true));
		assertEquals("", PlayerSession.economyKey("", true));
	}

	@Test
	public void mainEconomyPersistsUnderTheOriginalKeys()
	{
		buyFill(4151);
		service.persistOfferState();

		for (String prefix : new String[]{"persistedOffers_", "roundTripLedger_", "nextOfferId_", "fillWatermarks_"})
		{
			assertTrue(prefix, config.containsKey(prefix + "Zezima"));
			assertFalse(prefix, config.containsKey(prefix + "Zezima_hc"));
		}
		assertEquals("persistedOffers_Zezima", service.getPersistedOffersKey());
	}

	@Test
	public void hardcoreEconomyPersistsUnderSuffixedKeys()
	{
		session.setHardcoreWorld(true);
		buyFill(4151);
		service.persistOfferState();

		for (String prefix : new String[]{"persistedOffers_", "roundTripLedger_", "nextOfferId_", "fillWatermarks_"})
		{
			assertTrue(prefix, config.containsKey(prefix + "Zezima_hc"));
			assertFalse(prefix, config.containsKey(prefix + "Zezima"));
		}
		assertEquals("persistedOffers_Zezima_hc", service.getPersistedOffersKey());
	}

	@Test
	public void mainOffersAreNotRestoredIntoAHardcoreSession()
	{
		buyFill(4151);
		service.persistOfferState();
		service.releaseForLogout();

		session.setHardcoreWorld(true);
		service.forgetEconomyState();
		List<OfferRecord> restored = service.loadPersistedOfferRecords();

		assertTrue("main records must not load on Hardcore", restored.isEmpty());
		assertTrue("main ledger must not survive into Hardcore", ledger.isEmpty("Zezima"));

		session.setHardcoreWorld(false);
		assertEquals(1, service.loadPersistedOfferRecords().size());
	}

	@Test
	public void forgettingEconomyStateNeverWipesTheSavedMarks()
	{
		buyFill(4151);
		service.persistOfferState();
		String saved = config.get("fillWatermarks_Zezima");

		service.forgetEconomyState();
		store.importRecords(Collections.emptyList());
		service.persistOfferState();

		assertEquals(saved, config.get("fillWatermarks_Zezima"));
	}
}
