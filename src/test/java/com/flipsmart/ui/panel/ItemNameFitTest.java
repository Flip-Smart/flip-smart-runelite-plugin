package com.flipsmart.ui.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ItemNameFitTest
{
	/**
	 * Deterministic stand-in for FontMetrics: every glyph is fontSize/2 px wide.
	 * Real Arial metrics are unavailable on headless CI, so asserting against them
	 * would be meaningless there; the algorithm is what these tests cover.
	 */
	private static final ItemNameFit.WidthMeasurer FIXED = (text, size) -> text.length() * (size / 2);

	@Test
	public void shortNameStaysOneLineAtMaxSize()
	{
		ItemNameFit.Fit fit = ItemNameFit.fit("Twisted bow", 98, FIXED);
		assertEquals(13, fit.getFontSize());
		assertEquals(1, fit.getLineCount());
		assertEquals("<html>Twisted bow</html>", fit.getHtml());
	}

	@Test
	public void longNameWrapsToTwoLinesWithoutShrinking()
	{
		ItemNameFit.Fit fit = ItemNameFit.fit("Dragon pickaxe upgrade kit", 98, FIXED);
		assertEquals(13, fit.getFontSize());
		assertEquals(2, fit.getLineCount());
		assertEquals("<html>Dragon pickaxe<br>upgrade kit</html>", fit.getHtml());
	}

	@Test
	public void shrinksOnlyWhenTwoLinesAtMaxSizeWouldNotFit()
	{
		ItemNameFit.Fit fit = ItemNameFit.fit("Alpha bravo charlie delta echo foxtrot", 98, FIXED);
		assertEquals(11, fit.getFontSize());
		assertEquals(2, fit.getLineCount());
		assertEquals("<html>Alpha bravo charlie<br>delta echo foxtrot</html>", fit.getHtml());
	}

	@Test
	public void ellipsizesAtFloorWhenEvenTwoLinesCannotHoldIt()
	{
		ItemNameFit.Fit fit = ItemNameFit.fit("Alpha bravo charlie delta echo foxtrot golf hotel", 98, FIXED);
		assertEquals(10, fit.getFontSize());
		assertEquals(2, fit.getLineCount());
		assertTrue("expected the truncated line to end in an ellipsis: " + fit.getHtml(),
			fit.getHtml().endsWith("…</html>"));
	}

	@Test
	public void neverGoesBelowTheReadabilityFloor()
	{
		ItemNameFit.Fit fit = ItemNameFit.fit(
			"Alpha bravo charlie delta echo foxtrot golf hotel india juliet", 98, FIXED);
		assertEquals(ItemNameFit.MIN_FONT_SIZE, fit.getFontSize());
		assertTrue(fit.getLineCount() <= ItemNameFit.MAX_LINES);
	}

	@Test
	public void hardSplitsASingleWordWiderThanTheBox()
	{
		ItemNameFit.Fit fit = ItemNameFit.fit("Supercalifragilisticexpialidocious", 98, FIXED);
		assertTrue(fit.getLineCount() <= ItemNameFit.MAX_LINES);
		assertTrue(fit.getFontSize() >= ItemNameFit.MIN_FONT_SIZE);
	}

	@Test
	public void escapesHtmlAfterMeasuringSoAmpersandsDoNotOvershrink()
	{
		ItemNameFit.Fit fit = ItemNameFit.fit("Salt & pepper", 98, FIXED);
		assertEquals(13, fit.getFontSize());
		// Measured as 13 raw chars, not 17 escaped ones; escaping happens on render only.
		assertEquals(java.util.Collections.singletonList("Salt & pepper"), fit.getLines());
		assertEquals("<html>Salt &amp; pepper</html>", fit.getHtml());
	}

	@Test
	public void nullOrBlankNameIsHarmless()
	{
		assertEquals("<html></html>", ItemNameFit.fit(null, 98, FIXED).getHtml());
		assertEquals(1, ItemNameFit.fit("   ", 98, FIXED).getLineCount());
	}

	/**
	 * Invariants, not sizes: these must hold for whatever font the JVM actually resolves.
	 * Headless CI has no Arial and substitutes something else, so "this name is 13pt" is
	 * not assertable here — but "no line overflows its box" always is.
	 */
	@Test
	public void everyChosenLineFitsTheBoxUnderRealFontMetrics()
	{
		java.awt.Graphics2D g = new java.awt.image.BufferedImage(1, 1,
			java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
		ItemNameFit.WidthMeasurer real =
			(text, size) -> g.getFontMetrics(new java.awt.Font("Arial", java.awt.Font.BOLD, size))
				.stringWidth(text);

		String[] names = {
			"Twisted bow",
			"Rune platebody",
			"Elysian spirit shield",
			"Torva platelegs (damaged)",
			"Dragon pickaxe upgrade kit",
			"Armadyl godsword ornament kit",
		};

		for (String name : names)
		{
			ItemNameFit.Fit fit = ItemNameFit.fit(name, 98, real);
			assertTrue(name + " used " + fit.getLineCount() + " lines",
				fit.getLineCount() <= ItemNameFit.MAX_LINES);
			assertTrue(name + " shrank to " + fit.getFontSize() + "pt",
				fit.getFontSize() >= ItemNameFit.MIN_FONT_SIZE
					&& fit.getFontSize() <= ItemNameFit.MAX_FONT_SIZE);
			for (String line : fit.getLines())
			{
				assertTrue(name + " line '" + line + "' overflows the 98px box",
					real.widthOf(line, fit.getFontSize()) <= 98);
			}
		}
	}
}
