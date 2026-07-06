package com.flipsmart.api.dto;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Pins the fan-out the panel relies on: a {@code GET /plugin/sync} body must
 * deserialize into the same per-endpoint DTOs the standalone calls used, so
 * {@code FlipFinderPanel.refresh()} can dispatch each sub-payload to its
 * existing apply method. Also verifies per-payload isolation — an absent
 * sub-payload parses to {@code null} rather than throwing.
 */
public class PluginSyncResponseTest
{
	private final Gson gson = new Gson();

	private PluginSyncResponse parse(String json)
	{
		return gson.fromJson(json, PluginSyncResponse.class);
	}

	@Test
	public void fullBundleDeserializesEachSubPayload()
	{
		String json = "{"
			+ "\"version\":1,"
			+ "\"flip_finder\":{\"flip_style\":\"balanced\",\"recommendations\":[]},"
			+ "\"active_flips\":{\"active_flips\":[],\"total_items\":0,\"total_invested\":0},"
			+ "\"completed_flips\":{\"flips\":[],\"count\":3},"
			+ "\"statistics\":{\"total_flips\":7,\"total_profit\":1234}"
			+ "}";

		PluginSyncResponse sync = parse(json);

		assertEquals(1, sync.getVersion());
		assertNotNull(sync.getFlipFinder());
		assertEquals("balanced", sync.getFlipFinder().getFlipStyle());
		assertNotNull(sync.getActiveFlips());
		assertNotNull(sync.getActiveFlips().getActiveFlips());
		assertNotNull(sync.getCompletedFlips());
		assertEquals(3, sync.getCompletedFlips().getCount());
		assertNotNull(sync.getStatistics());
		assertEquals(7, sync.getStatistics().getTotalFlips());
	}

	@Test
	public void absentSubPayloadsAreNullNotThrown()
	{
		// A partial bundle (only completed flips succeeded server-side) must leave
		// the other sub-payloads null so the panel skips them and keeps prior state.
		PluginSyncResponse sync = parse("{\"version\":1,\"completed_flips\":{\"flips\":[],\"count\":0}}");

		assertEquals(1, sync.getVersion());
		assertNull(sync.getFlipFinder());
		assertNull(sync.getActiveFlips());
		assertNull(sync.getStatistics());
		assertNotNull(sync.getCompletedFlips());
	}
}
