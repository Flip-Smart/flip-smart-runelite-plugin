package com.flipsmart.ui.panel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for the shared card-construction helpers.
 *
 * <p>Every card in the panel is assembled from these, so their properties are the visual
 * contract the whole UI inherits. The values below were captured from the real methods
 * and pin CURRENT behaviour -- opacity, cursor, border insets and spacing structure are
 * exactly the things a table-driven rewrite is most likely to change by accident while
 * still looking roughly right in a screenshot.
 *
 * <p>These assert component PROPERTIES, never rendering, so they hold whether or not the
 * test JVM has a display.
 */
public class CardWidgetsCharacterizationTest
{
	private static final Color FG = new Color(12, 34, 56);
	private static final Color BG = new Color(65, 43, 21);

	// ------------------------------------------------------------------ labels

	@Test
	public void labelAppliesTextForegroundAndFontVerbatim()
	{
		Font font = new Font("Arial", Font.BOLD, 12);
		JLabel label = CardWidgets.label("hi", FG, font);

		assertEquals("hi", label.getText());
		assertEquals(FG, label.getForeground());
		assertEquals("Arial", label.getFont().getName());
		assertEquals(Font.BOLD, label.getFont().getStyle());
		assertEquals(12, label.getFont().getSize());
	}

	/**
	 * Labels are transparent so the card's background shows through. Cards recolour on
	 * hover and focus by setting the PARENT's background, which only works while the
	 * labels stay non-opaque -- making one opaque would leave a visible block that does
	 * not follow the hover state.
	 */
	@Test
	public void labelIsNotOpaque()
	{
		assertFalse(CardWidgets.label("x", FG, new Font("Arial", Font.PLAIN, 10)).isOpaque());
		assertFalse(CardWidgets.createStyledLabel("x", FG).isOpaque());
	}

	@Test
	public void labelIsLeftAligned()
	{
		assertEquals(Component.LEFT_ALIGNMENT,
			CardWidgets.label("x", FG, new Font("Arial", Font.PLAIN, 10)).getAlignmentX(), 0.0001);
	}

	/** createStyledLabel is the no-font overload: Arial plain 12. */
	@Test
	public void createStyledLabelDefaultsToArialPlainTwelve()
	{
		JLabel label = CardWidgets.createStyledLabel("st", FG);
		assertEquals(FG, label.getForeground());
		assertEquals("Arial", label.getFont().getName());
		assertEquals(Font.PLAIN, label.getFont().getStyle());
		assertEquals(12, label.getFont().getSize());
	}

	// ------------------------------------------------------------------ panels

	/** Panels ARE opaque -- they are what actually paints the card background. */
	@Test
	public void panelIsOpaqueAndKeepsGivenLayoutAndBackground()
	{
		JPanel panel = CardWidgets.panel(new BorderLayout(), BG);
		assertEquals(BG, panel.getBackground());
		assertTrue(panel.getLayout() instanceof BorderLayout);
		assertTrue(panel.isOpaque());
	}

	@Test
	public void createDetailsPanelUsesBoxLayoutWithTopPaddingOnly()
	{
		JPanel details = CardWidgets.createDetailsPanel(BG);
		assertEquals(BG, details.getBackground());
		assertTrue(details.getLayout() instanceof BoxLayout);
		assertEquals(new Insets(5, 0, 0, 0), details.getBorder().getBorderInsets(details));
	}

	@Test
	public void createBaseItemPanelAppliesUniformEightPixelPaddingAndMaxHeight()
	{
		JPanel base = CardWidgets.createBaseItemPanel(BG, 90, true);
		assertEquals(BG, base.getBackground());
		assertTrue(base.getLayout() instanceof BorderLayout);
		assertEquals(90, base.getMaximumSize().height);
		assertEquals(new Insets(8, 8, 8, 8), base.getBorder().getBorderInsets(base));
	}

	/** The handCursor flag is the only difference between the two base-panel forms. */
	@Test
	public void createBaseItemPanelHandCursorFlagSelectsCursor()
	{
		assertEquals(Cursor.HAND_CURSOR, CardWidgets.createBaseItemPanel(BG, 90, true).getCursor().getType());
		assertEquals(Cursor.DEFAULT_CURSOR, CardWidgets.createBaseItemPanel(BG, 90, false).getCursor().getType());
	}

	// ----------------------------------------------------------------- spacing

	/**
	 * Labels are interleaved with rigid-area fillers, so N labels yield 2N-1 children in
	 * strict label/filler/label order. A rewrite that appends spacing after every label
	 * instead of between them would add a trailing gap and shift every card's height.
	 */
	@Test
	public void addLabelsWithSpacingInterleavesFillersBetweenLabelsOnly()
	{
		JPanel host = new JPanel();
		CardWidgets.addLabelsWithSpacing(host, new JLabel("a"), new JLabel("b"), new JLabel("c"));

		assertEquals(5, host.getComponentCount());
		assertTrue(host.getComponent(0) instanceof JLabel);
		assertFalse(host.getComponent(1) instanceof JLabel);
		assertTrue(host.getComponent(2) instanceof JLabel);
		assertFalse(host.getComponent(3) instanceof JLabel);
		assertTrue(host.getComponent(4) instanceof JLabel);
	}

	@Test
	public void addLabelsWithSpacingAddsNoFillerForASingleLabel()
	{
		JPanel host = new JPanel();
		CardWidgets.addLabelsWithSpacing(host, new JLabel("only"));
		assertEquals(1, host.getComponentCount());
	}

	@Test
	public void setPanelBackgroundsAppliesToEveryPanelGiven()
	{
		JPanel a = new JPanel();
		JPanel b = new JPanel();
		CardWidgets.setPanelBackgrounds(BG, a, b);
		assertEquals(BG, a.getBackground());
		assertEquals(BG, b.getBackground());
	}
}
