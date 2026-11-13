package com.flipsmart;

import com.flipsmart.api.dto.Dtos.ReadjustmentResponse;
import com.flipsmart.api.dto.Dtos.WikiPrice;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;
import com.flipsmart.v9.V9FlipState;
import com.flipsmart.v9.V9FlipStateStore;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the real login market-up path on a real plugin instance: live GE offer in a real
 * OfferStore, real V9 state store, scripted wiki price and readjustment response. Only the
 * RuneLite collaborators (client thread, chat, item names, overlays) are stand-ins.
 */
public class V9MarketUpLoginFlowTest
{
	private static final int ITEM = 4151;
	private static final int SLOT = 3;
	private static final long LISTED = 1_500_000L;

	private FlipSmartPlugin plugin;
	private PlayerSession session;
	private FlipSmartApiClient apiClient;
	private ChatMessageManager chat;
	private GrandExchangeSlotOverlay slotOverlay;
	private V9FlipStateStore store;
	private Method loginCheck;

	@Before
	public void setUp() throws Exception
	{
		plugin = new FlipSmartPlugin();
		session = new PlayerSession();
		session.setOfflineSyncCompleted(true);
		apiClient = mock(FlipSmartApiClient.class);
		chat = mock(ChatMessageManager.class);
		slotOverlay = mock(GrandExchangeSlotOverlay.class);
		ClientThread clientThread = mock(ClientThread.class);
		doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		ItemManager itemManager = mock(ItemManager.class);
		ItemComposition comp = mock(ItemComposition.class);
		when(comp.getName()).thenReturn("Abyssal whip");
		when(itemManager.getItemComposition(anyInt())).thenReturn(comp);

		OfferStore offers = new OfferStore();
		offers.importRecords(Collections.singletonList(new Gson().fromJson(
			"{\"offerId\":1,\"slot\":" + SLOT + ",\"itemId\":" + ITEM + ",\"itemName\":\"Abyssal whip\","
				+ "\"buy\":false,\"totalQuantity\":1,\"price\":" + LISTED + ",\"filledQuantity\":0,"
				+ "\"spent\":0,\"state\":\"NEW\",\"offerStage\":\"initial\"}", OfferRecord.class)));

		store = new V9FlipStateStore(new Gson());
		store.put(listedState(5));

		set("session", session);
		set("apiClient", apiClient);
		set("clientThread", clientThread);
		set("chatMessageManager", chat);
		set("itemManager", itemManager);
		set("offerStore", offers);
		set("v9FlipStateStore", store);
		set("geSlotOverlay", slotOverlay);
		set("flipAssistOverlay", mock(FlipAssistOverlay.class));

		loginCheck = FlipSmartPlugin.class.getDeclaredMethod("maybeV9MarketUpOnLogin", int.class);
		loginCheck.setAccessible(true);
	}

	private static V9FlipState listedState(int listedMinutesAgo)
	{
		V9FlipState s = new V9FlipState();
		s.setItemId(ITEM);
		s.setTimeframe("30m");
		s.setScenario("B");
		s.setListingTimestampMs(System.currentTimeMillis() - listedMinutesAgo * 60_000L);
		s.setBuyPrice(1_400_000L);
		s.setTotalQty(1);
		s.setRemainingQty(1);
		s.setOriginalTarget(LISTED);
		return s;
	}

	private void set(String name, Object value) throws Exception
	{
		Field f = FlipSmartPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void tick() throws Exception
	{
		plugin.maybeAdvanceV9Ladder(ITEM);
		loginCheck.invoke(plugin, ITEM);
	}

	private void market(long instantSell)
	{
		when(apiClient.getWikiPrice(ITEM)).thenReturn(new WikiPrice(instantSell, instantSell - 20_000));
	}

	private void backendRelistsAt(long price)
	{
		ReadjustmentResponse resp = new ReadjustmentResponse();
		resp.setAction("relist");
		resp.setListingPrice(price);
		resp.setDisposition("profit");
		when(apiClient.postReadjustmentAsync(eq(ITEM), any(JsonObject.class)))
			.thenReturn(CompletableFuture.completedFuture(resp));
	}

	@Test
	public void risenMarketPromptsOnceAtLadder1PricingWithoutMovingTheLadder() throws Exception
	{
		market(1_600_000);
		backendRelistsAt(1_580_000);

		tick();
		tick();
		tick();

		ArgumentCaptor<JsonObject> body = ArgumentCaptor.forClass(JsonObject.class);
		verify(apiClient, times(1)).postReadjustmentAsync(eq(ITEM), body.capture());
		assertEquals(1, body.getValue().get("ladder").getAsInt());
		assertEquals(1_600_000L, body.getValue().get("current_instant_sell").getAsLong());
		verify(chat, times(1)).queue(any(QueuedMessage.class));
		verify(slotOverlay, times(1)).setAdjustmentHighlight(SLOT, 1_580_000L);
		assertEquals(1_580_000L, (long) session.getRecommendedPrice(ITEM));
		assertEquals(0, store.get(ITEM).getLadderRung());
		assertEquals(0L, store.get(ITEM).getLadder1ResolvedAtMs());
	}

	@Test
	public void flatMarketNeverAsksTheBackend() throws Exception
	{
		market(LISTED + 5);
		backendRelistsAt(1_580_000);

		tick();
		tick();

		verify(apiClient, never()).postReadjustmentAsync(anyInt(), any(JsonObject.class));
		verify(chat, never()).queue(any(QueuedMessage.class));
	}

	@Test
	public void waitsForOfflineSyncAndAWarmPriceThenFiresOnce() throws Exception
	{
		backendRelistsAt(1_580_000);
		session.setOfflineSyncCompleted(false);
		market(1_600_000);
		tick();
		session.setOfflineSyncCompleted(true);
		when(apiClient.getWikiPrice(ITEM)).thenReturn(null);
		tick();
		when(apiClient.getWikiPrice(ITEM)).thenReturn(new WikiPrice(0, 0));
		tick();
		verify(apiClient, never()).postReadjustmentAsync(anyInt(), any(JsonObject.class));

		market(1_600_000);
		tick();
		tick();

		verify(apiClient, times(1)).postReadjustmentAsync(eq(ITEM), any(JsonObject.class));
		verify(chat, times(1)).queue(any(QueuedMessage.class));
	}

	@Test
	public void backendPriceNotAboveTheListingDoesNotPrompt() throws Exception
	{
		market(1_600_000);
		backendRelistsAt(LISTED - 10_000);

		tick();
		tick();

		verify(apiClient, times(1)).postReadjustmentAsync(eq(ITEM), any(JsonObject.class));
		verify(chat, never()).queue(any(QueuedMessage.class));
		verify(slotOverlay, never()).setAdjustmentHighlight(anyInt(), anyLong());
		assertEquals(0, store.get(ITEM).getLadderRung());
	}

	@Test
	public void ladderRungDueAtLoginCoversTheCheckSoThePlayerGetsOnePrompt() throws Exception
	{
		store.put(listedState(25));
		market(1_600_000);
		backendRelistsAt(1_580_000);

		tick();
		tick();
		tick();

		verify(apiClient, times(1)).postReadjustmentAsync(eq(ITEM), any(JsonObject.class));
		verify(chat, times(1)).queue(any(QueuedMessage.class));
		assertEquals(1, store.get(ITEM).getLadderRung());
	}

	@Test
	public void sellListedThisSessionIsNotTreatedAsALoginCase() throws Exception
	{
		market(1_600_000);
		backendRelistsAt(1_580_000);

		plugin.onV9SellListed(ITEM);
		tick();
		tick();

		verify(apiClient, never()).postReadjustmentAsync(anyInt(), any(JsonObject.class));
	}
}
