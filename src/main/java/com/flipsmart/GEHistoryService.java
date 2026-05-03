package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the in-game GE History tab to recover actual fill prices for trades
 * completed while offline, then posts them to the API as is_history_backfill.
 */
@Slf4j
@Singleton
public class GEHistoryService
{
	private static final int GE_HISTORY_LIST_CHILD = 3;
	private static final int CHILDREN_PER_ROW = 6;
	private static final int ITEM_SPRITE_OFFSET = 2;
	private static final int QUANTITY_TEXT_OFFSET = 4;
	private static final int PRICE_TEXT_OFFSET = 5;
	private static final int BUY_SELL_INDICATOR_OFFSET = 1;

	// Sell-side text colors observed on the History widget
	private static final int[] SELL_COLORS = {0xff0000, 0xd40000, 0xff981f};

	private final Client client;
	private final FlipSmartApiClient apiClient;
	private final PlayerSession session;

	private final Set<Integer> pendingOfflineFillItemIds = ConcurrentHashMap.newKeySet();
	private volatile boolean historyReadThisSession = false;

	@Inject
	public GEHistoryService(Client client, FlipSmartApiClient apiClient, PlayerSession session)
	{
		this.client = client;
		this.apiClient = apiClient;
		this.session = session;
	}

	public void onHistoryWidgetLoaded()
	{
		Widget listWidget = client.getWidget(InterfaceID.GE_HISTORY, GE_HISTORY_LIST_CHILD);
		if (listWidget == null || listWidget.isHidden())
		{
			return;
		}

		historyReadThisSession = true;
		pendingOfflineFillItemIds.clear();

		List<GEHistoryEntry> entries = parseHistoryEntries(listWidget);
		if (entries.isEmpty())
		{
			return;
		}

		log.info("Read {} GE history entries", entries.size());
		backfillOfflineFills(entries);
	}

	public void registerOfflineFill(int itemId)
	{
		if (!historyReadThisSession)
		{
			pendingOfflineFillItemIds.add(itemId);
		}
	}

	public boolean hasUnverifiedOfflineFills()
	{
		return !historyReadThisSession && !pendingOfflineFillItemIds.isEmpty();
	}

	public void reset()
	{
		pendingOfflineFillItemIds.clear();
		historyReadThisSession = false;
	}

	private List<GEHistoryEntry> parseHistoryEntries(Widget listWidget)
	{
		Widget[] children = firstNonEmpty(listWidget.getDynamicChildren(), listWidget.getChildren(), listWidget.getNestedChildren());
		if (children == null)
		{
			return new ArrayList<>();
		}

		List<GEHistoryEntry> entries = (children.length >= CHILDREN_PER_ROW) ? parseFlatLayout(children) : new ArrayList<>();
		if (entries.isEmpty())
		{
			entries = parseContainerLayout(children);
		}
		return entries;
	}

	private List<GEHistoryEntry> parseFlatLayout(Widget[] children)
	{
		List<GEHistoryEntry> entries = new ArrayList<>();
		int rowCount = children.length / CHILDREN_PER_ROW;
		for (int row = 0; row < rowCount; row++)
		{
			int base = row * CHILDREN_PER_ROW;
			tryParseFlatRow(row, children, base, entries);
		}
		return entries;
	}

	private void tryParseFlatRow(int row, Widget[] children, int base, List<GEHistoryEntry> entries)
	{
		try
		{
			Widget itemWidget = safeGet(children, base + ITEM_SPRITE_OFFSET);
			int itemId = (itemWidget != null) ? itemWidget.getItemId() : -1;
			if (itemId <= 0)
			{
				return;
			}
			int quantity = parseQuantity(safeGet(children, base + QUANTITY_TEXT_OFFSET));
			int price = parsePrice(safeGet(children, base + PRICE_TEXT_OFFSET));
			boolean isBuy = parseBuySell(safeGet(children, base + BUY_SELL_INDICATOR_OFFSET));
			if (quantity > 0 && price > 0)
			{
				entries.add(new GEHistoryEntry(itemId, isBuy, quantity, price));
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to parse flat row {}: {}", row, e.getMessage());
		}
	}

	private List<GEHistoryEntry> parseContainerLayout(Widget[] children)
	{
		List<GEHistoryEntry> entries = new ArrayList<>();
		for (int i = 0; i < children.length; i++)
		{
			Widget rowWidget = children[i];
			if (rowWidget != null && !rowWidget.isHidden())
			{
				Widget[] subChildren = rowWidget.getDynamicChildren();
				if (subChildren == null || subChildren.length == 0)
				{
					subChildren = rowWidget.getChildren();
				}
				if (subChildren != null && subChildren.length >= 3)
				{
					tryParseContainerRow(i, subChildren, entries);
				}
			}
		}
		return entries;
	}

	private void tryParseContainerRow(int rowIndex, Widget[] subChildren, List<GEHistoryEntry> entries)
	{
		try
		{
			GEHistoryEntry entry = parseRowFromSubChildren(subChildren);
			if (entry != null)
			{
				entries.add(entry);
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to parse container row {}: {}", rowIndex, e.getMessage());
		}
	}

	private GEHistoryEntry parseRowFromSubChildren(Widget[] subChildren)
	{
		RowAcc acc = new RowAcc();
		for (Widget sub : subChildren)
		{
			if (sub != null)
			{
				absorbSub(sub, acc);
			}
		}
		return acc.toEntry();
	}

	private void absorbSub(Widget sub, RowAcc acc)
	{
		if (sub.getItemId() > 0 && acc.itemId <= 0)
		{
			acc.itemId = sub.getItemId();
			if (sub.getItemQuantity() > 0)
			{
				acc.quantity = sub.getItemQuantity();
			}
		}
		absorbText(sub.getText(), acc);
		if (isSellColor(sub.getTextColor()))
		{
			acc.isBuy = false;
		}
	}

	private static void absorbText(String text, RowAcc acc)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		if (text.contains("gp") || text.contains("coin") || text.contains(","))
		{
			int parsed = parseDigits(text);
			if (parsed > 0) acc.price = parsed;
		}
		else if (acc.quantity <= 0 && text.matches(".*\\d+.*"))
		{
			int parsed = parseDigits(text);
			if (parsed > 0) acc.quantity = parsed;
		}
	}

	private static final class RowAcc
	{
		int itemId = -1;
		int quantity = -1;
		int price = -1;
		boolean isBuy = true;

		GEHistoryEntry toEntry()
		{
			return (itemId > 0 && quantity > 0 && price > 0) ? new GEHistoryEntry(itemId, isBuy, quantity, price) : null;
		}
	}

	private void backfillOfflineFills(List<GEHistoryEntry> entries)
	{
		if (!session.isOfflineSyncCompleted())
		{
			return;
		}
		Optional<String> rsnOpt = session.getRsnSafe();
		if (rsnOpt.isEmpty())
		{
			return;
		}
		String rsn = rsnOpt.get();
		Map<Integer, TrackedOffer> trackedOffers = session.getTrackedOffers();
		Map<Integer, TrackedOffer> matchedOffers = new HashMap<>();

		for (GEHistoryEntry entry : entries)
		{
			tryBackfillEntry(entry, trackedOffers, matchedOffers, rsn);
		}
		if (!matchedOffers.isEmpty())
		{
			log.info("Backfilled {} offline fills with actual prices from GE history", matchedOffers.size());
		}
	}

	private void tryBackfillEntry(
		GEHistoryEntry entry,
		Map<Integer, TrackedOffer> trackedOffers,
		Map<Integer, TrackedOffer> matchedOffers,
		String rsn)
	{
		if (matchedOffers.containsKey(entry.getItemId()))
		{
			return;
		}
		for (TrackedOffer offer : trackedOffers.values())
		{
			if (offer.getItemId() == entry.getItemId()
				&& offer.isBuy() == entry.isBuy()
				&& offer.getPrice() != entry.getPricePerItem())
			{
				matchedOffers.put(entry.getItemId(), offer);
				postBackfill(entry, offer, rsn);
				return;
			}
		}
	}

	private void postBackfill(GEHistoryEntry entry, TrackedOffer offer, String rsn)
	{
		int actualPrice = entry.getPricePerItem();
		log.info("GE History backfill: {} {} — estimated {}gp, actual {}gp",
			entry.isBuy() ? "BUY" : "SELL", offer.getItemName(), offer.getPrice(), actualPrice);
		apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
			.builder(entry.getItemId(), offer.getItemName(), entry.isBuy(), entry.getQuantity(), actualPrice)
			.rsn(rsn)
			.totalQuantity(offer.getTotalQuantity())
			.isHistoryBackfill(true)
			.build());
	}

	private int parseQuantity(Widget widget)
	{
		if (widget == null) return -1;
		if (widget.getItemQuantity() > 0) return widget.getItemQuantity();
		return parseDigits(widget.getText());
	}

	private int parsePrice(Widget widget)
	{
		return (widget == null) ? -1 : parseDigits(widget.getText());
	}

	private boolean parseBuySell(Widget widget)
	{
		if (widget == null) return true;
		if (isSellColor(widget.getTextColor())) return false;
		String text = widget.getText();
		if (text != null)
		{
			String lower = text.toLowerCase();
			if (lower.contains("sold") || lower.contains("sell")) return false;
		}
		return true;
	}

	private static boolean isSellColor(int color)
	{
		for (int c : SELL_COLORS)
		{
			if (color == c) return true;
		}
		return false;
	}

	private static int parseDigits(String text)
	{
		if (text == null) return -1;
		String cleaned = text.replaceAll("\\D", "");
		if (cleaned.isEmpty()) return -1;
		try
		{
			long val = Long.parseLong(cleaned);
			return (val > Integer.MAX_VALUE) ? -1 : (int) val;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static Widget safeGet(Widget[] array, int index)
	{
		return (index >= 0 && index < array.length) ? array[index] : null;
	}

	@SafeVarargs
	private static Widget[] firstNonEmpty(Widget[]... candidates)
	{
		for (Widget[] c : candidates)
		{
			if (c != null && c.length > 0) return c;
		}
		return null;
	}
}
