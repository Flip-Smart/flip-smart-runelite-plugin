package com.flipsmart;

import com.flipsmart.api.dto.Dtos.BankItem;
import com.flipsmart.api.dto.Dtos.BankItemId;
import com.flipsmart.api.dto.Dtos.BankSnapshotResponse;
import com.flipsmart.api.dto.Dtos.BankSnapshotResult;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
		when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
		chatMessageManager = mock(ChatMessageManager.class);

		doAnswer(inv -> {
			Runnable r = inv.getArgument(0);
			r.run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		when(apiClient.isAuthenticated()).thenReturn(true);

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

	private static final int BANDOS_GODSWORD_OR = 20370;
	private static final int AVERNIC_DEFENDER = 22322;
	private static final int AVERNIC_TREADS_MAX = 31097;
	private static final int RUNE_PLATEBODY = 1127;
	private static final int RUNE_PLATEBODY_NOTED = 1128;

	@SuppressWarnings("unchecked")
	private List<BankItem> sentBankItems()
	{
		ArgumentCaptor<List<BankItem>> captor = ArgumentCaptor.forClass(List.class);
		verify(apiClient).createBankSnapshotAsync(anyString(), captor.capture(), anyList(), anyList(), anyLong());
		return captor.getValue();
	}

	@Test
	public void untradeableBankItemsAreSentForTheApiToValue()
	{
		when(bank.getItems()).thenReturn(new Item[]{
			new Item(995, 25_000), new Item(BANDOS_GODSWORD_OR, 1), new Item(AVERNIC_DEFENDER, 1), new Item(-1, 0),
		});
		when(itemManager.getItemPrice(995)).thenReturn(1L);
		when(itemManager.getItemPrice(BANDOS_GODSWORD_OR)).thenReturn(22_000_000L);
		stubCreate(CompletableFuture.completedFuture(successResult()));

		service.onBankContainerChanged(RSN);

		List<BankItem> sent = sentBankItems();
		assertEquals(List.of(995, BANDOS_GODSWORD_OR, AVERNIC_DEFENDER), sent.stream().map(i -> i.itemId).collect(Collectors.toList()));
		assertEquals(List.of(1L, 22_000_000L, 0L), sent.stream().map(i -> i.valuePerItem).collect(Collectors.toList()));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void inventoryAndGearSendUntradeablesAndCanonicalIds()
	{
		ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(RUNE_PLATEBODY_NOTED, 50)});
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventory);
		ItemContainer equipment = mock(ItemContainer.class);
		when(equipment.getItems()).thenReturn(new Item[]{new Item(AVERNIC_TREADS_MAX, 1)});
		when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(equipment);
		when(itemManager.canonicalize(RUNE_PLATEBODY_NOTED)).thenReturn(RUNE_PLATEBODY);
		stubCreate(CompletableFuture.completedFuture(successResult()));

		service.onBankContainerChanged(RSN);

		ArgumentCaptor<List<BankItemId>> inv = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<List<BankItemId>> gear = ArgumentCaptor.forClass(List.class);
		verify(apiClient).createBankSnapshotAsync(anyString(), anyList(), inv.capture(), gear.capture(), anyLong());
		assertEquals(RUNE_PLATEBODY, inv.getValue().get(0).itemId);
		assertEquals(50, inv.getValue().get(0).quantity);
		assertEquals(AVERNIC_TREADS_MAX, gear.getValue().get(0).itemId);
	}
}
