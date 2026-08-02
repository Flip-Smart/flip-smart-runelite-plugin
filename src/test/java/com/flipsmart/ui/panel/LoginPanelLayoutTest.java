package com.flipsmart.ui.panel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Pins the login form's layout helpers.
 *
 * <p>These replaced 127 lines of straight-line construction. The values asserted here are
 * the ones the old inline code produced, so a regression shows up as a failing test rather
 * than a subtly different login screen that nobody notices until a user complains.
 *
 * <p>The {@code stack} assertions matter most: the previous code alternated
 * {@code add(component)} / {@code add(rigidArea)} by hand, and getting the interleaving
 * wrong would shift every element on the form without changing any single value.
 */
public class LoginPanelLayoutTest
{
	// ------------------------------------------------------------------- stack

	/**
	 * Entries alternate component, gap, component, gap, ... and end on a component, so
	 * N components yield 2N-1 children with no trailing spacer. This is exactly the shape
	 * the hand-written add/rigidArea sequence produced.
	 */
	@Test
	public void stackInterleavesComponentsAndGapsWithNoTrailingSpacer()
	{
		JPanel host = new JPanel();
		JLabel a = new JLabel("a");
		JLabel b = new JLabel("b");
		JLabel c = new JLabel("c");

		LoginPanel.stack(host, a, 5, b, 30, c);

		assertEquals(5, host.getComponentCount());
		assertSame(a, host.getComponent(0));
		assertSame(b, host.getComponent(2));
		assertSame(c, host.getComponent(4));
	}

	/** The integer between two components becomes that gap's height, in order. */
	@Test
	public void stackAppliesEachGapHeightInOrder()
	{
		JPanel host = new JPanel();
		LoginPanel.stack(host, new JLabel("a"), 5, new JLabel("b"), 30, new JLabel("c"));

		assertEquals(5, host.getComponent(1).getPreferredSize().height);
		assertEquals(30, host.getComponent(3).getPreferredSize().height);
	}

	/** Gaps are vertical only -- a non-zero width would push the form off-centre. */
	@Test
	public void stackGapsHaveNoWidth()
	{
		JPanel host = new JPanel();
		LoginPanel.stack(host, new JLabel("a"), 15, new JLabel("b"));
		assertEquals(0, host.getComponent(1).getPreferredSize().width);
	}

	@Test
	public void stackWithASingleComponentAddsNoGap()
	{
		JPanel host = new JPanel();
		LoginPanel.stack(host, new JLabel("only"));
		assertEquals(1, host.getComponentCount());
	}

	/** Sanity: a rigid area of the same height is what the old code added. */
	@Test
	public void stackGapMatchesBoxRigidAreaOfSameHeight()
	{
		JPanel host = new JPanel();
		LoginPanel.stack(host, new JLabel("a"), 20, new JLabel("b"));
		Component expected = Box.createRigidArea(new Dimension(0, 20));
		assertEquals(expected.getPreferredSize(), host.getComponent(1).getPreferredSize());
	}

	// --------------------------------------------------------------- fullWidth

	@Test
	public void fullWidthCapsHeightButLetsWidthExpand()
	{
		assertEquals(new Dimension(Integer.MAX_VALUE, 35), LoginPanel.fullWidth(35));
	}

	// -------------------------------------------------------------- styleField

	@Test
	public void styleFieldAppliesDarkInputStylingAndHeightCap()
	{
		JTextField field = LoginPanel.styleField(new JTextField(20));

		assertEquals(Color.WHITE, field.getForeground());
		assertEquals(Color.WHITE, field.getCaretColor());
		assertEquals(30, field.getMaximumSize().height);
		assertEquals(Integer.MAX_VALUE, field.getMaximumSize().width);
	}

	/**
	 * The generic signature must return the concrete type so the password field stays a
	 * JPasswordField -- widening it to JTextField would silently render the password in
	 * plain text.
	 */
	@Test
	public void styleFieldPreservesPasswordFieldType()
	{
		JPasswordField password = LoginPanel.styleField(new JPasswordField(20));
		assertTrue(password.getEchoChar() != 0);
	}

	@Test
	public void styleFieldReturnsTheSameInstanceItWasGiven()
	{
		JTextField input = new JTextField(20);
		assertSame(input, LoginPanel.styleField(input));
	}

	// ------------------------------------------------------------ actionButton

	@Test
	public void actionButtonAppliesTextBackgroundAndHandCursor()
	{
		JButton button = LoginPanel.actionButton("Login", Color.ORANGE,
			BorderFactory.createEmptyBorder(8, 15, 8, 15), e -> { });

		assertEquals("Login", button.getText());
		assertEquals(Color.ORANGE, button.getBackground());
		assertEquals(Color.WHITE, button.getForeground());
		assertEquals(Cursor.HAND_CURSOR, button.getCursor().getType());
	}

	/** Focus painting stays off on all three buttons; the form styles focus itself. */
	@Test
	public void actionButtonDisablesFocusPainting()
	{
		assertFalse(LoginPanel.actionButton("x", Color.GRAY,
			BorderFactory.createEmptyBorder(), e -> { }).isFocusPainted());
	}

	@Test
	public void actionButtonWiresTheClickHandler()
	{
		JButton button = LoginPanel.actionButton("x", Color.GRAY,
			BorderFactory.createEmptyBorder(), e -> { });
		assertEquals(1, button.getActionListeners().length);
	}
}
