package com.flipsmart;

import com.flipsmart.api.dto.BankSnapshotResponse;
import com.flipsmart.api.dto.BankSnapshotResult;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
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
	private BankSnapshotService service;

	@Before
	public void setUp()
	{
		session = new PlayerSession();
		apiClient = mock(FlipSmartApiClient.class);
		Client client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		ItemManager itemManager = mock(ItemManager.class);
		chatMessageManager = mock(ChatMessageManager.class);

		doAnswer(inv -> {
			Runnable r = inv.getArgument(0);
			r.run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		when(apiClient.isAuthenticated()).thenReturn(true);

		// Bank containing a coins stack only — avoids tradeable/price lookups.
		ItemContainer bank = mock(ItemContainer.class);
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
}
