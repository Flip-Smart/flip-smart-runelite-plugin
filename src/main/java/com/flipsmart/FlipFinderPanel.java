package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.util.AsyncBufferedImage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class FlipFinderPanel extends PluginPanel
{
	private final FlipSmartConfig config;
	private final FlipSmartApiClient apiClient;
	private final ItemManager itemManager;
	private final JPanel listContainer = new JPanel();
	private final JLabel statusLabel = new JLabel("Loading...");
	private final JButton refreshButton = new JButton("Refresh");
	private final JComboBox<FlipSmartConfig.FlipStyle> flipStyleDropdown;
	private final List<FlipRecommendation> currentRecommendations = new ArrayList<>();

	public FlipFinderPanel(FlipSmartConfig config, FlipSmartApiClient apiClient, ItemManager itemManager)
	{
		super(false);
		this.config = config;
		this.apiClient = apiClient;
		this.itemManager = itemManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header panel
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JLabel titleLabel = new JLabel("Flip Finder");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(new Font("Arial", Font.BOLD, 16));

		refreshButton.setFocusable(false);
		refreshButton.addActionListener(e -> refresh());

		headerPanel.add(titleLabel, BorderLayout.WEST);
		headerPanel.add(refreshButton, BorderLayout.EAST);

		// Controls panel (flip style dropdown)
		JPanel controlsPanel = new JPanel(new BorderLayout());
		controlsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		controlsPanel.setBorder(new EmptyBorder(5, 10, 5, 10));

		JLabel flipStyleLabel = new JLabel("Style: ");
		flipStyleLabel.setForeground(Color.LIGHT_GRAY);
		flipStyleLabel.setFont(new Font("Arial", Font.PLAIN, 12));

		// Create flip style dropdown
		flipStyleDropdown = new JComboBox<>(FlipSmartConfig.FlipStyle.values());
		flipStyleDropdown.setSelectedItem(config.flipStyle());
		flipStyleDropdown.setFocusable(false);
		flipStyleDropdown.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		flipStyleDropdown.setForeground(Color.WHITE);
		flipStyleDropdown.addActionListener(e -> {
			// Refresh recommendations when flip style changes
			refresh();
		});

		// Custom renderer for better appearance
		flipStyleDropdown.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
														  boolean isSelected, boolean cellHasFocus) {
				Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (c instanceof JLabel && value instanceof FlipSmartConfig.FlipStyle) {
					FlipSmartConfig.FlipStyle style = (FlipSmartConfig.FlipStyle) value;
					((JLabel) c).setText(style.name().charAt(0) + style.name().substring(1).toLowerCase());
				}
				if (isSelected) {
					c.setBackground(ColorScheme.BRAND_ORANGE);
				} else {
					c.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
				c.setForeground(Color.WHITE);
				return c;
			}
		});

		JPanel dropdownWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		dropdownWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		dropdownWrapper.add(flipStyleLabel);
		dropdownWrapper.add(flipStyleDropdown);

		controlsPanel.add(dropdownWrapper, BorderLayout.WEST);

		// Status panel
		JPanel statusPanel = new JPanel(new BorderLayout());
		statusPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statusPanel.setBorder(new EmptyBorder(5, 10, 5, 10));
		statusLabel.setForeground(Color.LIGHT_GRAY);
		statusLabel.setFont(new Font("Arial", Font.PLAIN, 12));
		statusPanel.add(statusLabel, BorderLayout.CENTER);

		// Combine controls and status into top panel
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topPanel.add(headerPanel);
		topPanel.add(controlsPanel);
		topPanel.add(statusPanel);

		// List container
		listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
		listContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(listContainer);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

		add(topPanel, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);

		// Initial load
		refresh();
	}

	/**
	 * Refresh flip recommendations
	 */
	public void refresh()
	{
		statusLabel.setText("Loading recommendations...");
		refreshButton.setEnabled(false);
		listContainer.removeAll();
		listContainer.revalidate();
		listContainer.repaint();

		// Fetch recommendations asynchronously
		Integer cashStack = getCashStack();
		// Use the selected flip style from dropdown instead of config
		FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
		String flipStyle = selectedStyle != null ? selectedStyle.getApiValue() : config.flipStyle().getApiValue();
		int limit = Math.max(1, Math.min(50, config.flipFinderLimit()));

		apiClient.getFlipRecommendationsAsync(cashStack, flipStyle, limit).thenAccept(response ->
		{
			SwingUtilities.invokeLater(() ->
			{
				refreshButton.setEnabled(true);

				if (response == null)
				{
					showError("Failed to fetch recommendations. Check your API settings.");
					return;
				}

				if (response.getRecommendations() == null || response.getRecommendations().isEmpty())
				{
					showError("No flip recommendations found matching your criteria.");
					return;
				}

				currentRecommendations.clear();
				currentRecommendations.addAll(response.getRecommendations());

				updateStatusLabel(response);
				populateRecommendations(response.getRecommendations());
			});
		}).exceptionally(throwable ->
		{
			SwingUtilities.invokeLater(() ->
			{
				refreshButton.setEnabled(true);
				showError("Error: " + throwable.getMessage());
			});
			return null;
		});
	}

	/**
	 * Get the player's current cash stack from inventory
	 * Returns null if not available
	 * Can be overridden by subclasses to provide actual cash stack
	 */
	protected Integer getCashStack()
	{
		// This will be overridden by the plugin
		// For now, return null to get all recommendations
		return null;
	}

	/**
	 * Update the status label with response info
	 */
	private void updateStatusLabel(FlipFinderResponse response)
	{
		String flipStyleText = config.flipStyle().toString();
		int count = response.getRecommendations().size();
		
		if (response.getCashStack() != null)
		{
			statusLabel.setText(String.format("%s | %d flips | Cash: %s",
				flipStyleText,
				count,
				formatGP(response.getCashStack())));
		}
		else
		{
			statusLabel.setText(String.format("%s | %d flips", flipStyleText, count));
		}
	}

	/**
	 * Show an error message
	 */
	private void showError(String message)
	{
		statusLabel.setText("Error");
		listContainer.removeAll();

		PluginErrorPanel errorPanel = new PluginErrorPanel();
		errorPanel.setContent("Flip Finder", message);
		listContainer.add(errorPanel);

		listContainer.revalidate();
		listContainer.repaint();
	}

	/**
	 * Populate the list with recommendations
	 */
	private void populateRecommendations(List<FlipRecommendation> recommendations)
	{
		listContainer.removeAll();

		for (FlipRecommendation rec : recommendations)
		{
			listContainer.add(createRecommendationPanel(rec));
			listContainer.add(Box.createRigidArea(new Dimension(0, 5)));
		}

		listContainer.revalidate();
		listContainer.repaint();
	}

	/**
	 * Create a panel for a single recommendation
	 */
	private JPanel createRecommendationPanel(FlipRecommendation rec)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(8, 10, 8, 10));
		panel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		// Ensure panel doesn't exceed container width
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		// Item name panel
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Item icon and name
		JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		namePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Get item image
		AsyncBufferedImage itemImage = itemManager.getImage(rec.getItemId());
		JLabel iconLabel = new JLabel();
		if (itemImage != null)
		{
			// Set initial icon
			iconLabel.setIcon(new ImageIcon(itemImage));
			
			// Add observer to update when image loads
			itemImage.onLoaded(() ->
			{
				iconLabel.setIcon(new ImageIcon(itemImage));
				iconLabel.revalidate();
				iconLabel.repaint();
			});
		}
		else
		{
			// Placeholder if no image
			iconLabel.setPreferredSize(new Dimension(32, 32));
		}

		JLabel nameLabel = new JLabel(rec.getItemName());
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(new Font("Arial", Font.BOLD, 14));

		namePanel.add(iconLabel);
		namePanel.add(nameLabel);

		topPanel.add(namePanel, BorderLayout.WEST);

		// Details panel
		JPanel detailsPanel = new JPanel();
		detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
		detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detailsPanel.setBorder(new EmptyBorder(5, 0, 0, 0));

		// Recommended Buy/Sell prices (full format with commas for easy copying)
		JLabel priceLabel = new JLabel(String.format("Buy: %s | Sell: %s",
			formatGPExact(rec.getRecommendedBuyPrice()),
			formatGPExact(rec.getRecommendedSellPrice())));
		priceLabel.setForeground(Color.LIGHT_GRAY);
		priceLabel.setFont(new Font("Arial", Font.PLAIN, 12));

		// Quantity
		JLabel quantityLabel = new JLabel(String.format("Qty: %d (Limit: %d)",
			rec.getRecommendedQuantity(),
			rec.getBuyLimit()));
		quantityLabel.setForeground(new Color(200, 200, 255));
		quantityLabel.setFont(new Font("Arial", Font.PLAIN, 12));

		// Margin and ROI
		JLabel marginLabel = new JLabel(String.format("Margin: %s (%s ROI)",
			formatGP(rec.getMargin()),
			rec.getFormattedROI()));
		marginLabel.setForeground(new Color(100, 255, 100));
		marginLabel.setFont(new Font("Arial", Font.PLAIN, 12));

		// Potential profit and total cost
		JLabel profitLabel = new JLabel(String.format("Profit: %s | Cost: %s",
			formatGP(rec.getPotentialProfit()),
			formatGP(rec.getTotalCost())));
		profitLabel.setForeground(new Color(255, 215, 0));
		profitLabel.setFont(new Font("Arial", Font.PLAIN, 12));

		detailsPanel.add(priceLabel);
		detailsPanel.add(Box.createRigidArea(new Dimension(0, 2)));
		detailsPanel.add(quantityLabel);
		detailsPanel.add(Box.createRigidArea(new Dimension(0, 2)));
		detailsPanel.add(marginLabel);
		detailsPanel.add(Box.createRigidArea(new Dimension(0, 2)));
		detailsPanel.add(profitLabel);

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		// Add click listener to expand/show more details
		panel.addMouseListener(new MouseAdapter()
		{
			private boolean expanded = false;

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!expanded)
				{
					// Add additional details
					JPanel extraDetails = new JPanel();
					extraDetails.setLayout(new BoxLayout(extraDetails, BoxLayout.Y_AXIS));
					extraDetails.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					extraDetails.setBorder(new EmptyBorder(5, 0, 0, 0));

					JLabel liquidityLabel = new JLabel(String.format("Liquidity: %.0f (%s) | %.0f/hr",
						rec.getLiquidityScore(),
						rec.getLiquidityRating(),
						rec.getVolumePerHour()));
					liquidityLabel.setForeground(Color.CYAN);
					liquidityLabel.setFont(new Font("Arial", Font.PLAIN, 11));

					JLabel riskLabel = new JLabel(String.format("Risk: %.0f (%s)",
						rec.getRiskScore(),
						rec.getRiskRating()));
					riskLabel.setForeground(getRiskColor(rec.getRiskScore()));
					riskLabel.setFont(new Font("Arial", Font.PLAIN, 11));

					extraDetails.add(liquidityLabel);
					extraDetails.add(Box.createRigidArea(new Dimension(0, 2)));
					extraDetails.add(riskLabel);

					panel.add(extraDetails, BorderLayout.SOUTH);
					expanded = true;
				}
				else
				{
					// Remove extra details
					if (panel.getComponentCount() > 2)
					{
						panel.remove(2);
						expanded = false;
					}
				}

				panel.revalidate();
				panel.repaint();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				panel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		return panel;
	}

	/**
	 * Get color based on efficiency score
	 */
	private Color getEfficiencyColor(double score)
	{
		if (score >= 80)
		{
			return new Color(100, 255, 100); // Bright green
		}
		else if (score >= 65)
		{
			return new Color(150, 255, 100); // Yellow-green
		}
		else if (score >= 50)
		{
			return new Color(255, 255, 100); // Yellow
		}
		else
		{
			return new Color(255, 150, 100); // Orange
		}
	}

	/**
	 * Get color based on risk score
	 */
	private Color getRiskColor(double score)
	{
		if (score <= 20)
		{
			return new Color(100, 255, 100); // Green
		}
		else if (score <= 40)
		{
			return new Color(150, 255, 100); // Yellow-green
		}
		else if (score <= 60)
		{
			return new Color(255, 255, 100); // Yellow
		}
		else
		{
			return new Color(255, 100, 100); // Red
		}
	}

	/**
	 * Format GP amount for display
	 */
	private String formatGP(int amount)
	{
		if (amount >= 1_000_000)
		{
			return String.format("%.1fM", amount / 1_000_000.0);
		}
		else if (amount >= 1_000)
		{
			return String.format("%.1fK", amount / 1_000.0);
		}
		return String.valueOf(amount);
	}

	/**
	 * Format GP amount with commas for exact input (e.g., "1,234,567")
	 */
	private String formatGPExact(int amount)
	{
		return String.format("%,d", amount);
	}

	/**
	 * Set the cash stack for filtering recommendations
	 */
	public void setCashStack(Integer cashStack)
	{
		// This will trigger a refresh with the new cash stack
		refresh();
	}

	/**
	 * Get the current recommendations
	 */
	public List<FlipRecommendation> getCurrentRecommendations()
	{
		return new ArrayList<>(currentRecommendations);
	}
}

