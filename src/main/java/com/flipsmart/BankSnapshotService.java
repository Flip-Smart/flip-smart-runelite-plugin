package com.flipsmart;
import com.flipsmart.api.dto.Dtos.BankItemId;
import com.flipsmart.api.dto.Dtos.BankItem;
import com.flipsmart.api.dto.Dtos.BankSnapshotResponse;
import com.flipsmart.api.dto.Dtos.WikiPrice;
import com.flipsmart.domain.offer.OfferSignal;

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

/**
 * Handles bank snapshot capture and submission to the API.
 * Manages cooldowns, rate limit checks, and wealth calculation.
 */
@Slf4j
@Singleton
public class BankSnapshotService
{
	private static final long BANK_SNAPSHOT_COOLDOWN_MS = 60_000;
	private static final long BANK_SNAPSHOT_BACKOFF_MS = 30 * 60_000;

	/**
	 * Cooldown applied to the next attempt. Extended to the long backoff when
	 * the server accepted a snapshot or answered rate-limited — its window is
	 * 24h, so re-posting the full bank every minute would be wasted traffic.
	 */
	private volatile long mainCooldownMs = BANK_SNAPSHOT_COOLDOWN_MS;
	private volatile long hardcoreCooldownMs = BANK_SNAPSHOT_COOLDOWN_MS;

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
		if (now - session.getLastBankSnapshotAttempt() < cooldownMs())
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
		final boolean hardcore = session.isHardcoreWorld();
		setCooldownMs(hardcore, BANK_SNAPSHOT_COOLDOWN_MS);

		clientThread.invokeLater(() -> captureBankSnapshot(rsn, hardcore));
	}

	/**
	 * Capture the current bank contents and send to API.
	 * Must be called on client thread.
	 */
	private void captureBankSnapshot(String rsn, boolean hardcore)
	{
		try
		{
			List<BankItem> items = collectBankItems();

			if (items == null || items.isEmpty())
			{
				session.setBankSnapshotInProgress(false);
				return;
			}

			List<BankItemId> inventoryItems = collectInventoryItems();
			List<BankItemId> gearItems = collectGearItems();
			long geOffersValue = calculateGEOffersValue();

			log.debug("Capturing bank snapshot: {} bank items, {} inv items, {} gear items, ge={} for RSN {}",
				items.size(), inventoryItems.size(), gearItems.size(), geOffersValue, rsn);

			apiClient.createBankSnapshotAsync(rsn, items, inventoryItems, gearItems, geOffersValue).thenAccept(result ->
			{
				if (result != null && result.isSuccess())
				{
					BankSnapshotResponse response = result.getResponse();
					String wealthStr = String.format("%,d", response.getTotalWealth());
					log.debug("Bank snapshot captured: {} items, {} GP total wealth",
						response.getItemCount(), wealthStr);
					postBankSnapshotMessage(
						String.format("Bank snapshot saved: %s GP total wealth", wealthStr),
						false);
					setCooldownMs(hardcore, BANK_SNAPSHOT_BACKOFF_MS);
				}
				else if (result != null && result.isRateLimited())
				{
					log.debug("Bank snapshot rate limited by server - backing off");
					setCooldownMs(hardcore, BANK_SNAPSHOT_BACKOFF_MS);
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
			.append("[FlipSmart] ")
			.append(isError ? ChatColorType.HIGHLIGHT : ChatColorType.NORMAL)
			.append(message);

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(builder.build())
			.build());
	}

	/**
	 * Collect every bank item with its client-side GE price (0 when it has none).
	 * Untradeable variants are sent as-is; the API values them at their tradeable parts.
	 */
	private List<BankItem> collectBankItems()
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

		List<BankItem> items = new ArrayList<>();

		for (BankItemId item : collectItemIds(bankItems))
		{
			items.add(new BankItem(item.itemId, item.quantity, itemManager.getItemPrice(item.itemId)));
		}

		return items;
	}

	/**
	 * Collect items from the player's inventory.
	 * The backend prices these server-side, so no GE price is computed here.
	 * Coins (item_id 995) are included; backend prices coins as 1 GP each.
	 */
	private List<BankItemId> collectInventoryItems()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null)
		{
			return new ArrayList<>();
		}
		return collectItemIds(inventory.getItems());
	}

	/**
	 * Collect equipped gear items from the EQUIPMENT container.
	 * The backend prices these server-side.
	 */
	private List<BankItemId> collectGearItems()
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return new ArrayList<>();
		}
		return collectItemIds(equipment.getItems());
	}

	/**
	 * Every item in the array as a BankItemId record (canonical item_id + quantity,
	 * no client-side price). Noted and worn variants resolve to their base item;
	 * bank placeholders are skipped, since canonicalize would value them as the real item.
	 */
	private List<BankItemId> collectItemIds(Item[] items)
	{
		List<BankItemId> out = new ArrayList<>();
		if (items == null)
		{
			return out;
		}
		for (Item item : items)
		{
			int itemId = item.getId();
			int quantity = item.getQuantity();
			if (itemId <= 0 || quantity <= 0
				|| itemManager.getItemComposition(itemId).getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			out.add(new BankItemId(itemManager.canonicalize(itemId), quantity));
		}
		return out;
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
			totalValue += getGEOfferValue(offer);
		}

		return totalValue;
	}

	private long cooldownMs()
	{
		return session.isHardcoreWorld() ? hardcoreCooldownMs : mainCooldownMs;
	}

	private void setCooldownMs(boolean hardcore, long cooldown)
	{
		if (hardcore)
		{
			hardcoreCooldownMs = cooldown;
		}
		else
		{
			mainCooldownMs = cooldown;
		}
	}

	/** A Hardcore offer's item value, from Hardcore prices (RuneLite's guide price is the main game's). */
	private long hardcoreItemPrice(int itemId)
	{
		WikiPrice price = apiClient.getLastKnownWikiPrice(itemId);
		if (price == null)
		{
			return 0;
		}
		return Math.max(0, price.instaSell > 0 ? price.instaSell : price.instaBuy);
	}

	/**
	 * Get the GP value locked in a single GE offer (budget + items).
	 * Returns 0 for null or empty offers.
	 */
	private long getGEOfferValue(GrandExchangeOffer offer)
	{
		if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			return 0;
		}

		long price = offer.getPrice();
		int totalQty = offer.getTotalQuantity();
		int filledQty = offer.getQuantitySold();
		int remainingQty = totalQty - filledQty;

		// RuneLite's guide price is the main game's; a Hardcore offer is valued at Hardcore prices.
		long itemPrice = session.isHardcoreWorld()
			? hardcoreItemPrice(offer.getItemId())
			: itemManager.getItemPrice(offer.getItemId());
		if (itemPrice <= 0)
		{
			itemPrice = price;
		}

		if (OfferSignal.isBuyState(offer.getState()))
		{
			return (long) remainingQty * price + (long) filledQty * itemPrice;
		}
		else
		{
			return (long) remainingQty * itemPrice + (long) filledQty * price;
		}
	}
}
