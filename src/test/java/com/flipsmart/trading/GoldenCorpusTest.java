package com.flipsmart.trading;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Mirrored golden-corpus regression harness.
 *
 * Replays the shared production offer-event fixtures — the same JSON files the
 * backend golden-corpus suite asserts against — through the plugin's
 * {@link RoundTripLedger} and asserts its zero-crossing round-trip
 * segmentation. Distinct ids for back-to-back same-item round trips are the
 * plugin-side guard against the fill over-count class.
 *
 * Each fixture's {@code plugin.round_trip_ids} list is the expected id
 * {@link RoundTripLedger#recordFill} returns for each event, in order. A
 * fixture whose invariant is backend-only (e.g. Collect-fill idempotency)
 * carries {@code plugin.skip} and is not replayed here.
 *
 * Adding a case is one file: drop the shared JSON into
 * {@code src/test/resources/golden_corpus/}; it is auto-discovered.
 */
@RunWith(Parameterized.class)
public class GoldenCorpusTest
{
    private static final Path FIXTURES_DIR = Paths.get("src", "test", "resources", "golden_corpus");
    private static final Gson GSON = new Gson();

    private final String name;
    private final JsonObject fixture;

    public GoldenCorpusTest(String name, JsonObject fixture)
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
                    String name = obj.has("name") ? obj.get("name").getAsString() : path.getFileName().toString();
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
        assertTrue("golden corpus fixtures should be present", fixture.has("events"));
    }

    @Test
    public void roundTripSegmentationMatchesFixture()
    {
        JsonObject plugin = fixture.has("plugin") ? fixture.getAsJsonObject("plugin") : new JsonObject();
        if (plugin.has("skip"))
        {
            return;
        }

        JsonArray expectedIds = plugin.getAsJsonArray("round_trip_ids");
        assertTrue(
            "[" + name + "] fixture must supply plugin.round_trip_ids or plugin.skip",
            expectedIds != null);

        RoundTripLedger ledger = new RoundTripLedger();
        JsonArray events = fixture.getAsJsonArray("events");
        assertEquals(
            "[" + name + "] round_trip_ids length must match event count",
            events.size(), expectedIds.size());

        for (int i = 0; i < events.size(); i++)
        {
            JsonObject event = events.get(i).getAsJsonObject();
            String rsn = event.has("rsn") ? event.get("rsn").getAsString() : "TestPlayer";
            int itemId = event.get("item_id").getAsInt();
            boolean isBuy = event.get("is_buy").getAsBoolean();
            int quantity = event.get("quantity").getAsInt();

            Integer actualId = ledger.recordFill(rsn, itemId, isBuy, quantity);
            JsonElement expected = expectedIds.get(i);
            assertEquals(
                "[" + name + "] event[" + i + "] round-trip id",
                Integer.valueOf(expected.getAsInt()), actualId);
        }
    }
}
