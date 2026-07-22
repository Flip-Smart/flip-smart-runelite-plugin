package com.flipsmart.session;

import net.runelite.client.ui.ColorScheme;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;

/**
 * The four-line session-performance block above the panel tabs: session profit,
 * session duration, realised GP/hour and projected GP/hour. Presentation only —
 * every figure comes from a {@link SessionStats.Snapshot}.
 */
public final class SessionStatsView
{
	private static final Color COLOR_PROFIT_GREEN = new Color(100, 255, 100);
	private static final Color COLOR_LOSS_RED = new Color(255, 100, 100);
	private static final int ROW_HEIGHT = 18;

	private final JPanel component = new JPanel();
	private final JLabel sessionProfitValue = new JLabel();
	private final JLabel sessionDurationValue = new JLabel();
	private final JLabel realisedRateValue = new JLabel();
	private final JLabel projectedRateValue = new JLabel();

	public SessionStatsView()
	{
		component.setLayout(new BoxLayout(component, BoxLayout.Y_AXIS));
		component.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		component.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
		component.add(buildRow("Profit this session", sessionProfitValue));
		component.add(buildRow("Session duration", sessionDurationValue));
		component.add(buildRow("Realised GP/hour", realisedRateValue));
		component.add(buildRow("Projected GP/hour", projectedRateValue));
	}

	private JPanel buildRow(String label, JLabel valueLabel)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		JLabel name = new JLabel(label);
		name.setForeground(Color.LIGHT_GRAY);
		valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(name, BorderLayout.WEST);
		row.add(valueLabel, BorderLayout.EAST);
		return row;
	}

	public Component getComponent()
	{
		return component;
	}

	public void render(SessionStats.Snapshot snapshot)
	{
		sessionProfitValue.setText(SessionStats.formatSignedGp(snapshot.realisedProfit));
		sessionProfitValue.setForeground(profitColor(snapshot.realisedProfit));

		sessionDurationValue.setText(SessionStats.formatDuration(snapshot.activeMs));
		sessionDurationValue.setForeground(Color.WHITE);

		applyRate(realisedRateValue, snapshot.realisedGpPerHour);
		applyRate(projectedRateValue, snapshot.projectedGpPerHour);
	}

	private void applyRate(JLabel label, Long perHour)
	{
		label.setText(SessionStats.formatGpPerHour(perHour));
		label.setForeground(perHour == null ? Color.LIGHT_GRAY : profitColor(perHour));
	}

	private Color profitColor(long amount)
	{
		return amount < 0 ? COLOR_LOSS_RED : COLOR_PROFIT_GREEN;
	}
}
