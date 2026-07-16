package com.flipsmart.exit;

import net.runelite.client.ui.ColorScheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;

/** Center-screen pop-up letting the player pick a Breakeven or Instant exit. */
public final class ExitTradesDialog
{
	private ExitTradesDialog()
	{
	}

	public static void open(Component parent, ExitTradesController controller)
	{
		Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		JDialog dialog = new JDialog(owner, "Exit Trades", JDialog.ModalityType.APPLICATION_MODAL);
		dialog.getContentPane().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		dialog.setLayout(new BorderLayout(0, 8));

		JLabel heading = new JLabel("How do you want to exit your open trades?");
		heading.setForeground(Color.LIGHT_GRAY);
		heading.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
		dialog.add(heading, BorderLayout.NORTH);

		JPanel cards = new JPanel(new GridLayout(1, 2, 8, 0));
		cards.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cards.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
		cards.add(card("Exit Trades (Breakeven)",
			"Targets a fair, near-no-loss exit price. May still instant-sell for a small profit if the margin moved in your favor.",
			() -> {
				controller.start(ExitTradesMode.BREAKEVEN);
				controller.surfaceCurrent();
				dialog.dispose();
			}));
		cards.add(card("Exit Trades (Instant)",
			"Lists at the current instant-sell price for an immediate exit, regardless of profit or loss.",
			() -> {
				controller.start(ExitTradesMode.INSTANT);
				controller.surfaceCurrent();
				dialog.dispose();
			}));
		dialog.add(cards, BorderLayout.CENTER);

		dialog.pack();
		dialog.setMinimumSize(new Dimension(360, 190));
		dialog.setLocationRelativeTo(owner);
		dialog.setVisible(true);
	}

	private static JPanel card(String title, String description, Runnable onSelect)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel t = new JLabel(title);
		t.setForeground(Color.WHITE);
		t.setFont(t.getFont().deriveFont(Font.BOLD));
		t.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel d = new JLabel("<html><body style='width:150px'>" + description + "</body></html>");
		d.setForeground(Color.LIGHT_GRAY);
		d.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton choose = new JButton("Select");
		choose.setFocusable(false);
		choose.setAlignmentX(Component.LEFT_ALIGNMENT);
		choose.addActionListener(e -> onSelect.run());

		panel.add(t);
		panel.add(Box.createVerticalStrut(6));
		panel.add(d);
		panel.add(Box.createVerticalStrut(8));
		panel.add(choose);
		return panel;
	}
}
