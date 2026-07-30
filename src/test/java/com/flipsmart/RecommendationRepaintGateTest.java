package com.flipsmart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every repaint of the recommendation list must pass the free-tier slot gate.
 *
 * <p>Prod (#1133 follow-up): a free user at their 2-item cap saw the upgrade message replaced
 * by a full list of recommendation cards, because Auto's queue-advance callback repainted via
 * {@code populateRecommendations} directly. This is the second time a repaint call site skipped
 * the gate — the cogwheel "Update" path had the same hole earlier — so the invariant is pinned
 * here rather than left to review.
 *
 * <p>The gate lives in {@code repopulateRecommendationsGated} and {@code reevaluateSlotLimitDisplay};
 * {@code handleRecommendationsResponse} applies it inline around the fetch. Any NEW call site is a
 * bypass until it routes through one of those. If you are here because this test failed, call
 * {@code repopulateRecommendationsGated()} instead of {@code populateRecommendations(...)}.</p>
 */
public class RecommendationRepaintGateTest
{
	private static final Path PANEL_SOURCE =
		Paths.get("src/main/java/com/flipsmart/FlipFinderPanel.java");

	/** Methods allowed to call populateRecommendations, because each applies the gate itself. */
	private static final List<String> GATED_CALLERS = Arrays.asList(
		"handleRecommendationsResponse",
		"repopulateRecommendationsGated",
		"reevaluateSlotLimitDisplay");

	// A method declaration at class-body indentation (exactly one tab), e.g. "\tprivate void foo(".
	// Anchored on an access modifier so control flow ("\t\telse if (") can't pose as a declaration.
	private static final Pattern METHOD_DECL = Pattern.compile(
		"^\\t(?:public|private|protected)\\s+(?:static\\s+|final\\s+|synchronized\\s+)*"
			+ "[\\w<>,\\[\\].?]+\\s+(\\w+)\\s*\\(");

	@Test
	public void everyRecommendationRepaintRoutesThroughTheSlotGate() throws IOException
	{
		assertTrue("FlipFinderPanel source not found — is the test running from the project root?",
			Files.exists(PANEL_SOURCE));

		List<String> lines = Files.readAllLines(PANEL_SOURCE, StandardCharsets.UTF_8);
		List<String> ungated = new ArrayList<>();

		for (int i = 0; i < lines.size(); i++)
		{
			String line = lines.get(i);
			if (!line.contains("populateRecommendations(") || line.contains("private void populateRecommendations("))
			{
				continue;
			}
			String enclosing = enclosingMethod(lines, i);
			if (!GATED_CALLERS.contains(enclosing))
			{
				ungated.add((i + 1) + ": in " + enclosing + " -> " + line.trim());
			}
		}

		assertEquals("ungated populateRecommendations call site(s) — route through "
			+ "repopulateRecommendationsGated() so a capped free user keeps the upgrade message: "
			+ ungated, 0, ungated.size());
	}

	/** Name of the method containing {@code lineIndex}, or "<unknown>" when none is found. */
	private static String enclosingMethod(List<String> lines, int lineIndex)
	{
		for (int i = lineIndex; i >= 0; i--)
		{
			Matcher m = METHOD_DECL.matcher(lines.get(i));
			if (m.find())
			{
				return m.group(1);
			}
		}
		return "<unknown>";
	}
}
