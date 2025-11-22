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
	private final JPanel recommendedListContainer = new JPanel();
	private final JPanel activeFlipsListContainer = new JPanel();
	private final JPanel completedFlipsListContainer = new JPanel();
	private final JLabel statusLabel = new JLabel("Loading...");
	private final JButton refreshButton = new JButton("Refresh");
	private final JComboBox<FlipSmartConfig.FlipStyle> flipStyleDropdown;
	private final List<FlipRecommendation> currentRecommendations = new ArrayList<>();
	private final List<ActiveFlip> currentActiveFlips = new ArrayList<>();
	private final List<CompletedFlip> currentCompletedFlips = new ArrayList<>();
	private final JTabbedPane tabbedPane = new JTabbedPane();
	private final FlipSmartPlugin plugin;  // Reference to plugin to store recommended prices

	public FlipFinderPanel(FlipSmartConfig config, FlipSmartApiClient apiClient, ItemManager itemManager, FlipSmartPlugin plugin)
	{
		super(false);
		this.config = config;
		this.apiClient = apiClient;
		this.itemManager = itemManager;
		this.plugin = plugin;

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

		// Recommended flips list container
		recommendedListContainer.setLayout(new BoxLayout(recommendedListContainer, BoxLayout.Y_AXIS));
		recommendedListContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane recommendedScrollPane = new JScrollPane(recommendedListContainer);
		recommendedScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		recommendedScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		recommendedScrollPane.setBorder(BorderFactory.createEmptyBorder());
		recommendedScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		recommendedScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

		// Active flips list container
		activeFlipsListContainer.setLayout(new BoxLayout(activeFlipsListContainer, BoxLayout.Y_AXIS));
		activeFlipsListContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane activeFlipsScrollPane = new JScrollPane(activeFlipsListContainer);
		activeFlipsScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		activeFlipsScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		activeFlipsScrollPane.setBorder(BorderFactory.createEmptyBorder());
		activeFlipsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		activeFlipsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

		// Completed flips list container
		completedFlipsListContainer.setLayout(new BoxLayout(completedFlipsListContainer, BoxLayout.Y_AXIS));
		completedFlipsListContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane completedFlipsScrollPane = new JScrollPane(completedFlipsListContainer);
		completedFlipsScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		completedFlipsScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		completedFlipsScrollPane.setBorder(BorderFactory.createEmptyBorder());
		completedFlipsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		completedFlipsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

		// Create tabbed pane with custom UI for full-width tabs
		tabbedPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tabbedPane.setForeground(Color.WHITE);
		tabbedPane.setTabPlacement(JTabbedPane.TOP);
		
		// Custom UI to make tabs fill the full width
		tabbedPane.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
			@Override
			protected int calculateTabWidth(int tabPlacement, int tabIndex, java.awt.FontMetrics metrics) {
				// Calculate equal width for all tabs
				int totalWidth = tabbedPane.getWidth();
				int tabCount = tabbedPane.getTabCount();
				if (tabCount > 0 && totalWidth > 0) {
					return totalWidth / tabCount;
				}
				return super.calculateTabWidth(tabPlacement, tabIndex, metrics);
			}
			
			@Override
			protected void paintTabBackground(java.awt.Graphics g, int tabPlacement, int tabIndex,
											  int x, int y, int w, int h, boolean isSelected) {
				// Paint background for tabs
				g.setColor(isSelected ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
				g.fillRect(x, y, w, h);
			}
			
			@Override
			protected void paintTabBorder(java.awt.Graphics g, int tabPlacement, int tabIndex,
										  int x, int y, int w, int h, boolean isSelected) {
				// Paint border/underline for selected tab
				if (isSelected) {
					g.setColor(ColorScheme.BRAND_ORANGE);
					g.fillRect(x, y + h - 3, w, 3);
				}
			}
			
			@Override
			protected void paintContentBorder(java.awt.Graphics g, int tabPlacement, int selectedIndex) {
				// Don't paint content border
			}
		});
		
		tabbedPane.addTab("Recommended", recommendedScrollPane);
		tabbedPane.addTab("Active Flips", activeFlipsScrollPane);
		tabbedPane.addTab("Completed", completedFlipsScrollPane);
		
		// Add listener to update status when switching tabs
		tabbedPane.addChangeListener(e ->
		{
			int selectedIndex = tabbedPane.getSelectedIndex();
			if (selectedIndex == 1 && !currentActiveFlips.isEmpty())
			{
				// Switched to Active Flips tab, update status
				int itemCount = currentActiveFlips.size();
				int invested = currentActiveFlips.stream()
					.mapToInt(ActiveFlip::getTotalInvested)
					.sum();
				statusLabel.setText(String.format("%d active %s | %s invested",
					itemCount,
					itemCount == 1 ? "flip" : "flips",
					formatGP(invested)));
			}
			else if (selectedIndex == 2 && !currentCompletedFlips.isEmpty())
			{
				// Switched to Completed Flips tab, update status
				int flipCount = currentCompletedFlips.size();
				int totalProfit = currentCompletedFlips.stream()
					.mapToInt(CompletedFlip::getNetProfit)
					.sum();
				statusLabel.setText(String.format("%d completed | %s profit",
					flipCount,
					formatGP(totalProfit)));
			}
			else if (selectedIndex == 0 && !currentRecommendations.isEmpty())
			{
				// Switched back to Recommended tab, restore original status
				FlipFinderResponse response = new FlipFinderResponse();
				response.setRecommendations(currentRecommendations);
				updateStatusLabel(response);
			}
		});

		add(topPanel, BorderLayout.NORTH);
		add(tabbedPane, BorderLayout.CENTER);

		// Initial load
		refresh();
	}

	/**
	 * Refresh flip recommendations, active flips, and completed flips
	 */
	public void refresh()
	{
		refreshRecommendations();
		refreshActiveFlips();
		refreshCompletedFlips();
	}

	/**
	 * Refresh recommended flips
	 */
	private void refreshRecommendations()
	{
		statusLabel.setText("Loading recommendations...");
		refreshButton.setEnabled(false);
		recommendedListContainer.removeAll();
		recommendedListContainer.revalidate();
		recommendedListContainer.repaint();

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
					showErrorInRecommended("Failed to fetch recommendations. Check your API settings.");
					return;
				}

				if (response.getRecommendations() == null || response.getRecommendations().isEmpty())
				{
					showErrorInRecommended("No flip recommendations found matching your criteria.");
					return;
				}

				currentRecommendations.clear();
				currentRecommendations.addAll(response.getRecommendations());

				// Store recommended sell prices in the plugin for transaction tracking
				for (FlipRecommendation rec : response.getRecommendations())
				{
					plugin.setRecommendedSellPrice(rec.getItemId(), rec.getRecommendedSellPrice());
				}

				updateStatusLabel(response);
				populateRecommendations(response.getRecommendations());
			});
		}).exceptionally(throwable ->
		{
			SwingUtilities.invokeLater(() ->
			{
				refreshButton.setEnabled(true);
				showErrorInRecommended("Error: " + throwable.getMessage());
			});
			return null;
		});
	}

	/**
	 * Refresh active flips
	 */
	private void refreshActiveFlips()
	{
		activeFlipsListContainer.removeAll();
		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();

		apiClient.getActiveFlipsAsync().thenAccept(response ->
		{
			SwingUtilities.invokeLater(() ->
			{
				if (response == null)
				{
					showErrorInActiveFlips("Failed to fetch active flips. Check your API settings.");
					return;
				}

				currentActiveFlips.clear();
				if (response.getActiveFlips() != null)
				{
					currentActiveFlips.addAll(response.getActiveFlips());
				}

				// Get pending orders from plugin
				java.util.List<FlipSmartPlugin.PendingOrder> pendingOrders = plugin.getPendingBuyOrders();

				if (currentActiveFlips.isEmpty() && pendingOrders.isEmpty())
				{
					showNoActiveFlips();
					return;
				}

				// Update status label with active flips info
				if (!currentActiveFlips.isEmpty())
				{
					updateActiveFlipsStatus(response);
				}
				else if (!pendingOrders.isEmpty())
				{
					statusLabel.setText(String.format("%d pending %s",
						pendingOrders.size(),
						pendingOrders.size() == 1 ? "order" : "orders"));
				}

				// Display both active flips and pending orders
				displayActiveFlipsAndPending(currentActiveFlips, pendingOrders);
			});
		}).exceptionally(throwable ->
		{
			SwingUtilities.invokeLater(() ->
			{
				showErrorInActiveFlips("Error: " + throwable.getMessage());
			});
			return null;
		});
	}
	
	/**
	 * Update pending orders display (called when GE offers change)
	 */
	public void updatePendingOrders(java.util.List<FlipSmartPlugin.PendingOrder> pendingOrders)
	{
		// Only update if we're on the Active Flips tab
		if (tabbedPane.getSelectedIndex() == 1)
		{
			refreshActiveFlips();
		}
	}

	/**
	 * Refresh completed flips
	 */
	private void refreshCompletedFlips()
	{
		completedFlipsListContainer.removeAll();
		completedFlipsListContainer.revalidate();
		completedFlipsListContainer.repaint();

		// Fetch last 50 completed flips
		apiClient.getCompletedFlipsAsync(50).thenAccept(response ->
		{
			SwingUtilities.invokeLater(() ->
			{
				if (response == null)
				{
					showErrorInCompletedFlips("Failed to fetch completed flips. Check your API settings.");
					return;
				}

				currentCompletedFlips.clear();
				if (response.getFlips() != null)
				{
					currentCompletedFlips.addAll(response.getFlips());
				}

				if (currentCompletedFlips.isEmpty())
				{
					showNoCompletedFlips();
					return;
				}

				// Update status if on completed flips tab
				if (tabbedPane.getSelectedIndex() == 2)
				{
					int totalProfit = currentCompletedFlips.stream()
						.mapToInt(CompletedFlip::getNetProfit)
						.sum();
					statusLabel.setText(String.format("%d completed | %s profit",
						currentCompletedFlips.size(),
						formatGP(totalProfit)));
				}

				populateCompletedFlips(currentCompletedFlips);
			});
		}).exceptionally(throwable ->
		{
			SwingUtilities.invokeLater(() ->
			{
				showErrorInCompletedFlips("Error: " + throwable.getMessage());
			});
			return null;
		});
	}

	/**
	 * Show error message in completed flips tab
	 */
	private void showErrorInCompletedFlips(String message)
	{
		completedFlipsListContainer.removeAll();

		PluginErrorPanel errorPanel = new PluginErrorPanel();
		errorPanel.setContent("Completed Flips", message);
		completedFlipsListContainer.add(errorPanel);

		completedFlipsListContainer.revalidate();
		completedFlipsListContainer.repaint();
	}

	/**
	 * Show message when there are no completed flips
	 */
	private void showNoCompletedFlips()
	{
		completedFlipsListContainer.removeAll();

		JPanel emptyPanel = new JPanel();
		emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
		emptyPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		emptyPanel.setBorder(new EmptyBorder(60, 20, 60, 20));

		JLabel titleLabel = new JLabel("No completed flips");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(new Font("Arial", Font.BOLD, 16));
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel instructionLabel = new JLabel("<html><center>Complete your first flip to see it here!<br>Buy and sell items to track your profits</center></html>");
		instructionLabel.setForeground(new Color(180, 180, 180));
		instructionLabel.setFont(new Font("Arial", Font.PLAIN, 13));
		instructionLabel.setHorizontalAlignment(SwingConstants.CENTER);
		instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		emptyPanel.add(titleLabel);
		emptyPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		emptyPanel.add(instructionLabel);

		completedFlipsListContainer.add(emptyPanel);

		statusLabel.setText("0 completed flips");

		completedFlipsListContainer.revalidate();
		completedFlipsListContainer.repaint();
	}

	/**
	 * Populate the completed flips list
	 */
	private void populateCompletedFlips(java.util.List<CompletedFlip> flips)
	{
		completedFlipsListContainer.removeAll();

		for (CompletedFlip flip : flips)
		{
			completedFlipsListContainer.add(createCompletedFlipPanel(flip));
			completedFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
		}

		completedFlipsListContainer.revalidate();
		completedFlipsListContainer.repaint();
	}
	
	/**
	 * Display both active flips and pending orders
	 */
	private void displayActiveFlipsAndPending(java.util.List<ActiveFlip> activeFlips, java.util.List<FlipSmartPlugin.PendingOrder> pendingOrders)
	{
		activeFlipsListContainer.removeAll();
		
		// First show pending orders (orders not yet filled)
		if (!pendingOrders.isEmpty())
		{
			for (FlipSmartPlugin.PendingOrder pending : pendingOrders)
			{
				activeFlipsListContainer.add(createPendingOrderPanel(pending));
				activeFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
			}
		}
		
		// Then show active flips (items that have filled)
		for (ActiveFlip flip : activeFlips)
		{
			activeFlipsListContainer.add(createActiveFlipPanel(flip));
			activeFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
		}

		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();
	}

	/**
	 * Update status label with active flips information
	 */
	private void updateActiveFlipsStatus(ActiveFlipsResponse response)
	{
		int itemCount = response.getTotalItems();
		int invested = response.getTotalInvested();
		
		if (tabbedPane.getSelectedIndex() == 1) // Active Flips tab
		{
			statusLabel.setText(String.format("%d active %s | %s invested",
				itemCount,
				itemCount == 1 ? "flip" : "flips",
				formatGP(invested)));
		}
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
	 * Show an error message in recommended tab
	 */
	private void showErrorInRecommended(String message)
	{
		statusLabel.setText("Error");
		recommendedListContainer.removeAll();

		PluginErrorPanel errorPanel = new PluginErrorPanel();
		errorPanel.setContent("Flip Finder", message);
		recommendedListContainer.add(errorPanel);

		recommendedListContainer.revalidate();
		recommendedListContainer.repaint();
	}

	/**
	 * Show an error message in active flips tab
	 */
	private void showErrorInActiveFlips(String message)
	{
		activeFlipsListContainer.removeAll();

		PluginErrorPanel errorPanel = new PluginErrorPanel();
		errorPanel.setContent("Active Flips", message);
		activeFlipsListContainer.add(errorPanel);

		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();
	}

	/**
	 * Show message when there are no active flips
	 */
	private void showNoActiveFlips()
	{
		activeFlipsListContainer.removeAll();

		JPanel emptyPanel = new JPanel();
		emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
		emptyPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		emptyPanel.setBorder(new EmptyBorder(60, 20, 60, 20));

		// Main message
		JLabel titleLabel = new JLabel("No active flips");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(new Font("Arial", Font.BOLD, 16));
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		// Instructions
		JLabel instructionLabel = new JLabel("<html><center>Buy items from the Recommended tab<br>to start tracking your flips</center></html>");
		instructionLabel.setForeground(new Color(180, 180, 180));
		instructionLabel.setFont(new Font("Arial", Font.PLAIN, 13));
		instructionLabel.setHorizontalAlignment(SwingConstants.CENTER);
		instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		emptyPanel.add(titleLabel);
		emptyPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		emptyPanel.add(instructionLabel);

		activeFlipsListContainer.add(emptyPanel);

		// Update status label
		statusLabel.setText("0 active flips");

		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();
	}

	/**
	 * Populate the list with recommendations
	 */
	private void populateRecommendations(List<FlipRecommendation> recommendations)
	{
		recommendedListContainer.removeAll();

		for (FlipRecommendation rec : recommendations)
		{
			recommendedListContainer.add(createRecommendationPanel(rec));
			recommendedListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
		}

		recommendedListContainer.revalidate();
		recommendedListContainer.repaint();
	}

	/**
	 * Populate the active flips list
	 */
	private void populateActiveFlips(List<ActiveFlip> activeFlips)
	{
		activeFlipsListContainer.removeAll();

		for (ActiveFlip flip : activeFlips)
		{
			activeFlipsListContainer.add(createActiveFlipPanel(flip));
			activeFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
		}

		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();
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

	/**
	 * Create a panel for an active flip with current market data
	 */
	private JPanel createActiveFlipPanel(ActiveFlip flip)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));
		panel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

		// Top section: Item icon and name
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		namePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Get item image
		AsyncBufferedImage itemImage = itemManager.getImage(flip.getItemId());
		JLabel iconLabel = new JLabel();
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

		JLabel nameLabel = new JLabel(flip.getItemName());
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(new Font("Arial", Font.BOLD, 13));

		namePanel.add(iconLabel);
		namePanel.add(nameLabel);
		topPanel.add(namePanel, BorderLayout.WEST);

		// Details section with market data
		JPanel detailsPanel = new JPanel(new GridLayout(3, 2, 5, 2));
		detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detailsPanel.setBorder(new EmptyBorder(3, 38, 0, 0));

		// Row 1: Quantity and Buy Price (exact price for easy GE input)
		JLabel qtyLabel = new JLabel(String.format("Qty: %d", flip.getTotalQuantity()));
		qtyLabel.setForeground(new Color(200, 200, 200));
		qtyLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		JLabel buyPriceLabel = new JLabel(String.format("Buy: %s", formatGPExact(flip.getAverageBuyPrice())));
		buyPriceLabel.setForeground(new Color(255, 120, 120)); // Light red for buy
		buyPriceLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		// Row 2: Total Invested and Target Sell Price (exact price for GE input)
		JLabel investedLabel = new JLabel(String.format("Invested: %s", formatGP(flip.getTotalInvested())));
		investedLabel.setForeground(new Color(255, 200, 100)); // Gold
		investedLabel.setFont(new Font("Arial", Font.BOLD, 11));

		// Fetch current market price for this item
		JLabel sellPriceLabel = new JLabel("Sell: Loading...");
		sellPriceLabel.setForeground(new Color(120, 255, 120)); // Light green for sell
		sellPriceLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		// Row 3: Potential Profit and ROI
		JLabel profitLabel = new JLabel("Profit: Loading...");
		profitLabel.setForeground(Color.LIGHT_GRAY);
		profitLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		JLabel roiLabel = new JLabel("ROI: Loading...");
		roiLabel.setForeground(Color.LIGHT_GRAY);
		roiLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		detailsPanel.add(qtyLabel);
		detailsPanel.add(buyPriceLabel);
		detailsPanel.add(investedLabel);
		detailsPanel.add(sellPriceLabel);
		detailsPanel.add(profitLabel);
		detailsPanel.add(roiLabel);

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		// Use recommended sell price if available, otherwise fetch current market price
		if (flip.getRecommendedSellPrice() != null && flip.getRecommendedSellPrice() > 0)
		{
			// Use recommended price from when the flip was suggested
			int recommendedSellPrice = flip.getRecommendedSellPrice();
			
			// Update sell price
			sellPriceLabel.setText(String.format("Sell: %s", formatGPExact(recommendedSellPrice)));

			// Calculate GE tax (2% capped at 5M)
			int geTax = Math.min((int)(recommendedSellPrice * 0.02), 5_000_000);
			
			// Calculate potential profit (per item)
			int profitPerItem = recommendedSellPrice - flip.getAverageBuyPrice() - geTax;
			int totalProfit = profitPerItem * flip.getTotalQuantity();

			// Calculate ROI
			double roi = (profitPerItem * 100.0) / flip.getAverageBuyPrice();

			// Update profit label with color
			String profitText = Math.abs(totalProfit) >= 100_000 
				? formatGP(totalProfit) 
				: formatGPExact(totalProfit);
			profitLabel.setText(String.format("Profit: %s", profitText));
			profitLabel.setForeground(totalProfit > 0 ? new Color(100, 255, 100) : new Color(255, 100, 100));
			profitLabel.setFont(new Font("Arial", Font.BOLD, 11));

			// Update ROI label with color
			roiLabel.setText(String.format("ROI: %.1f%%", roi));
			roiLabel.setForeground(roi > 0 ? new Color(100, 255, 100) : new Color(255, 100, 100));
			roiLabel.setFont(new Font("Arial", Font.BOLD, 11));
		}
		else
		{
			// Fallback: fetch current market price
			apiClient.getItemAnalysisAsync(flip.getItemId()).thenAccept(analysis ->
			{
				SwingUtilities.invokeLater(() ->
				{
					if (analysis != null && analysis.getCurrentPrices() != null)
					{
						FlipAnalysis.CurrentPrices prices = analysis.getCurrentPrices();
						Integer currentSellPrice = prices.getHigh();
						Integer geTax = prices.getGeTax();

						if (currentSellPrice != null && geTax != null)
						{
							// Update sell price with exact number for easy GE input
							sellPriceLabel.setText(String.format("Sell: %s*", formatGPExact(currentSellPrice)));

							// Calculate potential profit (per item)
							int profitPerItem = currentSellPrice - flip.getAverageBuyPrice() - geTax;
							int totalProfit = profitPerItem * flip.getTotalQuantity();

							// Calculate ROI
							double roi = (profitPerItem * 100.0) / flip.getAverageBuyPrice();

							// Update profit label with color
							String profitText = Math.abs(totalProfit) >= 100_000 
								? formatGP(totalProfit) 
								: formatGPExact(totalProfit);
							profitLabel.setText(String.format("Profit: %s*", profitText));
							profitLabel.setForeground(totalProfit > 0 ? new Color(100, 255, 100) : new Color(255, 100, 100));
							profitLabel.setFont(new Font("Arial", Font.PLAIN, 11));

							// Update ROI label with color
							roiLabel.setText(String.format("ROI: %.1f%%*", roi));
							roiLabel.setForeground(roi > 0 ? new Color(100, 255, 100) : new Color(255, 100, 100));
							roiLabel.setFont(new Font("Arial", Font.PLAIN, 11));
						}
						else
						{
							sellPriceLabel.setText("Sell: N/A");
							profitLabel.setText("Profit: N/A");
							roiLabel.setText("ROI: N/A");
						}
					}
					else
					{
						sellPriceLabel.setText("Sell: N/A");
						profitLabel.setText("Profit: N/A");
						roiLabel.setText("ROI: N/A");
					}
				});
			});
		}

		// Add hover effect and context menu
		panel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				panel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				topPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				namePanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				detailsPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				namePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				showContextMenu(e, flip);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				showContextMenu(e, flip);
			}

			private void showContextMenu(MouseEvent e, ActiveFlip flip)
			{
				if (e.isPopupTrigger())
				{
					JPopupMenu contextMenu = new JPopupMenu();
					
					JMenuItem dismissItem = new JMenuItem("Dismiss from Active Flips");
					dismissItem.addActionListener(ae -> dismissActiveFlip(flip));
					contextMenu.add(dismissItem);
					
					contextMenu.show(e.getComponent(), e.getX(), e.getY());
				}
			}
		});

		return panel;
	}

	/**
	 * Create a panel for a pending order (not yet filled)
	 */
	private JPanel createPendingOrderPanel(FlipSmartPlugin.PendingOrder pending)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.setBackground(new Color(55, 55, 65)); // Slightly different color for pending
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

		// Top section: Item icon and name
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(new Color(55, 55, 65));

		JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		namePanel.setBackground(new Color(55, 55, 65));

		// Get item image
		AsyncBufferedImage itemImage = itemManager.getImage(pending.itemId);
		JLabel iconLabel = new JLabel();
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

		JLabel nameLabel = new JLabel(pending.itemName + " [PENDING]");
		nameLabel.setForeground(new Color(255, 200, 100)); // Yellow to indicate pending
		nameLabel.setFont(new Font("Arial", Font.BOLD, 13));

		namePanel.add(iconLabel);
		namePanel.add(nameLabel);
		topPanel.add(namePanel, BorderLayout.WEST);

		// Details section - same grid layout as active flips
		JPanel detailsPanel = new JPanel(new GridLayout(3, 2, 5, 2));
		detailsPanel.setBackground(new Color(55, 55, 65));
		detailsPanel.setBorder(new EmptyBorder(3, 38, 0, 0));

		// Row 1: Quantity and Offer Price
		JLabel qtyLabel = new JLabel(String.format("Qty: %d", pending.quantity));
		qtyLabel.setForeground(new Color(200, 200, 200));
		qtyLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		JLabel offerLabel = new JLabel(String.format("Offer: %s", formatGPExact(pending.pricePerItem)));
		offerLabel.setForeground(new Color(255, 120, 120)); // Light red like buy prices
		offerLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		// Row 2: Invested (potential) and Target Sell
		int potentialInvestment = pending.quantity * pending.pricePerItem;
		JLabel investedLabel = new JLabel(String.format("If filled: %s", formatGP(potentialInvestment)));
		investedLabel.setForeground(new Color(200, 200, 200));
		investedLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		JLabel sellLabel = new JLabel();
		if (pending.recommendedSellPrice != null && pending.recommendedSellPrice > 0)
		{
			sellLabel.setText(String.format("Sell: %s", formatGPExact(pending.recommendedSellPrice)));
			sellLabel.setForeground(new Color(120, 255, 120)); // Light green for sell
		}
		else
		{
			sellLabel.setText("Sell: --");
			sellLabel.setForeground(Color.LIGHT_GRAY);
		}
		sellLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		// Row 3: Status and Expected ROI
		JLabel statusLabel = new JLabel(String.format("GE Slot %d: Waiting", pending.slot + 1));
		statusLabel.setForeground(new Color(180, 180, 180));
		statusLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		JLabel roiLabel = new JLabel();
		if (pending.recommendedSellPrice != null && pending.recommendedSellPrice > 0)
		{
			int geTax = Math.min((int)(pending.recommendedSellPrice * 0.02), 5_000_000);
			int profitPerItem = pending.recommendedSellPrice - pending.pricePerItem - geTax;
			double roi = (profitPerItem * 100.0) / pending.pricePerItem;
			roiLabel.setText(String.format("ROI: %.1f%%", roi));
			roiLabel.setForeground(roi > 0 ? new Color(100, 255, 100) : new Color(255, 100, 100));
		}
		else
		{
			roiLabel.setText("ROI: --");
			roiLabel.setForeground(Color.LIGHT_GRAY);
		}
		roiLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		detailsPanel.add(qtyLabel);
		detailsPanel.add(offerLabel);
		detailsPanel.add(investedLabel);
		detailsPanel.add(sellLabel);
		detailsPanel.add(statusLabel);
		detailsPanel.add(roiLabel);

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		return panel;
	}
	
	/**
	 * Create a panel for a completed flip
	 */
	private JPanel createCompletedFlipPanel(CompletedFlip flip)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		// Color based on profit/loss
		Color backgroundColor = flip.isSuccessful() ? 
			new Color(40, 60, 40) : // Dark green for profit
			new Color(60, 40, 40);  // Dark red for loss
		panel.setBackground(backgroundColor);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

		// Top section: Item icon and name
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(backgroundColor);

		JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		namePanel.setBackground(backgroundColor);

		// Get item image
		AsyncBufferedImage itemImage = itemManager.getImage(flip.getItemId());
		JLabel iconLabel = new JLabel();
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

		JLabel nameLabel = new JLabel(flip.getItemName());
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(new Font("Arial", Font.BOLD, 13));

		namePanel.add(iconLabel);
		namePanel.add(nameLabel);
		topPanel.add(namePanel, BorderLayout.WEST);

		// Details section with profit/loss info
		JPanel detailsPanel = new JPanel(new GridLayout(3, 2, 5, 2));
		detailsPanel.setBackground(backgroundColor);
		detailsPanel.setBorder(new EmptyBorder(3, 38, 0, 0));

		// Row 1: Quantity and Buy Price
		JLabel qtyLabel = new JLabel(String.format("Qty: %d", flip.getQuantity()));
		qtyLabel.setForeground(new Color(200, 200, 200));
		qtyLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		JLabel buyPriceLabel = new JLabel(String.format("Buy: %s", formatGPExact(flip.getBuyPricePerItem())));
		buyPriceLabel.setForeground(new Color(255, 120, 120));
		buyPriceLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		// Row 2: Invested and Sell Price
		JLabel investedLabel = new JLabel(String.format("Cost: %s", formatGP(flip.getBuyTotal())));
		investedLabel.setForeground(new Color(200, 200, 200));
		investedLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		JLabel sellPriceLabel = new JLabel(String.format("Sell: %s", formatGPExact(flip.getSellPricePerItem())));
		sellPriceLabel.setForeground(new Color(120, 255, 120));
		sellPriceLabel.setFont(new Font("Arial", Font.PLAIN, 11));

		// Row 3: Net Profit and ROI
		JLabel profitLabel = new JLabel(String.format("Profit: %s", formatGP(flip.getNetProfit())));
		Color profitColor = flip.isSuccessful() ? 
			new Color(100, 255, 100) : // Bright green
			new Color(255, 100, 100);  // Bright red
		profitLabel.setForeground(profitColor);
		profitLabel.setFont(new Font("Arial", Font.BOLD, 11));

		JLabel roiLabel = new JLabel(String.format("ROI: %.1f%%", flip.getRoiPercent()));
		roiLabel.setForeground(profitColor);
		roiLabel.setFont(new Font("Arial", Font.BOLD, 11));

		detailsPanel.add(qtyLabel);
		detailsPanel.add(buyPriceLabel);
		detailsPanel.add(investedLabel);
		detailsPanel.add(sellPriceLabel);
		detailsPanel.add(profitLabel);
		detailsPanel.add(roiLabel);

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		// Add click to show more details
		panel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		panel.addMouseListener(new MouseAdapter()
		{
			private boolean expanded = false;

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!expanded)
				{
					// Add extra details
					JPanel extraDetails = new JPanel();
					extraDetails.setLayout(new BoxLayout(extraDetails, BoxLayout.Y_AXIS));
					extraDetails.setBackground(backgroundColor);
					extraDetails.setBorder(new EmptyBorder(5, 38, 0, 0));

					// Duration
					int hours = flip.getFlipDurationSeconds() / 3600;
					int minutes = (flip.getFlipDurationSeconds() % 3600) / 60;
					String duration = hours > 0 ? 
						String.format("%dh %dm", hours, minutes) :
						String.format("%dm", minutes);

					JLabel durationLabel = new JLabel(String.format("Duration: %s", duration));
					durationLabel.setForeground(new Color(180, 180, 180));
					durationLabel.setFont(new Font("Arial", Font.PLAIN, 10));

					// GE Tax
					JLabel taxLabel = new JLabel(String.format("GE Tax: %s", formatGP(flip.getGeTax())));
					taxLabel.setForeground(new Color(180, 180, 180));
					taxLabel.setFont(new Font("Arial", Font.PLAIN, 10));

					extraDetails.add(durationLabel);
					extraDetails.add(Box.createRigidArea(new Dimension(0, 2)));
					extraDetails.add(taxLabel);

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
		});

		return panel;
	}
	
	/**
	 * Dismiss an active flip (remove from tracking)
	 */
	private void dismissActiveFlip(ActiveFlip flip)
	{
		int result = JOptionPane.showConfirmDialog(
			this,
			String.format("Remove %s from active flips?\n\nThis will hide it from tracking.\nUse this if you sold/used the items outside of the GE.", flip.getItemName()),
			"Dismiss Active Flip",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.QUESTION_MESSAGE
		);

		if (result == JOptionPane.YES_OPTION)
		{
			// Dismiss asynchronously
			apiClient.dismissActiveFlipAsync(flip.getItemId()).thenAccept(success ->
			{
				SwingUtilities.invokeLater(() ->
				{
					if (success)
					{
						// Refresh the active flips list
						refreshActiveFlips();
						JOptionPane.showMessageDialog(
							this,
							String.format("%s has been removed from active flips.", flip.getItemName()),
							"Dismissed",
							JOptionPane.INFORMATION_MESSAGE
						);
					}
					else
					{
						JOptionPane.showMessageDialog(
							this,
							"Failed to dismiss active flip. Please try again.",
							"Error",
							JOptionPane.ERROR_MESSAGE
						);
					}
				});
			});
		}
	}
}

