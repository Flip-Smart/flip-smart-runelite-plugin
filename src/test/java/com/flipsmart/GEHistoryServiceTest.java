package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.trading.OfferStore;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.chat.ChatMessageManager;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GEHistoryService}'s widget-text parsing helpers.
 * <p>
 * The package-private {@code parsePerItemPriceFromText} and
 * {@code parseLeadingTotalFromText} entry points let us exercise the
 * parsing logic against raw widget-text shapes without mocking the
 * RuneLite {@code Widget} object.
 * <p>
 * The regression case under {@link #parseEachWidgetMissingSeparatorYieldsTrailingQuantity}
 * locks in the fix for Flip-Smart/flip-smart#689: pre-fix, widget text of
 * the shape {@code "<col=ffb83f>1,149 each</col>"} produced 831,149 because
 * the parser sliced from the {@code '='} inside the color attribute and
 * the orphaned {@code "ffb83f>"} fragment leaked its hex digits into the
 * digit-greedy parse. Stripping HTML tags first removes that failure mode.
 */
public class GEHistoryServiceTest
{
	// Standard yellow GE text color in OSRS interfaces; the hex digits
	// "83" here are the ones that previously bled into the parsed price.
	private static final String GE_YELLOW = "<col=ffb83f>";

	// ----- parsePerItemPriceFromText ----------------------------------

	@Test
	public void parseWellFormedPriceTextReturnsPerItemValue()
	{
		// The shape RuneLite emits for multi-quantity rows: a coins total
		// inside a color span, a <br>, then "= N each" with the per-item
		// price after the '=' sign.
		String text = GE_YELLOW + "1,571,832 coins</col><br>= 1,341 each";
		assertEquals(1341, GEHistoryService.parsePerItemPriceFromText(text));
	}

	@Test
	public void parseEachWidgetMissingSeparatorYieldsTrailingQuantity()
	{
		// REGRESSION GUARD for Flip-Smart/flip-smart#689.
		// When the matched "each" widget contains only the trailing
		// quantity inside a color tag (no plain-text '=' separator),
		// the parser must NOT leak the "83" hex digits out of the
		// color attribute into the parsed number. Pre-fix this
		// returned 831_149; the fix returns 1_149.
		String text = GE_YELLOW + "1,149 each</col>";
		assertEquals(1149, GEHistoryService.parsePerItemPriceFromText(text));
	}

	@Test
	public void parseProductionCorruptionShapeForBonesToPeaches()
	{
		// Synthetic reproduction of the exact prod corruption — a
		// 1,341 gp/unit Bones-to-peaches sell that was filed as
		// 831,149 gp/unit. Confirms the fix produces 1,341 regardless
		// of whether the widget text wraps the per-item value alone.
		String text = GE_YELLOW + "1,341 each</col>";
		assertEquals(1341, GEHistoryService.parsePerItemPriceFromText(text));
	}

	@Test
	public void parseHandlesNestedColorSpans()
	{
		// Defensive: a row whose total and "= each" segments live in
		// two separate color spans must still resolve to the per-item
		// value after the last '=' in the cleaned text.
		String text = GE_YELLOW + "1,571,832 coins</col><br>"
			+ "<col=ff981f>= 1,341 each</col>";
		assertEquals(1341, GEHistoryService.parsePerItemPriceFromText(text));
	}

	@Test
	public void parseEmptyOrNullPriceTextReturnsMinusOne()
	{
		assertEquals(-1, GEHistoryService.parsePerItemPriceFromText(null));
		assertEquals(-1, GEHistoryService.parsePerItemPriceFromText(""));
	}

	// ----- parseLeadingTotalFromText (qty=1 fallback path) ------------

	@Test
	public void parseLeadingTotalIgnoresParenthesizedTaxBreakdown()
	{
		// Used for qty=1 history rows where the per-item price equals
		// the displayed total. The parenthesized tax detail must NOT
		// be concatenated into the parse.
		String text = GE_YELLOW + "1,540,809 coins (1,571,832 - 31,023)</col>";
		assertEquals(1_540_809, GEHistoryService.parseLeadingTotalFromText(text));
	}

	@Test
	public void parseLeadingTotalHandlesNoParenthesizedTail()
	{
		// Many qty=1 rows don't render a tax breakdown at all.
		String text = GE_YELLOW + "2,500,000 coins</col>";
		assertEquals(2_500_000, GEHistoryService.parseLeadingTotalFromText(text));
	}

	@Test
	public void parseLeadingTotalEmptyOrNullReturnsMinusOne()
	{
		assertEquals(-1, GEHistoryService.parseLeadingTotalFromText(null));
		assertEquals(-1, GEHistoryService.parseLeadingTotalFromText(""));
	}

	// ----- matchOfferId (#759 offer_id linkage) -----------------------

	private static OfferRecord offer(long offerId, int itemId, boolean buy, int total, int price)
	{
		return OfferRecord.newOffer(offerId, 0, itemId, "i" + itemId, buy, total, price, 1L);
	}

	@Test
	public void matchOfferId_uniqueMatchReturnsOfferId()
	{
		List<OfferRecord> candidates = Arrays.asList(
			offer(42, 28924, false, 30000, 384),
			offer(7, 4151, true, 5, 2_000_000));
		assertEquals(Long.valueOf(42), GEHistoryService.matchOfferId(candidates, 28924, false, 384));
	}

	@Test
	public void matchOfferId_ambiguousSameItemDirectionPriceReturnsNull()
	{
		List<OfferRecord> candidates = Arrays.asList(
			offer(42, 28924, false, 30000, 384),
			offer(43, 28924, false, 20000, 384));
		assertNull(GEHistoryService.matchOfferId(candidates, 28924, false, 384));
	}

	@Test
	public void matchOfferId_noMatchOnPriceOrDirectionReturnsNull()
	{
		List<OfferRecord> candidates = Collections.singletonList(offer(42, 28924, false, 30000, 384));
		assertNull(GEHistoryService.matchOfferId(candidates, 28924, false, 999));
		assertNull(GEHistoryService.matchOfferId(candidates, 28924, true, 384));
	}

	@Test
	public void matchOfferId_sameOfferAcrossCollectionsIsNotAmbiguous()
	{
		OfferRecord same = offer(42, 28924, false, 30000, 384);
		assertEquals(Long.valueOf(42), GEHistoryService.matchOfferId(Arrays.asList(same, same), 28924, false, 384));
	}

	// ----- prompt de-nag: only prompt when GE data is genuinely missing (#759) -----

	private ChatMessageManager chatMessageManager;

	private GEHistoryService newService()
	{
		chatMessageManager = mock(ChatMessageManager.class);
		return new GEHistoryService(
			mock(Client.class),
			mock(FlipSmartApiClient.class),
			mock(PlayerSession.class),
			chatMessageManager,
			new OfferStore());
	}

	@Test
	public void persistedStateWithoutOfflineFillsDoesNotPromptOrFlagBanner()
	{
		// Having tracked offers last session is NOT a reason to nag — only a
		// genuinely-unverified offline fill is. No prompt, no overlay banner.
		GEHistoryService svc = newService();
		svc.setRecentlyPersistedOffers(Collections.singletonList(offer(42, 28924, false, 30000, 384)));
		assertFalse(svc.hasUnverifiedOfflineFills());
		verify(chatMessageManager, never()).queue(any());
	}

	@Test
	public void genuineOfflineFillPromptsAndFlagsBanner()
	{
		GEHistoryService svc = newService();
		svc.registerOfflineFill(28924);
		assertTrue(svc.hasUnverifiedOfflineFills());
		verify(chatMessageManager, times(1)).queue(any());
	}

	// ----- proactive auto-backfill (#1053) ----------------------------

	private Widget headerWidget(String text)
	{
		Widget w = mock(Widget.class);
		when(w.getText()).thenReturn(text);
		return w;
	}

	private Widget itemWidget(int itemId, int qty)
	{
		Widget w = mock(Widget.class);
		when(w.getItemId()).thenReturn(itemId);
		when(w.getItemQuantity()).thenReturn(qty);
		return w;
	}

	private Widget priceWidget(String text)
	{
		Widget w = mock(Widget.class);
		when(w.getText()).thenReturn(text);
		return w;
	}

	/** A visible History list widget holding one "Bought:" row (4151 x5 @ 2,000). */
	private Widget visibleHistoryList()
	{
		Widget header = headerWidget("Bought:");
		Widget item = itemWidget(4151, 5);
		Widget price = priceWidget(GE_YELLOW + "10,000 coins</col><br>= 2,000 each");
		Widget[] rows = new Widget[]{header, item, price};
		Widget list = mock(Widget.class);
		when(list.isHidden()).thenReturn(false);
		when(list.getDynamicChildren()).thenReturn(rows);
		return list;
	}

	@Test
	public void offlineFillWithHistoryTabOpenScrapesInsteadOfNagging()
	{
		Client client = mock(Client.class);
		Widget list = visibleHistoryList();
		when(client.getWidget(InterfaceID.GE_HISTORY, 3)).thenReturn(list);
		ChatMessageManager chat = mock(ChatMessageManager.class);
		GEHistoryService svc = new GEHistoryService(
			client, mock(FlipSmartApiClient.class), mock(PlayerSession.class), chat, new OfferStore());

		svc.registerOfflineFill(4151);

		// Tab is already open, so we schedule a scrape rather than nagging.
		verify(chat, never()).queue(any());
	}

	@Test
	public void proactiveRescanPostsBackfillWhileHistoryVisibleWithoutNag()
	{
		Client client = mock(Client.class);
		Widget list = visibleHistoryList();
		when(client.getWidget(InterfaceID.GE_HISTORY, 3)).thenReturn(list);

		PlayerSession session = mock(PlayerSession.class);
		when(session.isOfflineSyncCompleted()).thenReturn(true);
		when(session.getRsnSafe()).thenReturn(Optional.of("Zezima"));

		FlipSmartApiClient api = mock(FlipSmartApiClient.class);
		when(api.recordHistoryBackfillBatchAsync(eq("Zezima"), anyList()))
			.thenReturn(CompletableFuture.completedFuture(null));

		GEHistoryService svc = new GEHistoryService(
			client, api, session, mock(ChatMessageManager.class), new OfferStore());

		// No WidgetLoaded, no manual prompt — a plain tick with the tab visible
		// must trigger a read + backfill post.
		svc.onGameTick();

		verify(api, times(1)).recordHistoryBackfillBatchAsync(eq("Zezima"), anyList());
	}

	@Test
	public void proactiveRescanGatedUntilOfflineSyncCompletes()
	{
		Client client = mock(Client.class);
		Widget list = visibleHistoryList();
		when(client.getWidget(InterfaceID.GE_HISTORY, 3)).thenReturn(list);

		PlayerSession session = mock(PlayerSession.class);
		when(session.isOfflineSyncCompleted()).thenReturn(false);

		FlipSmartApiClient api = mock(FlipSmartApiClient.class);
		GEHistoryService svc = new GEHistoryService(
			client, api, session, mock(ChatMessageManager.class), new OfferStore());

		for (int i = 0; i < 40; i++)
		{
			svc.onGameTick();
		}

		verify(api, never()).recordHistoryBackfillBatchAsync(any(), anyList());
	}
}
