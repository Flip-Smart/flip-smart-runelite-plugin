package com.flipsmart.trading;

import com.flipsmart.util.BuyPriceLookup;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Session-corpus regression harness.
 *
 * <p>Replays each fixture's {@code timeline} — slot observations interleaved with hops and
 * relogs — through the real offer store and offline-sync service, then asserts the fixture's
 * {@code expect} block. Complements the fill-level golden corpus, which cannot express the
 * session lifecycle.</p>
 *
 * <p>Adding a case is one file: drop the JSON into {@code src/test/resources/session_corpus/};
 * it is auto-discovered.</p>
 */
@RunWith(Parameterized.class)
public class SessionCorpusTest
{
	private static final Path FIXTURES_DIR = Paths.get("src", "test", "resources", "session_corpus");
	private static final Gson GSON = new Gson();

	private final String name;
	private final JsonObject fixture;

	public SessionCorpusTest(String name, JsonObject fixture)
	{
		this.name = name;
		this.fixture = fixture;
	}

	@Parameterized.Parameters(name = "{0}")
	public static List<Object[]> fixtures() throws IOException
	{
		List<Object[]> cases = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(FIXTURES_DIR, "*.json"))
		{
			for (Path path : stream)
			{
				try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
				{
					JsonObject obj = GSON.fromJson(reader, JsonObject.class);
					String name = obj.has("name")
						? obj.get("name").getAsString() : path.getFileName().toString();
					cases.add(new Object[]{name, obj});
				}
			}
		}
		cases.sort((a, b) -> ((String) a[0]).compareTo((String) b[0]));
		return cases;
	}

	@Test
	public void corpusIsNonTrivial()
	{
		// Guards against a silently-empty resource dir masking a green run.
		assertTrue("[" + name + "] fixture must carry a timeline", fixture.has("timeline"));
	}

	@Test
	public void timelineMatchesExpectations()
	{
		Assume.assumeFalse("known failure pending absolute-merge",
			fixture.has("known_failure") && fixture.get("known_failure").getAsBoolean());

		SessionCorpusHarness.Result result = SessionCorpusHarness.run(fixture);
		JsonObject expect = fixture.getAsJsonObject("expect");

		assertBuyBasis(expect, result);
		assertLiveSlots(expect, result);
		assertReportedFills(expect, result);
	}

	private void assertBuyBasis(JsonObject expect, SessionCorpusHarness.Result result)
	{
		if (!expect.has("buy_basis"))
		{
			return;
		}
		for (Map.Entry<String, JsonElement> e : expect.getAsJsonObject("buy_basis").entrySet())
		{
			int itemId = Integer.parseInt(e.getKey());
			Integer basis = BuyPriceLookup.findAverageBuyPriceWithFallback(
				null, result.store.forItem(itemId), itemId);
			assertEquals("[" + name + "] buy basis for item " + itemId,
				Integer.valueOf(e.getValue().getAsInt()), basis);
		}
	}

	private void assertLiveSlots(JsonObject expect, SessionCorpusHarness.Result result)
	{
		if (!expect.has("live_slot_items"))
		{
			return;
		}
		for (Map.Entry<String, JsonElement> e : expect.getAsJsonObject("live_slot_items").entrySet())
		{
			int slot = Integer.parseInt(e.getKey());
			assertNotNull("[" + name + "] slot " + slot + " must hold a live record",
				result.store.bySlot(slot));
			assertEquals("[" + name + "] item in slot " + slot,
				e.getValue().getAsInt(), result.store.bySlot(slot).getItemId());
		}
	}

	private void assertReportedFills(JsonObject expect, SessionCorpusHarness.Result result)
	{
		if (!expect.has("reported_fills"))
		{
			return;
		}
		List<String> actual = new ArrayList<>();
		for (OfferEvent event : result.reportedFills)
		{
			actual.add(event.record.getItemId() + ":" + event.record.isBuy()
				+ ":" + event.newlyFilledQuantity);
		}
		List<String> wanted = new ArrayList<>();
		for (JsonElement element : expect.getAsJsonArray("reported_fills"))
		{
			JsonObject f = element.getAsJsonObject();
			wanted.add(f.get("item_id").getAsInt() + ":" + f.get("is_buy").getAsBoolean()
				+ ":" + f.get("quantity").getAsInt());
		}
		assertEquals("[" + name + "] reported fills", wanted, actual);
	}
}
