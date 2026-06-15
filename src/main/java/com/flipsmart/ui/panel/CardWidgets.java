package com.flipsmart.ui.panel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Shared low-level Swing builders used by all flip cards (recommendation,
 * active, completed) and the panel itself. Each method is a pure factory or a
 * mutation scoped to the widgets passed in - no panel state, no callbacks.
 */
public final class CardWidgets
{
	private static final Font FONT_PLAIN_12 = new Font("Arial", Font.PLAIN, 12);

	private CardWidgets()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Create a styled JLabel with common settings for detail rows
	 */
	public static JLabel createStyledLabel(String text, Color foreground)
	{
		JLabel label = new JLabel(text);
		label.setForeground(foreground);
		label.setFont(FONT_PLAIN_12);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/**
	 * Create a details panel with BoxLayout for vertical rows
	 */
	public static JPanel createDetailsPanel(Color bgColor)
	{
		JPanel detailsPanel = new JPanel();
		detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
		detailsPanel.setBackground(bgColor);
		detailsPanel.setBorder(new EmptyBorder(5, 0, 0, 0));
		return detailsPanel;
	}

	/**
	 * Add labels to a details panel with standard 2px vertical spacing
	 */
	public static void addLabelsWithSpacing(JPanel panel, JLabel... labels)
	{
		for (int i = 0; i < labels.length; i++)
		{
			panel.add(labels[i]);
			if (i < labels.length - 1)
			{
				panel.add(Box.createRigidArea(new Dimension(0, 2)));
			}
		}
	}

	/**
	 * Update background color for multiple panels (used in mouse listeners)
	 */
	public static void setPanelBackgrounds(Color color, JPanel... panels)
	{
		for (JPanel panel : panels)
		{
			panel.setBackground(color);
		}
	}

	/**
	 * Create a base panel with common settings for flip/recommendation items
	 */
	public static JPanel createBaseItemPanel(Color bgColor, int maxHeight, boolean handCursor)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.setBackground(bgColor);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHeight));
		if (handCursor)
		{
			panel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		}
		return panel;
	}

	/**
	 * Setup icon label with async image loading
	 */
	public static void setupIconLabel(JLabel iconLabel, AsyncBufferedImage itemImage)
	{
		if (itemImage != null)
		{
			iconLabel.setIcon(new ImageIcon(itemImage));
			itemImage.onLoaded(() ->
			{
				iconLabel.setIcon(new ImageIcon(itemImage));
				iconLabel.revalidate();
				iconLabel.repaint();
			});
		}
		else
		{
			iconLabel.setPreferredSize(new Dimension(32, 32));
		}
	}

	/**
	 * Apply a price indicator background color to the active flip panel and its children.
	 * Used to visually indicate whether the sell price is higher or lower than the original recommendation.
	 */
	public static void applyPriceIndicatorBackground(JPanel panel, JPanel topPanel, JPanel namePanel, JPanel detailsPanel, Color bgColor)
	{
		panel.setBackground(bgColor);
		topPanel.setBackground(bgColor);
		namePanel.setBackground(bgColor);
		detailsPanel.setBackground(bgColor);
		panel.putClientProperty("baseBackgroundColor", bgColor);
		panel.repaint();
	}
}
