package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
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
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class FlipFinderPanel extends PluginPanel
{
	// Constants for duplicated literals
	private static final String FONT_ARIAL = "Arial";
	private static final String ERROR_PREFIX = "Error: ";
	private static final String FORMAT_QTY = "Qty: %d";
	private static final String FORMAT_SELL = "Sell: %s";
	private static final String FORMAT_ROI = "ROI: %.1f%%";
	
	// Colors for focused/selected items
	private static final Color COLOR_FOCUSED_BORDER = new Color(0, 200, 220);
	private static final Color COLOR_FOCUSED_BG = new Color(0, 60, 70);

	private final transient FlipSmartConfig config;
	private final transient FlipSmartApiClient apiClient;
	private final transient ItemManager itemManager;
	private final transient ConfigManager configManager;
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
	private final transient FlipSmartPlugin plugin;  // Reference to plugin to store recommended prices
	
	// Scroll panes for preserving scroll position during refresh
	private JScrollPane recommendedScrollPane;
	private JScrollPane activeFlipsScrollPane;
	private JScrollPane completedFlipsScrollPane;

	// Login panel components
	private JPanel loginPanel;
	private JPanel mainPanel;
	private JTextField emailField;
	private JPasswordField passwordField;
	private JLabel loginStatusLabel;
	private JButton loginButton;
	private JButton signupButton;
	private boolean isAuthenticated = false;
	
	// EasyFlip focus tracking
	private FocusedFlip currentFocus = null;
	private JPanel currentFocusedPanel = null;
	private int currentFocusedItemId = -1;
	private java.util.function.Consumer<FocusedFlip> onFocusChanged;

	public FlipFinderPanel(FlipSmartConfig config, FlipSmartApiClient apiClient, ItemManager itemManager, FlipSmartPlugin plugin, ConfigManager configManager)
	{
		super(false);
		this.config = config;
		this.apiClient = apiClient;
		this.itemManager = itemManager;
		this.plugin = plugin;
		this.configManager = configManager;

		// Initialize flip style dropdown so it's available for both panels
		flipStyleDropdown = new JComboBox<>(FlipSmartConfig.FlipStyle.values());
		flipStyleDropdown.setFocusable(false);
		flipStyleDropdown.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		flipStyleDropdown.setForeground(Color.WHITE);
		flipStyleDropdown.addActionListener(e -> {
			// Refresh recommendations when flip style changes
			if (isAuthenticated)
			{
				refresh();
			}
		});

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Build both panels
		buildLoginPanel();
		buildMainPanel();

		// Start with login panel, then check authentication
		add(loginPanel, BorderLayout.CENTER);
		
		// Check if already authenticated and switch to main panel if so
		checkAuthenticationAndShow();
	}

	/**
	 * Build the login/signup panel
	 */
	private void buildLoginPanel()
	{
		loginPanel = new JPanel();
		loginPanel.setLayout(new BorderLayout());
		loginPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Center content panel
		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setBorder(new EmptyBorder(40, 20, 40, 20));

		// Title
		JLabel titleLabel = new JLabel("Flip Smart");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 24));
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		// Subtitle
		JLabel subtitleLabel = new JLabel("Sign in to start flipping");
		subtitleLabel.setForeground(Color.LIGHT_GRAY);
		subtitleLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 14));
		subtitleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		// Email field
		JLabel emailLabel = new JLabel("Email");
		emailLabel.setForeground(Color.LIGHT_GRAY);
		emailLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));
		emailLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		emailField = new JTextField(20);
		emailField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		emailField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		emailField.setForeground(Color.WHITE);
		emailField.setCaretColor(Color.WHITE);
		emailField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(5, 10, 5, 10)
		));

		// Password field
		JLabel passwordLabel = new JLabel("Password");
		passwordLabel.setForeground(Color.LIGHT_GRAY);
		passwordLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));
		passwordLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		passwordField = new JPasswordField(20);
		passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		passwordField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		passwordField.setForeground(Color.WHITE);
		passwordField.setCaretColor(Color.WHITE);
		passwordField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(5, 10, 5, 10)
		));

		// Status label for messages
		loginStatusLabel = new JLabel(" ");
		loginStatusLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));
		loginStatusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		loginStatusLabel.setForeground(Color.LIGHT_GRAY);

		// Buttons panel
		JPanel buttonsPanel = new JPanel(new GridLayout(1, 2, 10, 0));
		buttonsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttonsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));

		// Sign Up button
		signupButton = new JButton("Sign Up");
		signupButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		signupButton.setForeground(Color.WHITE);
		signupButton.setFocusPainted(false);
		signupButton.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(8, 15, 8, 15)
		));
		signupButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
		signupButton.addActionListener(e -> handleSignup());

		// Login button
		loginButton = new JButton("Login");
		loginButton.setBackground(ColorScheme.BRAND_ORANGE);
		loginButton.setForeground(Color.WHITE);
		loginButton.setFocusPainted(false);
		loginButton.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
		loginButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
		loginButton.addActionListener(e -> handleLogin());

		buttonsPanel.add(signupButton);
		buttonsPanel.add(loginButton);

		// Add components with spacing
		contentPanel.add(titleLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		contentPanel.add(subtitleLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 30)));
		contentPanel.add(emailLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		contentPanel.add(emailField);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		contentPanel.add(passwordLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		contentPanel.add(passwordField);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 20)));
		contentPanel.add(buttonsPanel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		contentPanel.add(loginStatusLabel);

		// Center the content vertically
		loginPanel.add(contentPanel, BorderLayout.CENTER);
	}

	/**
	 * Build the main flip finder panel
	 */
	private void buildMainPanel()
	{
		mainPanel = new JPanel(new BorderLayout());
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header panel
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JLabel titleLabel = new JLabel("Flip Finder");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 16));

		// Right side buttons panel (logout + refresh)
		JPanel rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
		rightButtonsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Logout button
		JButton logoutButton = new JButton("Logout");
		logoutButton.setFocusable(false);
		logoutButton.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));
		logoutButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		logoutButton.setForeground(Color.LIGHT_GRAY);
		logoutButton.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
		logoutButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
		logoutButton.addActionListener(e -> handleLogout());

		refreshButton.setFocusable(false);
		refreshButton.addActionListener(e -> refresh());

		rightButtonsPanel.add(logoutButton);
		rightButtonsPanel.add(refreshButton);

		headerPanel.add(titleLabel, BorderLayout.WEST);
		headerPanel.add(rightButtonsPanel, BorderLayout.EAST);

		// Controls panel (flip style dropdown)
		JPanel controlsPanel = new JPanel(new BorderLayout());
		controlsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		controlsPanel.setBorder(new EmptyBorder(5, 10, 5, 10));

		JLabel flipStyleLabel = new JLabel("Style: ");
		flipStyleLabel.setForeground(Color.LIGHT_GRAY);
		flipStyleLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));

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
		statusLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));
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

		recommendedScrollPane = new JScrollPane(recommendedListContainer);
		recommendedScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		recommendedScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		recommendedScrollPane.setBorder(BorderFactory.createEmptyBorder());
		recommendedScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		recommendedScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

		// Active flips list container
		activeFlipsListContainer.setLayout(new BoxLayout(activeFlipsListContainer, BoxLayout.Y_AXIS));
		activeFlipsListContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		activeFlipsScrollPane = new JScrollPane(activeFlipsListContainer);
		activeFlipsScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		activeFlipsScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		activeFlipsScrollPane.setBorder(BorderFactory.createEmptyBorder());
		activeFlipsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		activeFlipsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

		// Completed flips list container
		completedFlipsListContainer.setLayout(new BoxLayout(completedFlipsListContainer, BoxLayout.Y_AXIS));
		completedFlipsListContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		completedFlipsScrollPane = new JScrollPane(completedFlipsListContainer);
		completedFlipsScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		completedFlipsScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		completedFlipsScrollPane.setBorder(BorderFactory.createEmptyBorder());
		completedFlipsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		completedFlipsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

		// Create tabbed pane with custom UI for full-width tabs
		tabbedPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tabbedPane.setForeground(Color.WHITE);
		tabbedPane.setTabPlacement(SwingConstants.TOP);
		
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

		mainPanel.add(topPanel, BorderLayout.NORTH);
		mainPanel.add(tabbedPane, BorderLayout.CENTER);
	}

	/**
	 * Check if already authenticated and show appropriate panel
	 */
	private void checkAuthenticationAndShow()
	{
		// Try to authenticate silently with saved credentials
		String email = config.email();
		String password = config.password();
		
		if (email != null && !email.isEmpty() && password != null && !password.isEmpty())
		{
			// Pre-fill the email field
			emailField.setText(email);
			
			// Try to authenticate in background
			java.util.concurrent.CompletableFuture.runAsync(() -> {
				FlipSmartApiClient.AuthResult result = apiClient.login(email, password);
				
				SwingUtilities.invokeLater(() -> {
					if (result.success)
					{
						showMainPanel();
					}
					else
					{
						// Stay on login panel, show message
						loginStatusLabel.setText("Please login to continue");
						loginStatusLabel.setForeground(Color.LIGHT_GRAY);
					}
				});
			});
		}
	}

	/**
	 * Handle login button click
	 */
	private void handleLogin()
	{
		String email = emailField.getText().trim();
		String password = new String(passwordField.getPassword());
		
		if (email.isEmpty() || password.isEmpty())
		{
			showLoginStatus("Please enter email and password", false);
			return;
		}
		
		setLoginButtonsEnabled(false);
		showLoginStatus("Logging in...", true);
		
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			FlipSmartApiClient.AuthResult result = apiClient.login(email, password);
			
			SwingUtilities.invokeLater(() -> {
				setLoginButtonsEnabled(true);
				
				if (result.success)
				{
					// Save credentials for next session
					saveCredentials(email, password);
					
					showLoginStatus(result.message, true);
					// Small delay to show success message
					Timer timer = new Timer(500, e -> showMainPanel());
					timer.setRepeats(false);
					timer.start();
				}
				else
				{
					showLoginStatus(result.message, false);
				}
			});
		});
	}

	/**
	 * Handle signup button click
	 */
	private void handleSignup()
	{
		String email = emailField.getText().trim();
		String password = new String(passwordField.getPassword());
		
		if (email.isEmpty() || password.isEmpty())
		{
			showLoginStatus("Please enter email and password", false);
			return;
		}
		
		setLoginButtonsEnabled(false);
		showLoginStatus("Creating account...", true);
		
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			FlipSmartApiClient.AuthResult result = apiClient.signup(email, password);
			
			SwingUtilities.invokeLater(() -> {
				setLoginButtonsEnabled(true);
				
				if (result.success)
				{
					// Save credentials for next session
					saveCredentials(email, password);
					
					showLoginStatus(result.message, true);
					// Small delay to show success message
					Timer timer = new Timer(500, e -> showMainPanel());
					timer.setRepeats(false);
					timer.start();
				}
				else
				{
					showLoginStatus(result.message, false);
				}
			});
		});
	}

	/**
	 * Save credentials for next session
	 */
	private void saveCredentials(String email, String password)
	{
		configManager.setConfiguration("flipsmart", "email", email);
		configManager.setConfiguration("flipsmart", "password", password);
	}

	/**
	 * Show status message on login panel
	 */
	private void showLoginStatus(String message, boolean success)
	{
		loginStatusLabel.setText(message);
		loginStatusLabel.setForeground(success ? new Color(100, 255, 100) : new Color(255, 100, 100));
	}

	/**
	 * Enable/disable login buttons during authentication
	 */
	private void setLoginButtonsEnabled(boolean enabled)
	{
		loginButton.setEnabled(enabled);
		signupButton.setEnabled(enabled);
		emailField.setEnabled(enabled);
		passwordField.setEnabled(enabled);
	}

	/**
	 * Switch from login panel to main panel
	 */
	private void showMainPanel()
	{
		isAuthenticated = true;
		removeAll();
		add(mainPanel, BorderLayout.CENTER);
		revalidate();
		repaint();
		
		// Load data
		refresh();
	}

	/**
	 * Switch from main panel to login panel (e.g., on auth error)
	 */
	public void showLoginPanel()
	{
		isAuthenticated = false;
		removeAll();
		add(loginPanel, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	/**
	 * Handle logout button click
	 */
	private void handleLogout()
	{
		// Clear API client authentication
		apiClient.clearAuth();
		
		// Clear password field but keep email
		passwordField.setText("");
		
		// Reset status
		loginStatusLabel.setText("Logged out successfully");
		loginStatusLabel.setForeground(Color.LIGHT_GRAY);
		
		// Show login panel
		showLoginPanel();
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
		// Save scroll position before refresh
		final int scrollPos = getScrollPosition(recommendedScrollPane);
		
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
					restoreScrollPosition(recommendedScrollPane, scrollPos);
					return;
				}

				if (response.getRecommendations() == null || response.getRecommendations().isEmpty())
				{
					showErrorInRecommended("No flip recommendations found matching your criteria.");
					restoreScrollPosition(recommendedScrollPane, scrollPos);
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
				restoreScrollPosition(recommendedScrollPane, scrollPos);
				
				// Validate focus after refresh in case focused item is no longer recommended
				validateFocus();
			});
		}).exceptionally(throwable ->
		{
			SwingUtilities.invokeLater(() ->
			{
				refreshButton.setEnabled(true);
				showErrorInRecommended(ERROR_PREFIX + throwable.getMessage());
				restoreScrollPosition(recommendedScrollPane, scrollPos);
			});
			return null;
		});
	}

	/**
	 * Refresh active flips
	 */
	public void refreshActiveFlips()
	{
		// Save scroll position before refresh
		final int scrollPos = getScrollPosition(activeFlipsScrollPane);
		
		activeFlipsListContainer.removeAll();
		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();

		// Pass current RSN to filter data for the logged-in account
		String rsn = plugin.getCurrentRsnSafe().orElse(null);
		apiClient.getActiveFlipsAsync(rsn).thenAccept(response ->
		{
			SwingUtilities.invokeLater(() ->
			{
				if (response == null)
				{
					showErrorInActiveFlips("Failed to fetch active flips. Check your API settings.");
					restoreScrollPosition(activeFlipsScrollPane, scrollPos);
					return;
				}

				currentActiveFlips.clear();
				if (response.getActiveFlips() != null)
				{
					// Filter active flips to only show items that are currently being tracked:
					// 1. Items currently in GE buy slots (pending or filled)
					// 2. Items currently in GE sell slots (waiting to fully sell)
					// 3. Items collected from GE in this session (waiting to be sold)
					// This prevents showing old stale data while keeping active items visible
					java.util.Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();
					log.debug("Active flip filter: {} tracked item IDs, {} flips from backend",
						activeItemIds.size(), response.getActiveFlips().size());
					for (ActiveFlip flip : response.getActiveFlips())
					{
						if (activeItemIds.contains(flip.getItemId()))
						{
							currentActiveFlips.add(flip);
							log.debug("Including active flip: {} (ID {})", flip.getItemName(), flip.getItemId());
						}
						else
						{
							log.debug("Filtering out active flip: {} (ID {}) - not in tracked items",
								flip.getItemName(), flip.getItemId());
						}
					}
				}

				// Get pending orders from plugin
				java.util.List<FlipSmartPlugin.PendingOrder> pendingOrders = plugin.getPendingBuyOrders();

				if (currentActiveFlips.isEmpty() && pendingOrders.isEmpty())
				{
					showNoActiveFlips();
					restoreScrollPosition(activeFlipsScrollPane, scrollPos);
					return;
				}

				// Update status label with active flips info
				if (!currentActiveFlips.isEmpty())
				{
					// Update with filtered count
					int itemCount = currentActiveFlips.size();
					int invested = currentActiveFlips.stream()
						.mapToInt(ActiveFlip::getTotalInvested)
						.sum();
					if (tabbedPane.getSelectedIndex() == 1)
					{
						statusLabel.setText(String.format("%d active %s | %s invested",
							itemCount,
							itemCount == 1 ? "flip" : "flips",
							formatGP(invested)));
					}
				}
				else if (!pendingOrders.isEmpty())
				{
					statusLabel.setText(String.format("%d pending %s",
						pendingOrders.size(),
						pendingOrders.size() == 1 ? "order" : "orders"));
				}

				// Display both active flips and pending orders
				displayActiveFlipsAndPending(currentActiveFlips, pendingOrders);
				restoreScrollPosition(activeFlipsScrollPane, scrollPos);
				
				// Validate focus after refresh in case focused item is no longer active
				validateFocus();
			});
		}).exceptionally(throwable ->
		{
			SwingUtilities.invokeLater(() ->
			{
				showErrorInActiveFlips(ERROR_PREFIX + throwable.getMessage());
				restoreScrollPosition(activeFlipsScrollPane, scrollPos);
			});
			return null;
		});
	}
	
	/**
	 * Update pending orders display (called when GE offers change)
	 * @param pendingOrders the list of pending orders (used to trigger refresh)
	 */
	@SuppressWarnings("unused")
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
		// Save scroll position before refresh
		final int scrollPos = getScrollPosition(completedFlipsScrollPane);
		
		completedFlipsListContainer.removeAll();
		completedFlipsListContainer.revalidate();
		completedFlipsListContainer.repaint();

		// Fetch last 50 completed flips for current RSN
		String rsn = plugin.getCurrentRsnSafe().orElse(null);
		apiClient.getCompletedFlipsAsync(50, rsn).thenAccept(response ->
		{
			SwingUtilities.invokeLater(() ->
			{
				if (response == null)
				{
					showErrorInCompletedFlips("Failed to fetch completed flips. Check your API settings.");
					restoreScrollPosition(completedFlipsScrollPane, scrollPos);
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
					restoreScrollPosition(completedFlipsScrollPane, scrollPos);
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
				restoreScrollPosition(completedFlipsScrollPane, scrollPos);
			});
		}).exceptionally(throwable ->
		{
			SwingUtilities.invokeLater(() ->
			{
				showErrorInCompletedFlips(ERROR_PREFIX + throwable.getMessage());
				restoreScrollPosition(completedFlipsScrollPane, scrollPos);
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
		titleLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 16));
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel instructionLabel = new JLabel("<html><center>Complete your first flip to see it here!<br>Buy and sell items to track your profits</center></html>");
		instructionLabel.setForeground(new Color(180, 180, 180));
		instructionLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 13));
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
	 * Display both active flips and pending orders.
	 * Pending orders (items still in GE buy slots) take priority over active flips
	 * to avoid showing duplicates when an item is partially filled.
	 */
	private void displayActiveFlipsAndPending(java.util.List<ActiveFlip> activeFlips, java.util.List<FlipSmartPlugin.PendingOrder> pendingOrders)
	{
		activeFlipsListContainer.removeAll();
		
		// Build a map of pending orders by itemId for smart deduplication
		// Key: itemId, Value: list of pending orders for that item
		java.util.Map<Integer, java.util.List<FlipSmartPlugin.PendingOrder>> pendingByItemId = new java.util.HashMap<>();
		for (FlipSmartPlugin.PendingOrder pending : pendingOrders)
		{
			pendingByItemId.computeIfAbsent(pending.itemId, k -> new java.util.ArrayList<>()).add(pending);
		}
		
		// First show pending orders (items currently in GE buy slots)
		if (!pendingOrders.isEmpty())
		{
			for (FlipSmartPlugin.PendingOrder pending : pendingOrders)
			{
				activeFlipsListContainer.add(createPendingOrderPanel(pending));
				activeFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
			}
		}
		
		// Then show active flips (items collected, waiting to sell)
		// Skip active flips if pending orders already account for those items (still in GE slots)
		for (ActiveFlip flip : activeFlips)
		{
			java.util.List<FlipSmartPlugin.PendingOrder> matchingPending = pendingByItemId.get(flip.getItemId());
			
			boolean shouldShow = true;
			if (matchingPending != null && !matchingPending.isEmpty())
			{
				// Sum up ALL filled quantities from pending orders for this item
				// This handles multiple buy orders for the same item correctly
				int totalPendingFilled = 0;
				for (FlipSmartPlugin.PendingOrder pending : matchingPending)
				{
					totalPendingFilled += pending.quantityFilled;
				}
				
				// If pending orders account for ALL or MORE of the active flip quantity,
				// skip the active flip to avoid showing duplicates
				// (items are still in GE slots, not collected yet)
				if (totalPendingFilled >= flip.getTotalQuantity())
				{
					shouldShow = false;
					log.debug("Skipping active flip {} - pending orders account for {} items (flip has {})",
						flip.getItemName(), totalPendingFilled, flip.getTotalQuantity());
				}
				else
				{
					// Some items were collected but pending orders still exist
					// This could happen if you collected partial fills and have more pending
					log.debug("Showing active flip {} - {} collected items not in pending ({} pending filled)",
						flip.getItemName(), flip.getTotalQuantity() - totalPendingFilled, totalPendingFilled);
				}
			}
			
			if (shouldShow)
			{
				activeFlipsListContainer.add(createActiveFlipPanel(flip));
				activeFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
			}
		}

		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();
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
		titleLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 16));
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		// Instructions
		JLabel instructionLabel = new JLabel("<html><center>Buy items from the Recommended tab<br>to start tracking your flips</center></html>");
		instructionLabel.setForeground(new Color(180, 180, 180));
		instructionLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 13));
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
		nameLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 14));

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
		priceLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));

		// Quantity
		JLabel quantityLabel = new JLabel(String.format("Qty: %d (Limit: %d)",
			rec.getRecommendedQuantity(),
			rec.getBuyLimit()));
		quantityLabel.setForeground(new Color(200, 200, 255));
		quantityLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));

		// Margin and ROI
		JLabel marginLabel = new JLabel(String.format("Margin: %s (%s ROI)",
			formatGP(rec.getMargin()),
			rec.getFormattedROI()));
		marginLabel.setForeground(new Color(100, 255, 100));
		marginLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));

		// Potential profit and total cost
		JLabel profitLabel = new JLabel(String.format("Profit: %s | Cost: %s",
			formatGP(rec.getPotentialProfit()),
			formatGP(rec.getTotalCost())));
		profitLabel.setForeground(new Color(255, 215, 0));
		profitLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));

		detailsPanel.add(priceLabel);
		detailsPanel.add(Box.createRigidArea(new Dimension(0, 2)));
		detailsPanel.add(quantityLabel);
		detailsPanel.add(Box.createRigidArea(new Dimension(0, 2)));
		detailsPanel.add(marginLabel);
		detailsPanel.add(Box.createRigidArea(new Dimension(0, 2)));
		detailsPanel.add(profitLabel);

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		// Add click listener for focus selection and expand/show more details
		panel.addMouseListener(new MouseAdapter()
		{
			private boolean expanded = false;

			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Left click: set as EasyFlip focus
				if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1)
				{
					setFocus(rec, panel);
					return;
				}
				
				// Right click or double click: expand details
				if (e.getButton() == MouseEvent.BUTTON3 || e.getClickCount() == 2)
				{
					if (!expanded)
					{
						// Add additional details
						JPanel extraDetails = new JPanel();
						extraDetails.setLayout(new BoxLayout(extraDetails, BoxLayout.Y_AXIS));
						extraDetails.setBackground(panel.getBackground());
						extraDetails.setBorder(new EmptyBorder(5, 0, 0, 0));

						JLabel liquidityLabel = new JLabel(String.format("Liquidity: %.0f (%s) | %.0f/hr",
							rec.getLiquidityScore(),
							rec.getLiquidityRating(),
							rec.getVolumePerHour()));
						liquidityLabel.setForeground(Color.CYAN);
						liquidityLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

						JLabel riskLabel = new JLabel(String.format("Risk: %.0f (%s)",
							rec.getRiskScore(),
							rec.getRiskRating()));
						riskLabel.setForeground(getRiskColor(rec.getRiskScore()));
						riskLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));
						
						// Add focus hint
						JLabel focusHint = new JLabel("Click to focus • Press hotkey to auto-fill GE");
						focusHint.setForeground(COLOR_FOCUSED_BORDER);
						focusHint.setFont(new Font(FONT_ARIAL, Font.ITALIC, 10));

						extraDetails.add(liquidityLabel);
						extraDetails.add(Box.createRigidArea(new Dimension(0, 2)));
						extraDetails.add(riskLabel);
						extraDetails.add(Box.createRigidArea(new Dimension(0, 4)));
						extraDetails.add(focusHint);

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
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != rec.getItemId())
				{
					panel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					updateChildBackgrounds(panel, ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != rec.getItemId())
				{
					panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					updateChildBackgrounds(panel, ColorScheme.DARKER_GRAY_COLOR);
				}
			}
		});

		return panel;
	}
	
	/**
	 * Update child panel backgrounds
	 */
	private void updateChildBackgrounds(JPanel panel, Color color)
	{
		for (Component comp : panel.getComponents())
		{
			if (comp instanceof JPanel)
			{
				((JPanel) comp).setBackground(color);
				for (Component child : ((JPanel) comp).getComponents())
				{
					if (child instanceof JPanel)
					{
						((JPanel) child).setBackground(color);
					}
				}
			}
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
	 * @param cashStack the cash stack value (used to trigger refresh)
	 */
	@SuppressWarnings("unused")
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
	 * Get the current active flips
	 */
	public List<ActiveFlip> getCurrentActiveFlips()
	{
		return new ArrayList<>(currentActiveFlips);
	}
	
	/**
	 * Check if an item exists in recommendations or active flips
	 */
	public boolean hasFlipForItem(int itemId)
	{
		// Check recommendations
		for (FlipRecommendation rec : currentRecommendations)
		{
			if (rec.getItemId() == itemId)
			{
				return true;
			}
		}
		// Check active flips
		for (ActiveFlip flip : currentActiveFlips)
		{
			if (flip.getItemId() == itemId)
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Validate the current focus - clear it if the flip no longer exists
	 */
	public void validateFocus()
	{
		if (currentFocusedItemId <= 0)
		{
			return;
		}
		
		// Check if the focused item still exists in recommendations or active flips
		if (!hasFlipForItem(currentFocusedItemId))
		{
			log.debug("Clearing EasyFlip focus - item {} no longer in recommendations or active flips", currentFocusedItemId);
			clearFocus();
		}
	}
	
	/**
	 * Set the callback for when the focused flip changes
	 */
	public void setOnFocusChanged(java.util.function.Consumer<FocusedFlip> callback)
	{
		this.onFocusChanged = callback;
	}
	
	/**
	 * Set a recommendation as the current focus for EasyFlip
	 */
	private void setFocus(FlipRecommendation rec, JPanel panel)
	{
		// Create focused flip for buying
		FocusedFlip newFocus = FocusedFlip.forBuy(
			rec.getItemId(),
			rec.getItemName(),
			rec.getRecommendedBuyPrice(),
			rec.getRecommendedQuantity(),
			rec.getRecommendedSellPrice()
		);
		
		updateFocus(newFocus, panel);
	}
	
	/**
	 * Set an active flip as the current focus for EasyFlip
	 */
	private void setFocus(ActiveFlip flip, JPanel panel)
	{
		// Determine sell price - use recommended if available, otherwise calculate
		int sellPrice = flip.getRecommendedSellPrice() != null 
			? flip.getRecommendedSellPrice() 
			: (int)(flip.getAverageBuyPrice() * 1.05); // Default 5% markup
		
		// Create focused flip for selling
		FocusedFlip newFocus = FocusedFlip.forSell(
			flip.getItemId(),
			flip.getItemName(),
			sellPrice,
			flip.getTotalQuantity()
		);
		
		updateFocus(newFocus, panel);
	}
	
	/**
	 * Set a pending order as the current focus for EasyFlip (selling step)
	 */
	private void setFocus(FlipSmartPlugin.PendingOrder pending, JPanel panel)
	{
		// Pending orders that are filled should show sell step
		int sellPrice = pending.recommendedSellPrice != null 
			? pending.recommendedSellPrice 
			: (int)(pending.pricePerItem * 1.05); // Default 5% markup
		
		// Create focused flip for selling the filled items
		FocusedFlip newFocus = FocusedFlip.forSell(
			pending.itemId,
			pending.itemName,
			sellPrice,
			pending.quantityFilled > 0 ? pending.quantityFilled : pending.quantity
		);
		
		updateFocus(newFocus, panel);
	}
	
	/**
	 * Update the current focus and visual state
	 */
	private void updateFocus(FocusedFlip newFocus, JPanel panel)
	{
		// Reset previous focused panel
		if (currentFocusedPanel != null)
		{
			resetPanelStyle(currentFocusedPanel);
		}
		
		// If clicking the same item, toggle off
		if (currentFocus != null && currentFocus.getItemId() == newFocus.getItemId() 
			&& currentFocus.getStep() == newFocus.getStep())
		{
			currentFocus = null;
			currentFocusedPanel = null;
			currentFocusedItemId = -1;
			
			if (onFocusChanged != null)
			{
				onFocusChanged.accept(null);
			}
			return;
		}
		
		// Set new focus
		currentFocus = newFocus;
		currentFocusedPanel = panel;
		currentFocusedItemId = newFocus.getItemId();
		
		// Update panel style to show focus
		applyFocusedStyle(panel);
		
		// Notify callback
		if (onFocusChanged != null)
		{
			onFocusChanged.accept(newFocus);
		}
		
		log.info("Set EasyFlip focus: {} - {} at {} gp x{}", 
			newFocus.getStep(), 
			newFocus.getItemName(), 
			newFocus.getCurrentStepPrice(),
			newFocus.getCurrentStepQuantity());
	}
	
	/**
	 * Apply focused style to a panel
	 */
	private void applyFocusedStyle(JPanel panel)
	{
		panel.setBackground(COLOR_FOCUSED_BG);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(COLOR_FOCUSED_BORDER, 2),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)
		));
		
		// Update child panel backgrounds
		for (Component comp : panel.getComponents())
		{
			if (comp instanceof JPanel)
			{
				((JPanel) comp).setBackground(COLOR_FOCUSED_BG);
				for (Component child : ((JPanel) comp).getComponents())
				{
					if (child instanceof JPanel)
					{
						((JPanel) child).setBackground(COLOR_FOCUSED_BG);
					}
				}
			}
		}
		
		panel.revalidate();
		panel.repaint();
	}
	
	/**
	 * Reset a panel to its default style
	 */
	private void resetPanelStyle(JPanel panel)
	{
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(8, 10, 8, 10));
		
		// Reset child panel backgrounds
		for (Component comp : panel.getComponents())
		{
			if (comp instanceof JPanel)
			{
				((JPanel) comp).setBackground(ColorScheme.DARKER_GRAY_COLOR);
				for (Component child : ((JPanel) comp).getComponents())
				{
					if (child instanceof JPanel)
					{
						((JPanel) child).setBackground(ColorScheme.DARKER_GRAY_COLOR);
					}
				}
			}
		}
		
		panel.revalidate();
		panel.repaint();
	}
	
	/**
	 * Get the current focused flip
	 */
	public FocusedFlip getCurrentFocus()
	{
		return currentFocus;
	}
	
	/**
	 * Clear the current focus
	 */
	public void clearFocus()
	{
		if (currentFocusedPanel != null)
		{
			resetPanelStyle(currentFocusedPanel);
		}
		currentFocus = null;
		currentFocusedPanel = null;
		currentFocusedItemId = -1;
		
		if (onFocusChanged != null)
		{
			onFocusChanged.accept(null);
		}
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
		nameLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 13));

		namePanel.add(iconLabel);
		namePanel.add(nameLabel);
		topPanel.add(namePanel, BorderLayout.WEST);

		// Details section with market data
		JPanel detailsPanel = new JPanel(new GridLayout(3, 2, 5, 2));
		detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detailsPanel.setBorder(new EmptyBorder(3, 38, 0, 0));

		// Row 1: Quantity (sold/total) and Buy Price (exact price for easy GE input)
		String qtyText;
		if (flip.getOriginalQuantity() > 0)
		{
			// Show sold/total format (e.g., "0/200" means 0 sold out of 200)
			int soldQuantity = flip.getOriginalQuantity() - flip.getTotalQuantity();
			qtyText = String.format("Qty: %d/%d", soldQuantity, flip.getOriginalQuantity());
		}
		else
		{
			// Fallback if original not available
			qtyText = String.format(FORMAT_QTY, flip.getTotalQuantity());
		}
		JLabel qtyLabel = new JLabel(qtyText);
		qtyLabel.setForeground(new Color(200, 200, 200));
		qtyLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		JLabel buyPriceLabel = new JLabel(String.format("Buy: %s", formatGPExact(flip.getAverageBuyPrice())));
		buyPriceLabel.setForeground(new Color(255, 120, 120)); // Light red for buy
		buyPriceLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		// Row 2: Total Invested and Target Sell Price (exact price for GE input)
		JLabel investedLabel = new JLabel(String.format("Invested: %s", formatGP(flip.getTotalInvested())));
		investedLabel.setForeground(new Color(255, 200, 100)); // Gold
		investedLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 11));

		// Fetch current market price for this item
		JLabel sellPriceLabel = new JLabel("Sell: Loading...");
		sellPriceLabel.setForeground(new Color(120, 255, 120)); // Light green for sell
		sellPriceLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		// Row 3: Potential Profit and ROI
		JLabel profitLabel = new JLabel("Profit: Loading...");
		profitLabel.setForeground(Color.LIGHT_GRAY);
		profitLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		JLabel roiLabel = new JLabel("ROI: Loading...");
		roiLabel.setForeground(Color.LIGHT_GRAY);
		roiLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

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
			sellPriceLabel.setText(String.format(FORMAT_SELL, formatGPExact(recommendedSellPrice)));

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
			profitLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 11));

			// Update ROI label with color
			roiLabel.setText(String.format(FORMAT_ROI, roi));
			roiLabel.setForeground(roi > 0 ? new Color(100, 255, 100) : new Color(255, 100, 100));
			roiLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 11));
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
							profitLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

							// Update ROI label with color
							roiLabel.setText(String.format("ROI: %.1f%%*", roi));
							roiLabel.setForeground(roi > 0 ? new Color(100, 255, 100) : new Color(255, 100, 100));
							roiLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));
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

		// Add hover effect, click to focus, and context menu
		panel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Left click: set as EasyFlip focus for selling
				if (e.getButton() == MouseEvent.BUTTON1 && !e.isPopupTrigger())
				{
					setFocus(flip, panel);
				}
			}
			
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != flip.getItemId() 
					|| !currentFocus.isSelling())
				{
					panel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					topPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					namePanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					detailsPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != flip.getItemId() 
					|| !currentFocus.isSelling())
				{
					panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					namePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
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
					
					// Add focus option
					JMenuItem focusItem = new JMenuItem("Set as EasyFlip Focus (Sell)");
					focusItem.addActionListener(ae -> setFocus(flip, panel));
					contextMenu.add(focusItem);
					
					contextMenu.addSeparator();
					
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

		JLabel nameLabel = new JLabel(pending.itemName);
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 13));

		namePanel.add(iconLabel);
		namePanel.add(nameLabel);
		topPanel.add(namePanel, BorderLayout.WEST);

		// Details section - same grid layout as active flips
		JPanel detailsPanel = new JPanel(new GridLayout(3, 2, 5, 2));
		detailsPanel.setBackground(new Color(55, 55, 65));
		detailsPanel.setBorder(new EmptyBorder(3, 38, 0, 0));

		// Row 1: Quantity (filled/total) and Offer Price
		String qtyText;
		if (pending.quantityFilled > 0)
		{
			// Show progress: filled/total
			qtyText = String.format("Qty: %d/%d", pending.quantityFilled, pending.quantity);
		}
		else
		{
			// Show just total if nothing filled yet
			qtyText = String.format("Qty: 0/%d", pending.quantity);
		}
		JLabel qtyLabel = new JLabel(qtyText);
		qtyLabel.setForeground(new Color(200, 200, 200));
		qtyLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		JLabel offerLabel = new JLabel(String.format("Offer: %s", formatGPExact(pending.pricePerItem)));
		offerLabel.setForeground(new Color(255, 120, 120)); // Light red like buy prices
		offerLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		// Row 2: Invested (actual filled amount) and Target Sell
		int actualInvestment = pending.quantityFilled * pending.pricePerItem;
		int potentialInvestment = pending.quantity * pending.pricePerItem;
		String investedText;
		if (pending.quantityFilled > 0)
		{
			investedText = String.format("Invested: %s", formatGP(actualInvestment));
		}
		else
		{
			investedText = String.format("If filled: %s", formatGP(potentialInvestment));
		}
		JLabel investedLabel = new JLabel(investedText);
		investedLabel.setForeground(pending.quantityFilled > 0 ? new Color(255, 200, 100) : new Color(200, 200, 200));
		investedLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		JLabel sellLabel = new JLabel();
		if (pending.recommendedSellPrice != null && pending.recommendedSellPrice > 0)
		{
			sellLabel.setText(String.format(FORMAT_SELL, formatGPExact(pending.recommendedSellPrice)));
			sellLabel.setForeground(new Color(120, 255, 120)); // Light green for sell
		}
		else
		{
			sellLabel.setText("Sell: --");
			sellLabel.setForeground(Color.LIGHT_GRAY);
		}
		sellLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		// Row 3: Status and Expected ROI
		String statusText;
		Color statusColor;
		if (pending.quantityFilled > 0)
		{
			// Partially filled - show progress
			int percent = (pending.quantityFilled * 100) / pending.quantity;
			statusText = String.format("GE Slot %d: Filling (%d%%)", pending.slot + 1, percent);
			statusColor = new Color(120, 200, 255); // Light blue for in-progress
		}
		else
		{
			// Waiting for first fill
			statusText = String.format("GE Slot %d: Waiting", pending.slot + 1);
			statusColor = new Color(180, 180, 180);
		}
		JLabel slotStatusLabel = new JLabel(statusText);
		slotStatusLabel.setForeground(statusColor);
		slotStatusLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		JLabel roiLabel = new JLabel();
		if (pending.recommendedSellPrice != null && pending.recommendedSellPrice > 0)
		{
			int geTax = Math.min((int)(pending.recommendedSellPrice * 0.02), 5_000_000);
			int profitPerItem = pending.recommendedSellPrice - pending.pricePerItem - geTax;
			double roi = (profitPerItem * 100.0) / pending.pricePerItem;
			roiLabel.setText(String.format(FORMAT_ROI, roi));
			roiLabel.setForeground(roi > 0 ? new Color(100, 255, 100) : new Color(255, 100, 100));
		}
		else
		{
			roiLabel.setText("ROI: --");
			roiLabel.setForeground(Color.LIGHT_GRAY);
		}
		roiLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		detailsPanel.add(qtyLabel);
		detailsPanel.add(offerLabel);
		detailsPanel.add(investedLabel);
		detailsPanel.add(sellLabel);
		detailsPanel.add(slotStatusLabel);
		detailsPanel.add(roiLabel);

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);
		
		// Add click listener for focus selection
		Color bgColor = new Color(55, 55, 65);
		panel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Only allow focus if there are filled items to sell
				if (pending.quantityFilled > 0 && e.getButton() == MouseEvent.BUTTON1)
				{
					setFocus(pending, panel);
				}
			}
			
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != pending.itemId)
				{
					Color hoverColor = new Color(65, 65, 75);
					panel.setBackground(hoverColor);
					topPanel.setBackground(hoverColor);
					namePanel.setBackground(hoverColor);
					detailsPanel.setBackground(hoverColor);
				}
			}
			
			@Override
			public void mouseExited(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != pending.itemId)
				{
					panel.setBackground(bgColor);
					topPanel.setBackground(bgColor);
					namePanel.setBackground(bgColor);
					detailsPanel.setBackground(bgColor);
				}
			}
		});
		
		// Set cursor to indicate clickable if filled
		if (pending.quantityFilled > 0)
		{
			panel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		}

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
		nameLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 13));

		namePanel.add(iconLabel);
		namePanel.add(nameLabel);
		topPanel.add(namePanel, BorderLayout.WEST);

		// Details section with profit/loss info
		JPanel detailsPanel = new JPanel(new GridLayout(3, 2, 5, 2));
		detailsPanel.setBackground(backgroundColor);
		detailsPanel.setBorder(new EmptyBorder(3, 38, 0, 0));

		// Row 1: Quantity and Buy Price
		JLabel qtyLabel = new JLabel(String.format(FORMAT_QTY, flip.getQuantity()));
		qtyLabel.setForeground(new Color(200, 200, 200));
		qtyLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		JLabel buyPriceLabel = new JLabel(String.format("Buy: %s", formatGPExact(flip.getBuyPricePerItem())));
		buyPriceLabel.setForeground(new Color(255, 120, 120));
		buyPriceLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		// Row 2: Invested and Sell Price
		JLabel investedLabel = new JLabel(String.format("Cost: %s", formatGP(flip.getBuyTotal())));
		investedLabel.setForeground(new Color(200, 200, 200));
		investedLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		JLabel sellPriceLabel = new JLabel(String.format(FORMAT_SELL, formatGPExact(flip.getSellPricePerItem())));
		sellPriceLabel.setForeground(new Color(120, 255, 120));
		sellPriceLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));

		// Row 3: Net Profit and ROI
		JLabel profitLabel = new JLabel(String.format("Profit: %s", formatGP(flip.getNetProfit())));
		Color profitColor = flip.isSuccessful() ? 
			new Color(100, 255, 100) : // Bright green
			new Color(255, 100, 100);  // Bright red
		profitLabel.setForeground(profitColor);
		profitLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 11));

		JLabel roiLabel = new JLabel(String.format(FORMAT_ROI, flip.getRoiPercent()));
		roiLabel.setForeground(profitColor);
		roiLabel.setFont(new Font(FONT_ARIAL, Font.BOLD, 11));

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
					durationLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 10));

					// GE Tax
					JLabel taxLabel = new JLabel(String.format("GE Tax: %s", formatGP(flip.getGeTax())));
					taxLabel.setForeground(new Color(180, 180, 180));
					taxLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 10));

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
			String.format("Remove %s from active flips?%n%nThis will hide it from tracking.%nUse this if you sold/used the items outside of the GE.", flip.getItemName()),
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
					if (Boolean.TRUE.equals(success))
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
	
	/**
	 * Get the current scroll position of a scroll pane.
	 */
	private int getScrollPosition(JScrollPane scrollPane)
	{
		if (scrollPane == null || scrollPane.getVerticalScrollBar() == null)
		{
			return 0;
		}
		return scrollPane.getVerticalScrollBar().getValue();
	}
	
	/**
	 * Restore the scroll position of a scroll pane after content refresh.
	 * Uses invokeLater to ensure layout is complete before restoring.
	 */
	private void restoreScrollPosition(JScrollPane scrollPane, int position)
	{
		if (scrollPane == null || scrollPane.getVerticalScrollBar() == null || position <= 0)
		{
			return;
		}
		// Use invokeLater to restore after layout is complete
		SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(position));
	}
}

