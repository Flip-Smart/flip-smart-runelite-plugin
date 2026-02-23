package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ChatMessageType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles bank snapshot capture and submission to the API.
 * Manages cooldowns, rate limit checks, and wealth calculation.
 */
@Slf4j
@Singleton
public class BankSnapshotService
{
	private static final int COINS_ITEM_ID = 995;
	private static final long BANK_SNAPSHOT_COOLDOWN_MS = 60_000;

	private final PlayerSession session;
	private final FlipSmartApiClient apiClient;
	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final ChatMessageManager chatMessageManager;

	@Inject
	public BankSnapshotService(
		PlayerSession session,
		FlipSmartApiClient apiClient,
		Client client,
		ClientThread clientThread,
		ItemManager itemManager,
		ChatMessageManager chatMessageManager)
	{
		this.session = session;
		this.apiClient = apiClient;
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.chatMessageManager = chatMessageManager;
	}

	/**
	 * Handle bank container changes - attempt to capture snapshot when bank is opened.
	 * @param rsn The player's RSN (must not be null)
	 */
	public void onBankContainerChanged(String rsn)
	{
		if (session.isBankSnapshotInProgress())
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now - session.getLastBankSnapshotAttempt() < BANK_SNAPSHOT_COOLDOWN_MS)
		{
			return;
		}

		if (rsn == null)
		{
			return;
		}

		if (!apiClient.isAuthenticated())
		{
			return;
		}

		session.setLastBankSnapshotAttempt(now);
		session.setBankSnapshotInProgress(true);

		apiClient.checkBankSnapshotStatusAsync(rsn).thenAccept(status ->
		{
			if (status == null)
			{
				log.debug("Failed to check bank snapshot status");
				postBankSnapshotMessage("Failed to check snapshot status - will retry", true);
				session.setBankSnapshotInProgress(false);
				session.setLastBankSnapshotAttempt(0);
				return;
			}

			if (!status.isCanSnapshot())
			{
				log.debug("Bank snapshot not available: {}", status.getMessage());
				session.setBankSnapshotInProgress(false);
				return;
			}

			clientThread.invokeLater(() -> captureBankSnapshot(rsn));
		}).exceptionally(e ->
		{
			log.debug("Error checking bank snapshot status: {}", e.getMessage());
			postBankSnapshotMessage("Connection error - will retry", true);
			session.setBankSnapshotInProgress(false);
			session.setLastBankSnapshotAttempt(0);
			return null;
		});
	}

	/**
	 * Capture the current bank contents and send to API.
	 * Must be called on client thread.
	 */
	private void captureBankSnapshot(String rsn)
	{
		try
		{
			List<FlipSmartApiClient.BankItem> items = collectTradeableBankItems();

			if (items == null || items.isEmpty())
			{
				session.setBankSnapshotInProgress(false);
				return;
			}

			long inventoryValue = calculateInventoryValue();
			long geOffersValue = calculateGEOffersValue();

			log.info("Capturing bank snapshot: {} tradeable items, inv={}, ge={} for RSN {}",
				items.size(), inventoryValue, geOffersValue, rsn);

			apiClient.createBankSnapshotAsync(rsn, items, inventoryValue, geOffersValue).thenAccept(response ->
			{
				if (response != null)
				{
					String wealthStr = String.format("%,d", response.getTotalWealth());
					log.info("Bank snapshot captured: {} items, {} GP total wealth",
						response.getItemCount(), wealthStr);
					postBankSnapshotMessage(
						String.format("Bank snapshot saved: %s GP total wealth", wealthStr),
						false);
				}
				else
				{
					log.debug("Failed to create bank snapshot");
					postBankSnapshotMessage("Failed to save bank snapshot", true);
				}
				session.setBankSnapshotInProgress(false);
			}).exceptionally(e ->
			{
				log.debug("Error creating bank snapshot: {}", e.getMessage());
				postBankSnapshotMessage("Connection error - will retry", true);
				session.setBankSnapshotInProgress(false);
				session.setLastBankSnapshotAttempt(0);
				return null;
			});
		}
		catch (Exception e)
		{
			log.error("Error capturing bank snapshot: {}", e.getMessage());
			postBankSnapshotMessage("Error capturing bank data", true);
			session.setBankSnapshotInProgress(false);
		}
	}

	/**
	 * Post a bank snapshot message to game chat.
	 */
	private void postBankSnapshotMessage(String message, boolean isError)
	{
		ChatMessageBuilder builder = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Flip Smart] ")
			.append(isError ? ChatColorType.HIGHLIGHT : ChatColorType.NORMAL)
			.append(message);

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(builder.build())
			.build());
	}

	/**
	 * Collect all tradeable items from the bank with their GE prices.
	 */
	private List<FlipSmartApiClient.BankItem> collectTradeableBankItems()
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			log.debug("Bank container is null - bank may have been closed");
			return null;
		}

		Item[] bankItems = bank.getItems();
		if (bankItems == null || bankItems.length == 0)
		{
			log.debug("Bank is empty");
			return null;
		}

		List<FlipSmartApiClient.BankItem> items = new ArrayList<>();

		for (Item item : bankItems)
		{
			FlipSmartApiClient.BankItem bankItem = toTradeableBankItem(item);
			if (bankItem != null)
			{
				items.add(bankItem);
			}
		}

		if (items.isEmpty())
		{
			log.debug("No tradeable items with prices found in bank");
		}

		return items;
	}

	/**
	 * Convert a bank item to a BankItem if it's tradeable and has a price, or if it's coins.
	 */
	private FlipSmartApiClient.BankItem toTradeableBankItem(Item item)
	{
		int itemId = item.getId();
		int quantity = item.getQuantity();

		if (itemId <= 0 || quantity <= 0)
		{
			return null;
		}

		if (itemId == COINS_ITEM_ID)
		{
			return new FlipSmartApiClient.BankItem(itemId, quantity, 1);
		}

		if (!ItemUtils.isTradeable(itemManager, itemId))
		{
			return null;
		}

		int gePrice = itemManager.getItemPrice(itemId);
		if (gePrice <= 0)
		{
			return null;
		}

		return new FlipSmartApiClient.BankItem(itemId, quantity, gePrice);
	}

	/**
	 * Calculate the total value of items in the player's inventory.
	 */
	private long calculateInventoryValue()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null)
		{
			return 0;
		}

		long totalValue = 0;

		for (Item item : inventory.getItems())
		{
			int itemId = item.getId();
			int quantity = item.getQuantity();

			if (itemId <= 0 || quantity <= 0)
			{
				continue;
			}

			if (itemId == COINS_ITEM_ID)
			{
				totalValue += quantity;
				continue;
			}

			if (!ItemUtils.isTradeable(itemManager, itemId))
			{
				continue;
			}

			int gePrice = itemManager.getItemPrice(itemId);
			if (gePrice > 0)
			{
				totalValue += (long) quantity * gePrice;
			}
		}

		return totalValue;
	}

	/**
	 * Calculate the total value locked in GE offers.
	 */
	private long calculateGEOffersValue()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return 0;
		}

		long totalValue = 0;

		for (GrandExchangeOffer offer : offers)
		{
			if (offer == null)
			{
				continue;
			}

			GrandExchangeOfferState state = offer.getState();
			if (state == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}

			int itemId = offer.getItemId();
			int price = offer.getPrice();
			int totalQty = offer.getTotalQuantity();
			int filledQty = offer.getQuantitySold();
			int remainingQty = totalQty - filledQty;

			int itemPrice = itemManager.getItemPrice(itemId);
			if (itemPrice <= 0)
			{
				itemPrice = price;
			}

			if (TrackedOffer.isBuyState(state))
			{
				long remainingBudget = (long) remainingQty * price;
				long filledItemsValue = (long) filledQty * itemPrice;
				totalValue += remainingBudget + filledItemsValue;
			}
			else
			{
				long remainingItemsValue = (long) remainingQty * itemPrice;
				long filledGP = (long) filledQty * price;
				totalValue += remainingItemsValue + filledGP;
			}
		}

		return totalValue;
	}
}
