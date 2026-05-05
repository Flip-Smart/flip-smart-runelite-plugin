package com.flipsmart;

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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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

	private final Set<Integer> pendingOfflineFillItemIds = ConcurrentHashMap.newKeySet();
	private final Map<Integer, TrackedOffer> recentlyPersistedOffers = new ConcurrentHashMap<>();
	private volatile boolean historyReadThisSession = false;
	private volatile boolean widgetStructureLogged = false;
	private volatile int pendingHistoryReadTicks = 0;
	private volatile boolean chatPromptSent = false;
	private volatile Runnable onBackfillComplete;

	@Inject
	public GEHistoryService(
		Client client,
		FlipSmartApiClient apiClient,
		PlayerSession session,
		ChatMessageManager chatMessageManager)
	{
		this.client = client;
		this.apiClient = apiClient;
		this.session = session;
		this.chatMessageManager = chatMessageManager;
	}

	public void onHistoryWidgetLoaded()
	{
		log.info("GE History widget loaded (group {})", InterfaceID.GE_HISTORY);
		// WidgetLoaded fires when the interface is *created*, but list rows are
		// populated by clientscripts that run afterward. Defer two ticks so the
		// scripts have time to write the row widgets before we scan them.
		pendingHistoryReadTicks = 2;
	}

	/**
	 * Called from FlipSmartPlugin.onGameTick. Counts down ticks after WidgetLoaded
	 * to give clientscripts time to populate the list rows before we read them.
	 */
	public void onGameTick()
	{
		if (pendingHistoryReadTicks <= 0)
		{
			return;
		}
		pendingHistoryReadTicks--;
		if (pendingHistoryReadTicks > 0)
		{
			return;
		}
		readHistoryNow();
	}

	private void readHistoryNow()
	{
		// Don't clear pendingOfflineFillItemIds yet — backfillOfflineFills
		// reads from it to decide which History rows to post. Clearing too
		// early skips every match. historyReadThisSession alone is enough
		// to flip hasUnverifiedOfflineFills() to false (it ANDs both) and
		// to gate further registerOfflineFill calls.
		historyReadThisSession = true;

		if (!widgetStructureLogged)
		{
			dumpHistoryGroup();
			widgetStructureLogged = true;
		}

		Widget listWidget = client.getWidget(InterfaceID.GE_HISTORY, GE_HISTORY_LIST_CHILD);
		if (listWidget == null)
		{
			log.info("Deferred read: list widget at child {} still null", GE_HISTORY_LIST_CHILD);
			return;
		}

		List<GEHistoryEntry> entries = parseHistoryEntries(listWidget);
		if (entries.isEmpty())
		{
			log.info("Deferred read: parsed 0 history entries from child {} — see group dump above to recalibrate offsets",
				GE_HISTORY_LIST_CHILD);
			return;
		}

		log.info("Read {} GE history entries", entries.size());
		for (GEHistoryEntry entry : entries)
		{
			log.debug("  parsed: {}", entry);
		}
		backfillOfflineFills(entries);
	}

	/** Dump every child index 0..47 of group GE_HISTORY so we can see where row data actually lives. */
	private void dumpHistoryGroup()
	{
		log.info("=== Dumping all children of GE_HISTORY group {} ===", InterfaceID.GE_HISTORY);
		for (int idx = 0; idx < 48; idx++)
		{
			Widget w = client.getWidget(InterfaceID.GE_HISTORY, idx);
			if (w == null) continue;
			Widget[] dyn = w.getDynamicChildren();
			Widget[] kid = w.getChildren();
			Widget[] nest = w.getNestedChildren();
			int dynN = dyn == null ? 0 : dyn.length;
			int kidN = kid == null ? -1 : kid.length;
			int nestN = nest == null ? 0 : nest.length;
			log.info("  child[{}]: type={} w={} h={} hidden={} text='{}' itemId={} sprite={} dyn={} kid={} nest={}",
				idx, w.getType(), w.getWidth(), w.getHeight(), w.isHidden(),
				w.getText() == null ? "" : w.getText(),
				w.getItemId(), w.getSpriteId(),
				dynN, kidN, nestN);
			Widget[] sample = firstNonEmpty(dyn, kid, nest);
			if (sample != null && sample.length > 0)
			{
				// Cap at 80 children for the row-list child (idx==3) so we see
				// 10+ rows and can spot variable-stride layouts. Other children
				// stay at 8 to keep noise down.
				int max = Math.min(sample.length, idx == GE_HISTORY_LIST_CHILD ? 80 : 8);
				for (int j = 0; j < max; j++)
				{
					Widget c = sample[j];
					if (c == null) continue;
					log.info("    [{}.{}]: type={} itemId={} qty={} text='{}' color={}",
						idx, j, c.getType(), c.getItemId(), c.getItemQuantity(),
						c.getText() == null ? "" : c.getText(),
						Integer.toHexString(c.getTextColor()));
				}
			}
		}
		log.info("=== end group dump ===");
	}

	public void registerOfflineFill(int itemId)
	{
		if (!historyReadThisSession)
		{
			boolean added = pendingOfflineFillItemIds.add(itemId);
			if (added)
			{
				log.info("GE History: registered offline fill for itemId={} (pending count {})",
					itemId, pendingOfflineFillItemIds.size());
				maybeSendChatPrompt();
			}
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
			.append("[Flip Smart] ")
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
	 * Snapshot of offers persisted at the previous session's logout — used as
	 * additional anchors when backfilling, so fully-completed offline trades
	 * (whose live TrackedOffer no longer exists) can still be matched by
	 * itemId + isBuy and have their estimated price replaced.
	 */
	/**
	 * Runs after a History-backfill batch lands on the API. Used by the
	 * plugin to refresh Active Flips / Completed panels so backfilled trades
	 * appear without the user having to manually reload.
	 */
	public void setOnBackfillComplete(Runnable callback)
	{
		this.onBackfillComplete = callback;
	}

	public void setRecentlyPersistedOffers(Map<Integer, TrackedOffer> offers)
	{
		recentlyPersistedOffers.clear();
		if (offers != null)
		{
			recentlyPersistedOffers.putAll(offers);
		}
		log.info("GE History: snapshot loaded with {} persisted offers", recentlyPersistedOffers.size());
	}

	public boolean hasUnverifiedOfflineFills()
	{
		return !historyReadThisSession && !pendingOfflineFillItemIds.isEmpty();
	}

	public void reset()
	{
		pendingOfflineFillItemIds.clear();
		recentlyPersistedOffers.clear();
		historyReadThisSession = false;
		widgetStructureLogged = false;
		pendingHistoryReadTicks = 0;
		chatPromptSent = false;
	}

	private List<GEHistoryEntry> parseHistoryEntries(Widget listWidget)
	{
		Widget[] children = firstNonEmpty(listWidget.getDynamicChildren(), listWidget.getChildren(), listWidget.getNestedChildren());
		if (children == null)
		{
			log.info("GE History list widget has no dynamic/child/nested children at all");
			return new ArrayList<>();
		}
		log.debug("GE History parser: found {} children to scan", children.length);

		// Walk the children looking for "Bought:" / "Sold:" header markers.
		// Each header anchors a row; the item sprite (carries itemId+qty) and
		// the price text follow within the next ~6 widgets. This is robust to
		// variable per-row stride that breaks fixed-offset indexing.
		List<GEHistoryEntry> entries = new ArrayList<>();
		for (int i = 0; i < children.length; i++)
		{
			Widget w = children[i];
			if (w == null) continue;
			String text = w.getText();
			if (text == null) continue;
			boolean isBuy;
			if ("Bought:".equals(text)) isBuy = true;
			else if ("Sold:".equals(text)) isBuy = false;
			else continue;
			tryParseRowAt(children, i, isBuy, entries);
		}
		return entries;
	}

	private void tryParseRowAt(Widget[] children, int headerIdx, boolean isBuy, List<GEHistoryEntry> entries)
	{
		Widget itemWidget = null;
		Widget priceWidget = null;
		// Scan the next several widgets after the header; stop early at the
		// next header marker so we never bleed into the following row.
		int end = Math.min(children.length, headerIdx + 8);
		for (int j = headerIdx + 1; j < end; j++)
		{
			Widget w = children[j];
			if (w == null) continue;
			String text = w.getText();
			if (text != null && ("Bought:".equals(text) || "Sold:".equals(text)))
			{
				break;
			}
			if (itemWidget == null && w.getItemId() > 0)
			{
				itemWidget = w;
			}
			if (priceWidget == null && text != null && text.contains("each"))
			{
				priceWidget = w;
			}
		}
		if (itemWidget == null || priceWidget == null)
		{
			log.debug("Row at {} ({}): missing itemWidget={} priceWidget={}",
				headerIdx, isBuy ? "Bought" : "Sold", itemWidget != null, priceWidget != null);
			return;
		}
		int itemId = itemWidget.getItemId();
		int qty = itemWidget.getItemQuantity();
		int price = parsePerItemPrice(priceWidget);
		if (qty > 0 && price > 0)
		{
			entries.add(new GEHistoryEntry(itemId, isBuy, qty, price));
		}
		else
		{
			log.debug("Row at {} ({}): id={} qty={} price={} — dropped",
				headerIdx, isBuy ? "Bought" : "Sold", itemId, qty, price);
		}
	}

	private void logWidgetStructure(Widget listWidget)
	{
		log.info("=== GE History widget structure (group {}, child {}) ===",
			InterfaceID.GE_HISTORY, GE_HISTORY_LIST_CHILD);
		log.info("List widget: type={}, w={}, h={}, hidden={}",
			listWidget.getType(), listWidget.getWidth(), listWidget.getHeight(), listWidget.isHidden());

		Widget[] dyn = listWidget.getDynamicChildren();
		Widget[] kid = listWidget.getChildren();
		Widget[] nest = listWidget.getNestedChildren();
		log.info("dynamicChildren={}, getChildren()={}, nestedChildren={}",
			dyn == null ? "null" : dyn.length,
			kid == null ? "null" : kid.length,
			nest == null ? "null" : nest.length);

		Widget[] sample = firstNonEmpty(dyn, kid, nest);
		if (sample == null)
		{
			log.info("No children to dump.");
			return;
		}
		int max = Math.min(sample.length, 24);
		for (int i = 0; i < max; i++)
		{
			Widget c = sample[i];
			if (c == null) continue;
			log.info("  [{}]: type={} itemId={} qty={} text='{}' spriteId={} color={}",
				i, c.getType(), c.getItemId(), c.getItemQuantity(),
				c.getText() == null ? "" : c.getText(),
				c.getSpriteId(), Integer.toHexString(c.getTextColor()));
		}
		log.info("=== end widget structure ===");
	}

	/** Price text is "<col=...>X coins</col><br>= Y each" — the per-item price is after the '='. */
	private static int parsePerItemPrice(Widget widget)
	{
		if (widget == null) return -1;
		String text = widget.getText();
		if (text == null || text.isEmpty()) return -1;
		int eqIdx = text.lastIndexOf('=');
		String slice = (eqIdx >= 0) ? text.substring(eqIdx) : text;
		return parseDigits(slice);
	}

	private void backfillOfflineFills(List<GEHistoryEntry> entries)
	{
		if (!session.isOfflineSyncCompleted())
		{
			log.info("Backfill skipped: offline sync has not completed yet");
			return;
		}
		Optional<String> rsnOpt = session.getRsnSafe();
		if (rsnOpt.isEmpty())
		{
			log.info("Backfill skipped: no RSN available on session");
			return;
		}
		String rsn = rsnOpt.get();
		if (pendingOfflineFillItemIds.isEmpty())
		{
			log.info("Backfill skipped: no offline fills registered this session");
			return;
		}
		List<TrackedOffer> candidates = new ArrayList<>(session.getTrackedOffers().values());
		candidates.addAll(recentlyPersistedOffers.values());
		log.info("Backfill: {} history entries vs {} candidate offers ({} live + {} persisted), {} pending item ids",
			entries.size(), candidates.size(),
			session.getTrackedOffers().size(), recentlyPersistedOffers.size(),
			pendingOfflineFillItemIds.size());
		Map<Integer, TrackedOffer> matchedOffers = new HashMap<>();
		List<CompletableFuture<Void>> postFutures = new ArrayList<>();

		for (GEHistoryEntry entry : entries)
		{
			tryBackfillEntry(entry, candidates, matchedOffers, rsn, postFutures);
		}
		if (matchedOffers.isEmpty())
		{
			log.info("Backfill: no history entries matched a registered offline fill");
			return;
		}
		log.info("Backfilled {} offline fills with actual prices from GE history", matchedOffers.size());

		// After all backfill posts complete, ping the panel so newly-paired
		// flips appear in Active/Completed without the user reloading.
		CompletableFuture.allOf(postFutures.toArray(new CompletableFuture[0]))
			.whenComplete((v, ex) ->
			{
				Runnable cb = onBackfillComplete;
				if (cb != null)
				{
					try { cb.run(); }
					catch (Exception e) { log.debug("onBackfillComplete callback threw: {}", e.getMessage()); }
				}
			});
	}

	private void tryBackfillEntry(
		GEHistoryEntry entry,
		Collection<TrackedOffer> candidates,
		Map<Integer, TrackedOffer> matchedOffers,
		String rsn,
		List<CompletableFuture<Void>> postFutures)
	{
		// Only backfill items we registered as offline-completed this session.
		// Without this gate, opening the History tab would replay every visible
		// row (up to 30 entries spanning prior sessions) on every read.
		if (!pendingOfflineFillItemIds.contains(entry.getItemId()))
		{
			return;
		}
		if (matchedOffers.containsKey(entry.getItemId()))
		{
			return;
		}
		for (TrackedOffer offer : candidates)
		{
			if (offer.getItemId() == entry.getItemId() && offer.isBuy() == entry.isBuy())
			{
				matchedOffers.put(entry.getItemId(), offer);
				postFutures.add(postBackfill(entry, offer, rsn));
				return;
			}
		}
	}

	private CompletableFuture<Void> postBackfill(GEHistoryEntry entry, TrackedOffer offer, String rsn)
	{
		int actualPrice = entry.getPricePerItem();
		log.info("GE History backfill: {} {} — estimated {}gp, actual {}gp",
			entry.isBuy() ? "BUY" : "SELL", offer.getItemName(), offer.getPrice(), actualPrice);
		return apiClient.recordTransactionAsync(FlipSmartApiClient.TransactionRequest
			.builder(entry.getItemId(), offer.getItemName(), entry.isBuy(), entry.getQuantity(), actualPrice)
			.rsn(rsn)
			.totalQuantity(offer.getTotalQuantity())
			.isHistoryBackfill(true)
			.build());
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
