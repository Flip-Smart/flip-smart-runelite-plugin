package com.flipsmart;
import com.flipsmart.api.dto.HistoryBackfillEntry;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads the in-game GE History tab to recover actual fill prices for trades
 * completed while offline, then posts them to the API as is_history_backfill.
 */
@Slf4j
@Singleton
public class GEHistoryService
{
	// Row layout in the GE History list (child 3 of group 383): each entry
	// is anchored by a "Bought:" / "Sold:" header widget; the next item
	// sprite (carries itemId + itemQuantity natively) and the next text
	// containing "each" (the per-item price) within the following ~6
	// children belong to that header. We walk children rather than
	// fixed-stride to handle variable-width rows.
	private static final int GE_HISTORY_LIST_CHILD = 3;

	private final Client client;
	private final FlipSmartApiClient apiClient;
	private final PlayerSession session;
	private final ChatMessageManager chatMessageManager;
	private final OfferStore offerStore;

	private final Set<Integer> pendingOfflineFillItemIds = ConcurrentHashMap.newKeySet();
	private final Map<Integer, OfferRecord> recentlyPersistedOffers = new ConcurrentHashMap<>();
	private final AtomicInteger pendingHistoryReadTicks = new AtomicInteger(0);
	private volatile boolean historyReadThisSession = false;
	private volatile boolean chatPromptSent = false;
	private volatile Runnable onBackfillComplete;

	@Inject
	public GEHistoryService(
		Client client,
		FlipSmartApiClient apiClient,
		PlayerSession session,
		ChatMessageManager chatMessageManager,
		OfferStore offerStore)
	{
		this.client = client;
		this.apiClient = apiClient;
		this.session = session;
		this.chatMessageManager = chatMessageManager;
		this.offerStore = offerStore;
	}

	public void onHistoryWidgetLoaded()
	{
		// WidgetLoaded fires when the interface is *created*, but list rows are
		// populated by clientscripts that run afterward. Defer two ticks so the
		// scripts have time to write the row widgets before we scan them.
		pendingHistoryReadTicks.set(2);
	}

	/** Called from FlipSmartPlugin.onGameTick. */
	public void onGameTick()
	{
		int prev = pendingHistoryReadTicks.get();
		if (prev <= 0)
		{
			return;
		}
		if (pendingHistoryReadTicks.decrementAndGet() == 0)
		{
			readHistoryNow();
		}
	}

	private void readHistoryNow()
	{
		// historyReadThisSession alone gates hasUnverifiedOfflineFills() and
		// further registerOfflineFill calls; pendingOfflineFillItemIds is
		// allowed to stay populated until reset() so future reads (e.g. user
		// reopens History) can still see what we'd registered.
		historyReadThisSession = true;

		Widget listWidget = client.getWidget(InterfaceID.GE_HISTORY, GE_HISTORY_LIST_CHILD);
		if (listWidget == null)
		{
			return;
		}

		List<GEHistoryEntry> entries = parseHistoryEntries(listWidget);
		if (entries.isEmpty())
		{
			return;
		}

		log.debug("Read {} GE history entries", entries.size());
		backfillOfflineFills(entries);
	}

	public void registerOfflineFill(int itemId)
	{
		if (!historyReadThisSession && pendingOfflineFillItemIds.add(itemId))
		{
			log.debug("Registered offline fill for itemId={} (pending count {})",
				itemId, pendingOfflineFillItemIds.size());
			maybeSendChatPrompt();
		}
	}

	private void maybeSendChatPrompt()
	{
		if (chatPromptSent)
		{
			return;
		}
		chatPromptSent = true;
		String msg = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[FlipSmart] ")
			.append(ChatColorType.NORMAL)
			.append("Offline trades detected. Open the Grand Exchange and click the ")
			.append(ChatColorType.HIGHLIGHT)
			.append("History")
			.append(ChatColorType.NORMAL)
			.append(" tab to recover their actual fill prices.")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(msg)
			.build());
	}

	/**
	 * Runs after a History-backfill batch lands on the API. Used by the
	 * plugin to refresh Active Flips / Completed panels so backfilled trades
	 * appear without the user having to manually reload.
	 */
	public void setOnBackfillComplete(Runnable callback)
	{
		this.onBackfillComplete = callback;
	}

	/**
	 * Snapshot of offers persisted at the previous session's logout — used
	 * for item-name resolution in the backfill payload, and as the trigger
	 * for the chat prompt (any persisted state means there might be offline
	 * activity worth backfilling).
	 */
	public void setRecentlyPersistedOffers(List<OfferRecord> offers)
	{
		recentlyPersistedOffers.clear();
		if (offers != null)
		{
			for (OfferRecord o : offers)
			{
				recentlyPersistedOffers.put(o.getItemId(), o);
			}
		}
		// Any prior-session activity is reason enough to prompt the user to
		// open History. Backend dedup makes the backfill safe regardless:
		// covered rows dedupe, missing ones get inserted.
		if (!recentlyPersistedOffers.isEmpty())
		{
			maybeSendChatPrompt();
		}
	}

	public boolean hasUnverifiedOfflineFills()
	{
		// Prompt the overlay banner whenever we haven't yet read the History
		// this session and there's *any* signal that offline activity might
		// have happened — either the narrow per-item detection registered
		// something, or the user simply had persisted offer state from the
		// prior session.
		return !historyReadThisSession && (!pendingOfflineFillItemIds.isEmpty() || !recentlyPersistedOffers.isEmpty());
	}

	public void reset()
	{
		pendingOfflineFillItemIds.clear();
		recentlyPersistedOffers.clear();
		historyReadThisSession = false;
		pendingHistoryReadTicks.set(0);
		chatPromptSent = false;
	}

	private List<GEHistoryEntry> parseHistoryEntries(Widget listWidget)
	{
		Widget[] children = firstNonEmpty(listWidget.getDynamicChildren(), listWidget.getChildren(), listWidget.getNestedChildren());
		if (children == null)
		{
			return new ArrayList<>();
		}

		// Walk the children looking for "Bought:" / "Sold:" header markers.
		// Each header anchors a row; the item sprite (carries itemId+qty) and
		// the price text follow within the next ~6 widgets. This is robust to
		// variable per-row stride that breaks fixed-offset indexing.
		List<GEHistoryEntry> entries = new ArrayList<>();
		for (int i = 0; i < children.length; i++)
		{
			Boolean isBuy = headerToIsBuy(children[i]);
			if (isBuy != null)
			{
				tryParseRowAt(children, i, isBuy, entries);
			}
		}
		return entries;
	}

	/** Returns TRUE for "Bought:", FALSE for "Sold:", null otherwise. */
	private static Boolean headerToIsBuy(Widget w)
	{
		if (w == null) return null;
		String text = w.getText();
		if ("Bought:".equals(text)) return Boolean.TRUE;
		if ("Sold:".equals(text)) return Boolean.FALSE;
		return null;
	}

	private void tryParseRowAt(Widget[] children, int headerIdx, boolean isBuy, List<GEHistoryEntry> entries)
	{
		RowScan scan = scanRowFrom(children, headerIdx);
		if (scan.itemWidget == null)
		{
			log.debug("[GEHistory] dropped row at headerIdx={} isBuy={} — no itemWidget", headerIdx, isBuy);
			return;
		}
		int itemId = scan.itemWidget.getItemId();
		// RuneLite's Widget.getItemQuantity() returns 0 for single-item sprites
		// because the GE clientscript does not write a quantity badge when qty
		// would visually be "1". Default to 1 when we have a valid itemId but
		// no reported quantity — that is the only context this triggers.
		int rawQty = scan.itemWidget.getItemQuantity();
		int qty = (rawQty > 0) ? rawQty : (itemId > 0 ? 1 : 0);
		int price;
		String pricePath;
		if (scan.priceWidget != null)
		{
			price = parsePerItemPrice(scan.priceWidget);
			pricePath = "each";
		}
		else if (scan.coinsWidget != null && qty > 0)
		{
			// Single-quantity GE History rows omit the "= N each" line because
			// there's nothing to break out — the displayed total IS the per-item
			// price. Fall back to the "X,XXX coins" widget and divide by qty.
			// See Flip-Smart/flip-smart#650.
			int total = parseLeadingTotal(scan.coinsWidget);
			price = (total > 0) ? total / qty : -1;
			pricePath = "coins-fallback(total=" + total + ")";
		}
		else
		{
			log.debug("[GEHistory] dropped row at headerIdx={} isBuy={} itemId={} rawQty={} — "
					+ "no priceWidget AND (no coinsWidget OR qty<=0)",
				headerIdx, isBuy, itemId, rawQty);
			return;
		}
		if (qty > 0 && price > 0)
		{
			entries.add(new GEHistoryEntry(itemId, isBuy, qty, price));
			log.debug("[GEHistory] parsed row isBuy={} itemId={} qty={} price={} via {}",
				isBuy, itemId, qty, price, pricePath);
		}
		else
		{
			log.debug("[GEHistory] dropped row at headerIdx={} isBuy={} itemId={} qty={} price={} via {}",
				headerIdx, isBuy, itemId, qty, price, pricePath);
		}
	}

	/**
	 * Scan the children starting just after a header, capturing the first
	 * item-bearing widget and the first "X each" price text. Stops at the
	 * next header so we never bleed into the following row.
	 */
	private static RowScan scanRowFrom(Widget[] children, int headerIdx)
	{
		RowScan scan = new RowScan();
		int end = Math.min(children.length, headerIdx + 8);
		for (int j = headerIdx + 1; j < end; j++)
		{
			Widget w = children[j];
			if (w != null && !absorbWidget(w, scan))
			{
				break;
			}
		}
		return scan;
	}

	/** Returns false if the next-header marker is hit (caller should stop). */
	private static boolean absorbWidget(Widget w, RowScan scan)
	{
		String text = w.getText();
		if ("Bought:".equals(text) || "Sold:".equals(text))
		{
			return false;
		}
		if (scan.itemWidget == null && w.getItemId() > 0)
		{
			scan.itemWidget = w;
		}
		if (text != null)
		{
			if (scan.priceWidget == null && text.contains("each"))
			{
				scan.priceWidget = w;
			}
			else if (scan.coinsWidget == null && text.contains("coins"))
			{
				// Single-qty rows have a "X,XXX coins" widget but no "each" line.
				// Captured for the qty=1 fallback in tryParseRowAt.
				scan.coinsWidget = w;
			}
		}
		return true;
	}

	private static final class RowScan
	{
		Widget itemWidget;
		Widget priceWidget;
		Widget coinsWidget;
	}

	/** Price text is "<col=...>X coins</col><br>= Y each" — the per-item price is after the '='. */
	private static int parsePerItemPrice(Widget widget)
	{
		return (widget == null) ? -1 : parsePerItemPriceFromText(widget.getText());
	}

	/** String-level entry point for {@link #parsePerItemPrice}, package-private
	 *  so unit tests can exercise the parsing logic without mocking
	 *  {@link Widget}.
	 *  <p>
	 *  Strips HTML tags <em>before</em> seeking the '=' separator. If the seek
	 *  runs on the raw text first, the '=' inside {@code <col=ffb83f>} can win
	 *  the {@code lastIndexOf('=')} race (when no plain-text '=' exists in the
	 *  row), the substring starts mid-tag at {@code "=ffb83f>…"}, and
	 *  {@link #stripHtmlTags} can no longer strip the fragment because its
	 *  regex requires a leading '<'. The orphaned hex digits then leak into
	 *  {@link #parseDigits} and concatenate with the trailing quantity —
	 *  the {@code 83 × 10^n + qty} corruption pattern observed in production
	 *  (Flip-Smart/flip-smart#689). Tag-stripping first removes that whole
	 *  failure mode regardless of which widget shape RuneLite returns. */
	static int parsePerItemPriceFromText(String text)
	{
		if (text == null || text.isEmpty()) return -1;
		String cleaned = stripHtmlTags(text);
		int eqIdx = cleaned.lastIndexOf('=');
		String slice = (eqIdx >= 0) ? cleaned.substring(eqIdx) : cleaned;
		return parseDigits(slice);
	}

	/** Leading total from a "X,XXX coins[(gross - tax)]" widget, ignoring any
	 *  parenthesized tax breakdown so parseDigits doesn't concatenate them. */
	private static int parseLeadingTotal(Widget widget)
	{
		return (widget == null) ? -1 : parseLeadingTotalFromText(widget.getText());
	}

	/** String-level entry point for {@link #parseLeadingTotal}, package-private
	 *  for the same testing reason as {@link #parsePerItemPriceFromText}.
	 *  Same tag-strip-first discipline: today the raw widget text always wraps
	 *  the parenthesized tax breakdown inside the same {@code <col>} as the
	 *  total, so the {@code indexOf('(')} seek would produce the correct head
	 *  either way — but ordering matters as soon as RuneLite changes the row
	 *  shape, so we strip first defensively. */
	static int parseLeadingTotalFromText(String text)
	{
		if (text == null || text.isEmpty()) return -1;
		String cleaned = stripHtmlTags(text);
		int parenIdx = cleaned.indexOf('(');
		String head = (parenIdx >= 0) ? cleaned.substring(0, parenIdx) : cleaned;
		return parseDigits(head);
	}

	/** Strip HTML-like tags (e.g. {@code <col=ffb83f>}, {@code <br>}, {@code </col>})
	 *  before parsing digits, so hex characters inside color codes don't pollute
	 *  the result. parseDigits is digit-greedy and would otherwise concatenate
	 *  digits found inside attribute values like "ff<b>83</b>f" into the price. */
	private static String stripHtmlTags(String text)
	{
		return (text == null) ? "" : text.replaceAll("<[^>]*>", "");
	}

	private void backfillOfflineFills(List<GEHistoryEntry> entries)
	{
		if (!session.isOfflineSyncCompleted() || entries.isEmpty())
		{
			return;
		}
		Optional<String> rsnOpt = session.getRsnSafe();
		if (rsnOpt.isEmpty())
		{
			return;
		}
		String rsn = rsnOpt.get();

		// Resolve item names from live + persisted-offer state when available.
		// The backend tolerates null but resolves from prior transactions on
		// its side too; this just gives nicer logs.
		Map<Integer, String> nameByItem = new HashMap<>();
		for (OfferRecord o : offerStore.allRecords()) nameByItem.put(o.getItemId(), o.getItemName());
		for (OfferRecord o : recentlyPersistedOffers.values()) nameByItem.putIfAbsent(o.getItemId(), o.getItemName());

		List<HistoryBackfillEntry> batch = new ArrayList<>(entries.size());
		for (GEHistoryEntry e : entries)
		{
			batch.add(new HistoryBackfillEntry(
				e.getItemId(), nameByItem.get(e.getItemId()),
				e.isBuy(), e.getQuantity(), e.getPricePerItem()
			));
		}

		log.debug("Posting {} GE history rows for batch reconciliation", batch.size());
		apiClient.recordHistoryBackfillBatchAsync(rsn, batch)
			.whenComplete((v, ex) ->
			{
				if (ex != null)
				{
					log.warn("History backfill batch failed: {}", ex.getMessage());
					return;
				}
				Runnable cb = onBackfillComplete;
				if (cb != null)
				{
					try { cb.run(); }
					catch (Exception e) { log.debug("onBackfillComplete callback threw: {}", e.getMessage()); }
				}
			});
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
