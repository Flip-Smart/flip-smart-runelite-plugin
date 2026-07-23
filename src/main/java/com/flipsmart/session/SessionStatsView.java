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
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * The session-performance block above the panel tabs: session profit, session
 * duration, realised GP/hour and projected GP/hour. Presentation only — every
 * figure comes from a {@link SessionStats.Snapshot}.
 *
 * A header row carries a collapse arrow: clicking it hides the four data rows so
 * users on smaller viewports can reclaim vertical space for the recommendation
 * list. The collapsed state is applied via {@link #setCollapsed} and reported
 * through {@link #setToggleListener} so the panel can persist it.
 */
public final class SessionStatsView
{
	private static final Color COLOR_PROFIT_GREEN = new Color(100, 255, 100);
	private static final Color COLOR_LOSS_RED = new Color(255, 100, 100);
	private static final int ROW_HEIGHT = 18;
	private static final String ARROW_EXPANDED = "▼";
	private static final String ARROW_COLLAPSED = "▶";

	private final JPanel component = new JPanel();
	private final JPanel body = new JPanel();
	private final JLabel toggleArrow = new JLabel(ARROW_EXPANDED, SwingConstants.RIGHT);
	private final JLabel sessionProfitValue = new JLabel();
	private final JLabel sessionDurationValue = new JLabel();
	private final JLabel realisedRateValue = new JLabel();
	private final JLabel projectedRateValue = new JLabel();

	private boolean collapsed;
	private transient Consumer<Boolean> toggleListener;

	public SessionStatsView()
	{
		component.setLayout(new BoxLayout(component, BoxLayout.Y_AXIS));
		component.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		component.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));

		component.add(buildHeader());

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		body.add(buildRow("Profit this session", sessionProfitValue));
		body.add(buildRow("Session duration", sessionDurationValue));
		body.add(buildRow("Realised GP/hour", realisedRateValue));
		body.add(buildRow("Projected GP/hour", projectedRateValue));
		component.add(body);
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel title = new JLabel("Session");
		title.setForeground(Color.LIGHT_GRAY);

		toggleArrow.setForeground(Color.LIGHT_GRAY);

		header.add(title, BorderLayout.WEST);
		header.add(toggleArrow, BorderLayout.EAST);
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				toggle();
			}
		});
		return header;
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

	public boolean isCollapsed()
	{
		return collapsed;
	}

	/** Notified with the new collapsed state whenever the user clicks the header. */
	public void setToggleListener(Consumer<Boolean> listener)
	{
		this.toggleListener = listener;
	}

	/** Apply a collapsed state (e.g. restoring a persisted preference) without notifying the listener. */
	public void setCollapsed(boolean value)
	{
		collapsed = value;
		applyCollapsed();
	}

	private void toggle()
	{
		collapsed = !collapsed;
		applyCollapsed();
		if (toggleListener != null)
		{
			toggleListener.accept(collapsed);
		}
	}

	private void applyCollapsed()
	{
		body.setVisible(!collapsed);
		toggleArrow.setText(collapsed ? ARROW_COLLAPSED : ARROW_EXPANDED);
		component.revalidate();
		component.repaint();
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
