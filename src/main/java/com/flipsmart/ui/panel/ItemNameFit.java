package com.flipsmart.ui.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chooses a font size and line breaks so an item name fits the fixed-width name box
 * in a flip card header.
 *
 * Measurement is injected rather than taken from a Font so the algorithm is testable
 * without a display: headless CI has no Arial and silently substitutes a font with
 * different metrics.
 */
public final class ItemNameFit
{
	public static final int MAX_FONT_SIZE = 13;
	public static final int MIN_FONT_SIZE = 10;
	public static final int MAX_LINES = 2;

	private static final String ELLIPSIS = "…";

	/** Rendered width of {@code text} at {@code fontSize}, in pixels. */
	@FunctionalInterface
	public interface WidthMeasurer
	{
		int widthOf(String text, int fontSize);
	}

	/** A chosen layout: the raw lines, the font size they assume, and their HTML render. */
	public static final class Fit
	{
		private final List<String> lines;
		private final int fontSize;

		Fit(List<String> lines, int fontSize)
		{
			this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
			this.fontSize = fontSize;
		}

		/** The raw, unescaped lines this fit chose. */
		public List<String> getLines()
		{
			return lines;
		}

		public String getHtml()
		{
			return toHtml(lines);
		}

		public int getFontSize()
		{
			return fontSize;
		}

		public int getLineCount()
		{
			return lines.size();
		}
	}

	private ItemNameFit()
	{
	}

	/**
	 * Largest size in [MIN_FONT_SIZE, MAX_FONT_SIZE] whose greedy wrap fits in
	 * MAX_LINES lines of {@code boxWidth}, else the floor size with the last line
	 * ellipsized.
	 */
	public static Fit fit(String name, int boxWidth, WidthMeasurer measurer)
	{
		String safe = name == null ? "" : name.trim();
		if (safe.isEmpty())
		{
			return new Fit(Collections.singletonList(""), MAX_FONT_SIZE);
		}

		for (int size = MAX_FONT_SIZE; size >= MIN_FONT_SIZE; size--)
		{
			List<String> lines = wrap(safe, boxWidth, size, measurer);
			if (lines.size() <= MAX_LINES)
			{
				return new Fit(lines, size);
			}
		}

		List<String> lines = wrap(safe, boxWidth, MIN_FONT_SIZE, measurer);
		List<String> kept = new ArrayList<>(lines.subList(0, MAX_LINES));
		kept.set(MAX_LINES - 1, ellipsize(kept.get(MAX_LINES - 1), boxWidth, MIN_FONT_SIZE, measurer));
		return new Fit(kept, MIN_FONT_SIZE);
	}

	/** Greedy word wrap; a word wider than the box on its own is split at characters. */
	private static List<String> wrap(String text, int boxWidth, int size, WidthMeasurer measurer)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (String word : text.split(" +"))
		{
			String rest = flushOverlongWord(word, lines, current, boxWidth, size, measurer);
			if (rest.isEmpty())
			{
				continue;
			}
			String candidate = current.length() == 0 ? rest : current + " " + rest;
			if (measurer.widthOf(candidate, size) <= boxWidth)
			{
				current.setLength(0);
				current.append(candidate);
			}
			else
			{
				if (current.length() > 0)
				{
					lines.add(current.toString());
				}
				current.setLength(0);
				current.append(rest);
			}
		}

		if (current.length() > 0)
		{
			lines.add(current.toString());
		}
		if (lines.isEmpty())
		{
			lines.add("");
		}
		return lines;
	}

	/** Splits {@code word} across {@code lines} while it's wider than the box; returns the remainder. */
	private static String flushOverlongWord(String word, List<String> lines, StringBuilder current, int boxWidth, int size, WidthMeasurer measurer)
	{
		String rest = word;
		while (measurer.widthOf(rest, size) > boxWidth)
		{
			int cut = longestPrefixThatFits(rest, boxWidth, size, measurer);
			if (current.length() > 0)
			{
				lines.add(current.toString());
				current.setLength(0);
			}
			lines.add(rest.substring(0, cut));
			rest = rest.substring(cut);
		}
		return rest;
	}

	/** At least 1, so a box too narrow for any glyph still terminates the split loop. */
	private static int longestPrefixThatFits(String word, int boxWidth, int size, WidthMeasurer measurer)
	{
		int cut = 0;
		for (int i = 1; i <= word.length(); i++)
		{
			if (measurer.widthOf(word.substring(0, i), size) > boxWidth)
			{
				break;
			}
			cut = i;
		}
		return Math.max(1, cut);
	}

	private static String ellipsize(String line, int boxWidth, int size, WidthMeasurer measurer)
	{
		String trimmed = line;
		while (!trimmed.isEmpty() && measurer.widthOf(trimmed + ELLIPSIS, size) > boxWidth)
		{
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed + ELLIPSIS;
	}

	/** Escape last: wrapping measured the raw text, so "&" sized as 1 char, not 5. */
	private static String toHtml(List<String> lines)
	{
		StringBuilder sb = new StringBuilder("<html>");
		for (int i = 0; i < lines.size(); i++)
		{
			if (i > 0)
			{
				sb.append("<br>");
			}
			sb.append(PanelFormat.escapeHtml(lines.get(i)));
		}
		return sb.append("</html>").toString();
	}
}
