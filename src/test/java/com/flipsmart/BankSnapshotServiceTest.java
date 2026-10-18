package com.flipsmart;

import com.flipsmart.api.dto.Dtos.BankItem;
import com.flipsmart.api.dto.Dtos.BankItemId;
import com.flipsmart.api.dto.Dtos.BankSnapshotResponse;
import com.flipsmart.api.dto.Dtos.BankSnapshotResult;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BankSnapshotServiceTest
{
	private static final String RSN = "Zezima";

	private PlayerSession session;
	private FlipSmartApiClient apiClient;
	private ChatMessageManager chatMessageManager;
	private Client client;
	private ItemManager itemManager;
	private ItemContainer bank;
	private BankSnapshotService service;

	@Before
	public void setUp()
	{
		session = new PlayerSession();
		apiClient = mock(FlipSmartApiClient.class);
		client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		itemManager = mock(ItemManager.class);
		chatMessageManager = mock(ChatMessageManager.class);

		doAnswer(inv -> {
			Runnable r = inv.getArgument(0);
			r.run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		when(apiClient.isAuthenticated()).thenReturn(true);
		when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
		when(itemManager.getItemPrice(995)).thenReturn(1L);

		// Bank containing a coins stack only — avoids tradeable/price lookups.
		bank = mock(ItemContainer.class);
		when(bank.getItems()).thenReturn(new Item[]{new Item(995, 25_000)});
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(bank);

		service = new BankSnapshotService(session, apiClient, client, clientThread, itemManager, chatMessageManager);
	}

	private void stubCreate(CompletableFuture<BankSnapshotResult> future)
	{
		when(apiClient.createBankSnapshotAsync(anyString(), anyList(), anyList(), anyList(), anyLong()))
			.thenReturn(future);
	}

	private BankSnapshotResult successResult()
	{
		BankSnapshotResponse response = mock(BankSnapshotResponse.class);
		when(response.getTotalWealth()).thenReturn(1_000_000L);
		when(response.getItemCount()).thenReturn(1);
		return BankSnapshotResult.success(response);
	}

	private void fakeElapsed(long elapsedMs)
	{
		session.setLastBankSnapshotAttempt(System.currentTimeMillis() - elapsedMs);
	}

	@Test
	public void successUploadsInSingleCallAndPostsChat()
	{
		stubCreate(CompletableFuture.completedFuture(successResult()));

		service.onBankContainerChanged(RSN);

		verify(apiClient, times(1)).createBankSnapshotAsync(anyString(), anyList(), anyList(), anyList(), anyLong());
		verify(chatMessageManager, times(1)).queue(any());
		assertFalse(session.isBankSnapshotInProgress());
	}

	@Test
	public void cooldownBlocksImmediateRetryAfterSuccess()
	{
		stubCreate(CompletableFuture.completedFuture(successResult()));

		service.onBankContainerChanged(RSN);
		service.onBankContainerChanged(RSN);

		verify(apiClient, times(1)).createBankSnapshotAsync(anyString(), anyList(), anyList(), anyList(), anyLong());
	}

	@Test
	public void failureKeepsCooldownNoImmediateRetry()
	{
		stubCreate(CompletableFuture.completedFuture(BankSnapshotResult.failure()));

		service.onBankContainerChanged(RSN);
		service.onBankContainerChanged(RSN);

		verify(apiClient, times(1)).createBankSnapshotAsync(anyString(), anyList(), anyList(), anyList(), anyLong());
		assertFalse(session.isBankSnapshotInProgress());
	}

	@Test
	public void failureRetriesAfterStandardCooldownElapses()
	{
		stubCreate(CompletableFuture.completedFuture(BankSnapshotResult.failure()));

		service.onBankContainerChanged(RSN);
		fakeElapsed(90_000);
		service.onBankContainerChanged(RSN);

		verify(apiClient, times(2)).createBankSnapshotAsync(anyString(), anyList(), anyList(), anyList(), anyLong());
	}

	@Test
	public void transportExceptionKeepsCooldownNoImmediateRetry()
	{
		CompletableFuture<BankSnapshotResult> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("boom"));
		stubCreate(failed);

		service.onBankContainerChanged(RSN);
		service.onBankContainerChanged(RSN);

		verify(apiClient, times(1)).createBankSnapshotAsync(anyString(), anyList(), anyList(), anyList(), anyLong());
		assertFalse(session.isBankSnapshotInProgress());
	}

	@Test
	public void rateLimitedIsSilentAndBacksOffLongerThanStandardCooldown()
	{
		stubCreate(CompletableFuture.completedFuture(BankSnapshotResult.rateLimitedResult()));

		service.onBankContainerChanged(RSN);
		verify(chatMessageManager, never()).queue(any());
		assertFalse(session.isBankSnapshotInProgress());

		// Standard cooldown has elapsed, but the server said no — stay backed off.
		fakeElapsed(90_000);
		service.onBankContainerChanged(RSN);

		verify(apiClient, times(1)).createBankSnapshotAsync(anyString(), anyList(), anyList(), anyList(), anyLong());
	}

	@Test
	public void successBacksOffLongerThanStandardCooldown()
	{
		stubCreate(CompletableFuture.completedFuture(successResult()));

		service.onBankContainerChanged(RSN);
		fakeElapsed(90_000);
		service.onBankContainerChanged(RSN);

		verify(apiClient, times(1)).createBankSnapshotAsync(anyString(), anyList(), anyList(), anyList(), anyLong());
	}

	@Test
	public void skipsWhenNotAuthenticated()
	{
		when(apiClient.isAuthenticated()).thenReturn(false);
		stubCreate(CompletableFuture.completedFuture(successResult()));

		service.onBankContainerChanged(RSN);

		verify(apiClient, never()).createBankSnapshotAsync(anyString(), anyList(), anyList(), anyList(), anyLong());
	}

	private static final int BANDOS_GODSWORD = 11804;
	private static final int BANDOS_GODSWORD_OR = 20370;
	private static final int BGS_ORNAMENT_KIT = 20071;
	private static final int TUMEKENS_SHADOW_CHARGED = 27275;
	private static final int TUMEKENS_SHADOW_UNCHARGED = 27277;
	private static final int AVERNIC_TREADS = 31088;
	private static final int AVERNIC_TREADS_MAX = 31097;
	private static final int PRIMORDIAL_BOOTS = 13239;
	private static final int PEGASIAN_BOOTS = 13237;
	private static final int ETERNAL_BOOTS = 13235;
	private static final int FIRE_CAPE = 6570;
	private static final int ARMADYL_CHESTPLATE = 11828;
	private static final int ARMADYL_CHESTPLATE_OR = 26715;
	private static final int RUNE_PLATEBODY = 1127;
	private static final int RUNE_PLATEBODY_NOTED = 1128;

	private void stubItem(int itemId, boolean tradeable, long price)
	{
		ItemComposition composition = mock(ItemComposition.class);
		when(composition.isTradeable()).thenReturn(tradeable);
		when(itemManager.getItemComposition(itemId)).thenReturn(composition);
		when(itemManager.getItemPrice(itemId)).thenReturn(price);
	}

	private void stubTradeables()
	{
		stubItem(BANDOS_GODSWORD, true, 12_000_000);
		stubItem(BGS_ORNAMENT_KIT, true, 10_000_000);
		stubItem(TUMEKENS_SHADOW_UNCHARGED, true, 770_000_000);
		stubItem(AVERNIC_TREADS, true, 50_000_000);
		stubItem(PRIMORDIAL_BOOTS, true, 30_000_000);
		stubItem(PEGASIAN_BOOTS, true, 20_000_000);
		stubItem(ETERNAL_BOOTS, true, 10_000_000);
		stubItem(BANDOS_GODSWORD_OR, false, 0);
		stubItem(TUMEKENS_SHADOW_CHARGED, false, 0);
		stubItem(AVERNIC_TREADS_MAX, false, 0);
		stubItem(FIRE_CAPE, false, 0);
		stubItem(ARMADYL_CHESTPLATE, true, 36_000_000);
		stubItem(ARMADYL_CHESTPLATE_OR, false, 0);
		stubItem(RUNE_PLATEBODY, true, 38_000);
	}

	private static Map<Integer, Integer> quantities(List<BankItemId> items)
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (BankItemId item : items)
		{
			out.merge(item.itemId, item.quantity, Integer::sum);
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private List<BankItem> captureBankItems()
	{
		ArgumentCaptor<List<BankItem>> captor = ArgumentCaptor.forClass(List.class);
		verify(apiClient).createBankSnapshotAsync(anyString(), captor.capture(), anyList(), anyList(), anyLong());
		return captor.getValue();
	}

	@Test
	public void ornamentVariantResolvesToBaseItemPlusKit()
	{
		stubTradeables();

		Map<Integer, Integer> components = quantities(service.toTradeableComponents(BANDOS_GODSWORD_OR, 2));

		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(BANDOS_GODSWORD, 2);
		expected.put(BGS_ORNAMENT_KIT, 2);
		assertEquals(expected, components);
	}

	@Test
	public void chargedVariantResolvesToUnchargedItem()
	{
		stubTradeables();

		Map<Integer, Integer> components = quantities(service.toTradeableComponents(TUMEKENS_SHADOW_CHARGED, 1));

		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(TUMEKENS_SHADOW_UNCHARGED, 1);
		assertEquals(expected, components);
	}

	@Test
	public void combinedItemResolvesToEveryComponent()
	{
		stubTradeables();

		Map<Integer, Integer> components = quantities(service.toTradeableComponents(AVERNIC_TREADS_MAX, 1));

		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(AVERNIC_TREADS, 1);
		expected.put(PRIMORDIAL_BOOTS, 1);
		expected.put(PEGASIAN_BOOTS, 1);
		expected.put(ETERNAL_BOOTS, 1);
		assertEquals(expected, components);
	}

	@Test
	public void unmappedUntradeableResolvesToNothing()
	{
		stubTradeables();

		assertTrue(service.toTradeableComponents(FIRE_CAPE, 1).isEmpty());
	}

	@Test
	public void tradeableItemPassesThroughUnchanged()
	{
		stubTradeables();

		Map<Integer, Integer> components = quantities(service.toTradeableComponents(BANDOS_GODSWORD, 3));

		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(BANDOS_GODSWORD, 3);
		assertEquals(expected, components);
	}

	@Test
	public void bankSnapshotValuesUntradeableVariantsAtTheirComponents()
	{
		stubTradeables();
		when(bank.getItems()).thenReturn(new Item[]{
			new Item(995, 25_000),
			new Item(BANDOS_GODSWORD_OR, 1),
			new Item(TUMEKENS_SHADOW_CHARGED, 1),
			new Item(AVERNIC_TREADS_MAX, 1),
			new Item(FIRE_CAPE, 1),
		});
		stubCreate(CompletableFuture.completedFuture(successResult()));

		service.onBankContainerChanged(RSN);

		List<BankItem> sent = captureBankItems();
		long total = sent.stream().mapToLong(i -> i.quantity * i.valuePerItem).sum();
		assertEquals(25_000L + 22_000_000L + 770_000_000L + 110_000_000L, total);
		assertFalse(sent.stream().map(i -> i.itemId).collect(Collectors.toSet()).contains(FIRE_CAPE));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void equippedUntradeableVariantsAreSentAsComponents()
	{
		stubTradeables();
		ItemContainer equipment = mock(ItemContainer.class);
		when(equipment.getItems()).thenReturn(new Item[]{new Item(AVERNIC_TREADS_MAX, 1)});
		when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(equipment);
		stubCreate(CompletableFuture.completedFuture(successResult()));

		service.onBankContainerChanged(RSN);

		ArgumentCaptor<List<BankItemId>> gear = ArgumentCaptor.forClass(List.class);
		verify(apiClient).createBankSnapshotAsync(anyString(), anyList(), anyList(), gear.capture(), anyLong());
		assertEquals(4, quantities(gear.getValue()).size());
		assertFalse(quantities(gear.getValue()).containsKey(AVERNIC_TREADS_MAX));
	}

	@Test
	public void variantMissingFromItemMappingResolvesViaSupplementalMap()
	{
		stubTradeables();

		Map<Integer, Integer> components = quantities(service.toTradeableComponents(ARMADYL_CHESTPLATE_OR, 1));

		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(ARMADYL_CHESTPLATE, 1);
		assertEquals(expected, components);
	}

	@Test
	public void notedItemResolvesToItsUnnotedId()
	{
		stubTradeables();
		when(itemManager.canonicalize(RUNE_PLATEBODY_NOTED)).thenReturn(RUNE_PLATEBODY);

		Map<Integer, Integer> components = quantities(service.toTradeableComponents(RUNE_PLATEBODY_NOTED, 50));

		Map<Integer, Integer> expected = new HashMap<>();
		expected.put(RUNE_PLATEBODY, 50);
		assertEquals(expected, components);
	}
}
