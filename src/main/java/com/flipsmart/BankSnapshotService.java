package com.flipsmart;
import com.flipsmart.api.dto.Dtos.BankItemId;
import com.flipsmart.api.dto.Dtos.BankItem;
import com.flipsmart.api.dto.Dtos.BankSnapshotResponse;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.util.ItemUtils;

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
import net.runelite.client.game.ItemMapping;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
	private static final long BANK_SNAPSHOT_BACKOFF_MS = 30 * 60_000;

	/**
	 * Untradeable variants RuneLite's ItemMapping does not cover, keyed to the
	 * tradeable item they are worth. Kits with no GE price contribute nothing.
	 */
	private static final Map<Integer, Integer> UNMAPPED_VARIANTS = ItemUtils.resourceRows("/unmapped_variants.txt")
		.collect(Collectors.toMap(r -> Integer.parseInt(r[0]), r -> Integer.parseInt(r[1])));

	/**
	 * Cooldown applied to the next attempt. Extended to the long backoff when
	 * the server accepted a snapshot or answered rate-limited — its window is
	 * 24h, so re-posting the full bank every minute would be wasted traffic.
	 */
	private volatile long currentCooldownMs = BANK_SNAPSHOT_COOLDOWN_MS;

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
		if (now - session.getLastBankSnapshotAttempt() < currentCooldownMs)
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
		currentCooldownMs = BANK_SNAPSHOT_COOLDOWN_MS;

		clientThread.invokeLater(() -> captureBankSnapshot(rsn));
	}

	/**
	 * Capture the current bank contents and send to API.
	 * Must be called on client thread.
	 */
	private void captureBankSnapshot(String rsn)
	{
		try
		{
			List<BankItem> items = collectTradeableBankItems();

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
					currentCooldownMs = BANK_SNAPSHOT_BACKOFF_MS;
				}
				else if (result != null && result.isRateLimited())
				{
					log.debug("Bank snapshot rate limited by server - backing off");
					currentCooldownMs = BANK_SNAPSHOT_BACKOFF_MS;
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
	 * Collect all bank items, resolved to tradeable components, with their GE prices.
	 */
	private List<BankItem> collectTradeableBankItems()
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

		for (Item item : bankItems)
		{
			for (BankItemId part : toTradeableComponents(item.getId(), item.getQuantity()))
			{
				long gePrice = itemManager.getItemPrice(part.itemId);
				if (gePrice > 0)
				{
					items.add(new BankItem(part.itemId, part.quantity, gePrice));
				}
			}
		}

		if (items.isEmpty())
		{
			log.debug("No tradeable items with prices found in bank");
		}

		return items;
	}

	/**
	 * Resolve an item to the tradeable item(s) it is worth. Untradeable variants
	 * (ornament kits, charged weapons, combined items like Avernic treads (max))
	 * expand to their tradeable components via RuneLite's ItemMapping; untradeable
	 * items with no mapping resolve to nothing.
	 */
	List<BankItemId> toTradeableComponents(int id, int quantity)
	{
		if (id <= 0 || quantity <= 0)
		{
			return List.of();
		}
		int itemId = itemManager.canonicalize(id);
		if (itemId == COINS_ITEM_ID || ItemUtils.isTradeable(itemManager, itemId))
		{
			return List.of(new BankItemId(itemId, quantity));
		}
		var mappings = ItemMapping.map(itemId);
		if (mappings == null)
		{
			Integer tradeable = UNMAPPED_VARIANTS.get(itemId);
			return tradeable == null ? List.of() : List.of(new BankItemId(tradeable, quantity));
		}
		return mappings.stream()
			.map(m -> new BankItemId(m.getTradeableItem(), (int) Math.min(Integer.MAX_VALUE, quantity * m.getQuantity())))
			.collect(Collectors.toList());
	}

	/**
	 * Collect tradeable items from the player's inventory.
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
	 * Collect tradeable equipped gear items from the EQUIPMENT container.
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
	 * Resolve an item array to tradeable items (or coins) and return as a list
	 * of BankItemId records (item_id + quantity, no client-side price).
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
			out.addAll(toTradeableComponents(item.getId(), item.getQuantity()));
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

		long itemPrice = itemManager.getItemPrice(offer.getItemId());
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
