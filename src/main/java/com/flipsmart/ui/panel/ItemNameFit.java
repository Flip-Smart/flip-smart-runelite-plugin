package com.flipsmart.ui.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

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

	/** Rendered width of {@code text} at {@code fontSize}, in pixels. */
	public interface WidthMeasurer
	{
		int widthOf(String text, int fontSize);
	}

	/** A chosen layout: the raw, unescaped lines and the font size they assume. */
	@Value
	public static class Fit
	{
		List<String> lines;
		int fontSize;

		/** Escape last: wrapping measured the raw text, so "&" sized as 1 char, not 5. */
		public String getHtml()
		{
			return lines.stream().map(PanelFormat::escapeHtml).collect(Collectors.joining("<br>", "<html>", "</html>"));
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
	 * ellipsized. A single word wider than the box keeps its own line and is clipped
	 * by the label, which no OSRS item name is long enough to hit.
	 */
	public static Fit fit(String name, int boxWidth, WidthMeasurer measurer)
	{
		String safe = name == null ? "" : name.trim();
		int size = MAX_FONT_SIZE;
		List<String> lines = wrap(safe, boxWidth, size, measurer);
		while (lines.size() > MAX_LINES && size > MIN_FONT_SIZE)
		{
			lines = wrap(safe, boxWidth, --size, measurer);
		}
		if (lines.size() > MAX_LINES)
		{
			lines = new ArrayList<>(lines.subList(0, MAX_LINES));
			String last = lines.get(MAX_LINES - 1);
			while (!last.isEmpty() && measurer.widthOf(last + "…", size) > boxWidth)
			{
				last = last.substring(0, last.length() - 1);
			}
			lines.set(MAX_LINES - 1, last + "…");
		}
		return new Fit(lines, size);
	}

	private static List<String> wrap(String text, int boxWidth, int size, WidthMeasurer measurer)
	{
		List<String> lines = new ArrayList<>();
		String current = "";
		for (String word : text.split(" +"))
		{
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (current.isEmpty() || measurer.widthOf(candidate, size) <= boxWidth)
			{
				current = candidate;
			}
			else
			{
				lines.add(current);
				current = word;
			}
		}
		lines.add(current);
		return lines;
	}
}
