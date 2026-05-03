package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the in-game GE History tab (interface 383) to extract actual fill prices
 * for trades completed while offline. Cross-references against tracked offers to
 * backfill estimated prices with actual execution prices.
 */
@Slf4j
@Singleton
public class GEHistoryService
{
	private static final int GE_HISTORY_LIST_CHILD = 3;

	/**
	 * Number of dynamic child widgets per history row.
	 * Discovered via Widget Inspector — adjust if the game updates the layout.
	 */
	private static final int CHILDREN_PER_ROW = 6;

	/**
	 * Child offset within each row for the item sprite (carries itemId).
	 * Set to -1 until discovered via in-game Widget Inspector.
	 */
	private static final int ITEM_SPRITE_OFFSET = 2;

	/**
	 * Child offset within each row for the quantity text.
	 */
	private static final int QUANTITY_TEXT_OFFSET = 4;

	/**
	 * Child offset within each row for the price text.
	 */
	private static final int PRICE_TEXT_OFFSET = 5;

	/**
	 * Child offset within each row for the buy/sell indicator.
	 * Buy entries use a different sprite or text color than sell entries.
	 */
	private static final int BUY_SELL_INDICATOR_OFFSET = 1;

	private final Client client;
	private final FlipSmartApiClient apiClient;
	private final PlayerSession session;

	private boolean widgetStructureLogged = false;
	private long lastReadAtMillis = 0;
	private List<GEHistoryEntry> lastReadEntries = Collections.emptyList();

	private final Set<Integer> pendingOfflineFillItemIds = ConcurrentHashMap.newKeySet();
	private volatile boolean historyReadThisSession = false;

	@Inject
	public GEHistoryService(
		Client client,
		FlipSmartApiClient apiClient,
		PlayerSession session)
	{
		this.client = client;
		this.apiClient = apiClient;
		this.session = session;
	}

	/**
	 * Called when the GE History widget (group 383) is loaded.
	 * Reads history entries and cross-references with tracked offers.
	 */
	public void onHistoryWidgetLoaded()
	{
		Widget listWidget = client.getWidget(InterfaceID.GE_HISTORY, GE_HISTORY_LIST_CHILD);
		if (listWidget == null || listWidget.isHidden())
		{
			log.debug("GE History list widget not available");
			return;
		}

		if (!widgetStructureLogged)
		{
			logWidgetStructure(listWidget);
			widgetStructureLogged = true;
		}

		historyReadThisSession = true;
		pendingOfflineFillItemIds.clear();

		List<GEHistoryEntry> entries = parseHistoryEntries(listWidget);
		if (entries.isEmpty())
		{
			log.debug("No history entries parsed from GE History tab (widget structure may need calibration)");
			return;
		}

		lastReadEntries = entries;
		lastReadAtMillis = System.currentTimeMillis();

		log.info("Read {} GE history entries", entries.size());
		for (GEHistoryEntry entry : entries)
		{
			log.debug("  {}", entry);
		}

		backfillOfflineFills(entries);
	}

	/**
	 * Register that an offline fill was detected for the given item.
	 * Called by OfflineSyncService when it reports estimated offline fills.
	 */
	public void registerOfflineFill(int itemId)
	{
		if (!historyReadThisSession)
		{
			pendingOfflineFillItemIds.add(itemId);
		}
	}

	/**
	 * Returns true if there are offline fills that haven't been verified
	 * against GE history yet. Used by the Flip Assist overlay to prompt
	 * the player to open the History tab.
	 */
	public boolean hasUnverifiedOfflineFills()
	{
		return !historyReadThisSession && !pendingOfflineFillItemIds.isEmpty();
	}

	/**
	 * Reset state on logout/session end.
	 */
	public void reset()
	{
		pendingOfflineFillItemIds.clear();
		historyReadThisSession = false;
		widgetStructureLogged = false;
		lastReadEntries = Collections.emptyList();
		lastReadAtMillis = 0;
	}

	/**
	 * Dump the widget tree structure for development/debugging.
	 * Run once per session to discover the exact child layout.
	 */
	private void logWidgetStructure(Widget listWidget)
	{
		log.info("=== GE History Widget Structure (group {}, child {}) ===",
			InterfaceID.GE_HISTORY, GE_HISTORY_LIST_CHILD);
		log.info("Widget type={}, width={}, height={}, text='{}'",
			listWidget.getType(), listWidget.getWidth(), listWidget.getHeight(), listWidget.getText());

		Widget[] dynamicChildren = listWidget.getDynamicChildren();
		if (dynamicChildren == null || dynamicChildren.length == 0)
		{
			log.info("No dynamic children found - trying getChildren()");
			Widget[] children = listWidget.getChildren();
			if (children != null)
			{
				log.info("getChildren() returned {} entries (some may be null)", children.length);
				logChildWidgets(children, "getChildren");
			}
			else
			{
				log.info("No children found at all. Trying static/nested children...");
				Widget[] staticChildren = listWidget.getStaticChildren();
				if (staticChildren != null && staticChildren.length > 0)
				{
					log.info("staticChildren: {} entries", staticChildren.length);
					logChildWidgets(staticChildren, "static");
				}
				Widget[] nestedChildren = listWidget.getNestedChildren();
				if (nestedChildren != null && nestedChildren.length > 0)
				{
					log.info("nestedChildren: {} entries", nestedChildren.length);
					logChildWidgets(nestedChildren, "nested");
				}
			}
		}
		else
		{
			log.info("dynamicChildren: {} entries", dynamicChildren.length);
			logChildWidgets(dynamicChildren, "dynamic");
		}
		log.info("=== End GE History Widget Structure ===");
	}

	private void logChildWidgets(Widget[] children, String source)
	{
		int logged = 0;
		for (int i = 0; i < children.length && logged < 30; i++)
		{
			Widget child = children[i];
			if (child == null)
			{
				continue;
			}
			log.info("  [{}] {}[{}]: type={}, itemId={}, itemQty={}, text='{}', spriteId={}, " +
					"textColor={}, width={}, height={}, hidden={}",
				logged, source, i,
				child.getType(),
				child.getItemId(),
				child.getItemQuantity(),
				truncate(child.getText(), 40),
				child.getSpriteId(),
				child.getTextColor(),
				child.getWidth(),
				child.getHeight(),
				child.isHidden());

			Widget[] subChildren = child.getDynamicChildren();
			if (subChildren != null && subChildren.length > 0)
			{
				for (int j = 0; j < Math.min(subChildren.length, 8); j++)
				{
					Widget sub = subChildren[j];
					if (sub == null) continue;
					log.info("    [{}][{}]: type={}, itemId={}, itemQty={}, text='{}', spriteId={}, textColor={}",
						i, j, sub.getType(), sub.getItemId(), sub.getItemQuantity(),
						truncate(sub.getText(), 40), sub.getSpriteId(), sub.getTextColor());
				}
			}

			logged++;
		}
	}

	/**
	 * Parse history entries from the widget's dynamic children.
	 *
	 * The GE history list uses flat dynamic children where every CHILDREN_PER_ROW
	 * widgets represent one trade entry. The exact offsets (ITEM_SPRITE_OFFSET, etc.)
	 * were determined via Widget Inspector. If parsing fails, check the debug log
	 * from logWidgetStructure() and adjust the offsets.
	 */
	private List<GEHistoryEntry> parseHistoryEntries(Widget listWidget)
	{
		Widget[] dynamicChildren = listWidget.getDynamicChildren();
		if (dynamicChildren == null || dynamicChildren.length == 0)
		{
			Widget[] children = listWidget.getChildren();
			if (children == null || children.length == 0)
			{
				return tryNestedParsing(listWidget);
			}
			dynamicChildren = children;
		}

		List<GEHistoryEntry> entries = new ArrayList<>();

		// First try: flat layout where every N children = one row
		if (dynamicChildren.length >= CHILDREN_PER_ROW)
		{
			entries = parseFlatLayout(dynamicChildren);
		}

		// Second try: each child is a row container with its own sub-children
		if (entries.isEmpty())
		{
			entries = parseContainerLayout(dynamicChildren);
		}

		return entries;
	}

	/**
	 * Flat layout: dynamic children are a flat array, every CHILDREN_PER_ROW widgets = one row.
	 */
	private List<GEHistoryEntry> parseFlatLayout(Widget[] children)
	{
		List<GEHistoryEntry> entries = new ArrayList<>();
		int rowCount = children.length / CHILDREN_PER_ROW;

		for (int row = 0; row < rowCount; row++)
		{
			int baseIdx = row * CHILDREN_PER_ROW;

			try
			{
				Widget itemWidget = safeGet(children, baseIdx + ITEM_SPRITE_OFFSET);
				Widget qtyWidget = safeGet(children, baseIdx + QUANTITY_TEXT_OFFSET);
				Widget priceWidget = safeGet(children, baseIdx + PRICE_TEXT_OFFSET);
				Widget buySellWidget = safeGet(children, baseIdx + BUY_SELL_INDICATOR_OFFSET);

				if (itemWidget == null)
				{
					continue;
				}

				int itemId = itemWidget.getItemId();
				if (itemId <= 0)
				{
					continue;
				}

				int quantity = parseQuantity(qtyWidget);
				int price = parsePrice(priceWidget);
				boolean isBuy = parseBuySell(buySellWidget);

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

		return entries;
	}

	/**
	 * Container layout: each top-level child is a row with its own sub-children.
	 */
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
		int itemId = -1;
		int quantity = -1;
		int price = -1;
		boolean isBuy = true;

		for (Widget sub : subChildren)
		{
			if (sub == null) continue;

			if (sub.getItemId() > 0 && itemId <= 0)
			{
				itemId = sub.getItemId();
				if (sub.getItemQuantity() > 0)
				{
					quantity = sub.getItemQuantity();
				}
			}

			String text = sub.getText();
			if (text != null && !text.isEmpty())
			{
				if (text.contains("gp") || text.contains("coin") || text.contains(","))
				{
					int parsed = parseGpString(text);
					if (parsed > 0) price = parsed;
				}
				else if (text.matches(".*\\d+.*") && quantity <= 0)
				{
					int parsed = parseNumericString(text);
					if (parsed > 0) quantity = parsed;
				}
			}

			if (sub.getTextColor() == 0x00ff00 || sub.getTextColor() == 0x0dc10d)
			{
				isBuy = true;
			}
			else if (sub.getTextColor() == 0xff0000 || sub.getTextColor() == 0xd40000)
			{
				isBuy = false;
			}
		}

		if (itemId > 0 && quantity > 0 && price > 0)
		{
			return new GEHistoryEntry(itemId, isBuy, quantity, price);
		}
		return null;
	}

	/**
	 * Fallback: try nested children of the list widget.
	 */
	private List<GEHistoryEntry> tryNestedParsing(Widget listWidget)
	{
		Widget[] nested = listWidget.getNestedChildren();
		if (nested != null && nested.length > 0)
		{
			return parseContainerLayout(nested);
		}
		return Collections.emptyList();
	}

	/**
	 * Cross-reference history entries with tracked offers to find offline fills
	 * where we estimated the price. Re-report with actual prices from history.
	 */
	private void backfillOfflineFills(List<GEHistoryEntry> entries)
	{
		if (!session.isOfflineSyncCompleted())
		{
			log.debug("Offline sync not yet completed — skipping backfill");
			return;
		}

		Optional<String> rsnOpt = session.getRsnSafe();
		if (rsnOpt.isEmpty())
		{
			log.debug("No RSN available — skipping backfill");
			return;
		}
		String rsn = rsnOpt.get();

		Map<Integer, TrackedOffer> trackedOffers = session.getTrackedOffers();
		Map<Integer, TrackedOffer> matchedOffers = new HashMap<>();

		for (GEHistoryEntry entry : entries)
		{
			for (Map.Entry<Integer, TrackedOffer> offerEntry : trackedOffers.entrySet())
			{
				TrackedOffer offer = offerEntry.getValue();
				if (offer.getItemId() != entry.getItemId() || offer.isBuy() != entry.isBuy())
				{
					continue;
				}

				int estimatedPrice = offer.getPrice();
				int actualPrice = entry.getPricePerItem();

				if (estimatedPrice != actualPrice && !matchedOffers.containsKey(entry.getItemId()))
				{
					matchedOffers.put(entry.getItemId(), offer);
					log.info("GE History backfill: {} {} — estimated {}gp, actual {}gp (delta: {}gp)",
						entry.isBuy() ? "BUY" : "SELL",
						offer.getItemName(),
						estimatedPrice, actualPrice, actualPrice - estimatedPrice);

					apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
						.builder(entry.getItemId(), offer.getItemName(), entry.isBuy(),
							entry.getQuantity(), actualPrice)
						.rsn(rsn)
						.totalQuantity(offer.getTotalQuantity())
						.isHistoryBackfill(true)
						.build());
				}
			}
		}

		if (matchedOffers.isEmpty())
		{
			log.debug("No offline fills to backfill from GE history");
		}
		else
		{
			log.info("Backfilled {} offline fills with actual prices from GE history", matchedOffers.size());
		}
	}

	public List<GEHistoryEntry> getLastReadEntries()
	{
		return Collections.unmodifiableList(lastReadEntries);
	}

	public long getLastReadAtMillis()
	{
		return lastReadAtMillis;
	}

	// =====================
	// Parsing Helpers
	// =====================

	private int parseQuantity(Widget widget)
	{
		if (widget == null) return -1;
		if (widget.getItemQuantity() > 0) return widget.getItemQuantity();
		return parseNumericString(widget.getText());
	}

	private int parsePrice(Widget widget)
	{
		if (widget == null) return -1;
		String text = widget.getText();
		if (text == null || text.isEmpty()) return -1;
		return parseGpString(text);
	}

	private boolean parseBuySell(Widget widget)
	{
		if (widget == null) return true;

		int color = widget.getTextColor();
		if (color == 0xff0000 || color == 0xd40000 || color == 0xff981f)
		{
			return false;
		}

		String text = widget.getText();
		if (text != null)
		{
			String lower = text.toLowerCase();
			if (lower.contains("sold") || lower.contains("sell"))
			{
				return false;
			}
		}

		int spriteId = widget.getSpriteId();
		if (spriteId > 0)
		{
			// Sell sprites typically have a red/orange arrow
			// This may need adjustment after Widget Inspector discovery
		}

		return true;
	}

	private int parseGpString(String text)
	{
		if (text == null) return -1;
		String cleaned = text.replaceAll("[^0-9]", "");
		if (cleaned.isEmpty()) return -1;
		try
		{
			long val = Long.parseLong(cleaned);
			return val > Integer.MAX_VALUE ? -1 : (int) val;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private int parseNumericString(String text)
	{
		if (text == null) return -1;
		String cleaned = text.replaceAll("[^0-9]", "");
		if (cleaned.isEmpty()) return -1;
		try
		{
			return Integer.parseInt(cleaned);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private Widget safeGet(Widget[] array, int index)
	{
		if (index >= 0 && index < array.length)
		{
			return array[index];
		}
		return null;
	}

	private String truncate(String text, int maxLen)
	{
		if (text == null) return "";
		return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
	}
}
