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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class FlipFinderPanel extends PluginPanel
{
	// Constants for duplicated literals
	private static final String FONT_ARIAL = "Arial";
	private static final String ERROR_PREFIX = "Error: ";
	private static final String FORMAT_QTY = "Qty: %d";
	private static final String FORMAT_SELL = "Sell: %s";
	private static final String FORMAT_ROI = "ROI: %.1f%%";
	private static final String FORMAT_BUY_SELL = "Buy: %s | Sell: %s";
	private static final String FORMAT_PROFIT_COST = "Profit: %s | Cost: %s";
	private static final String FORMAT_MARGIN_ROI = "Margin: %s (%.1f%% ROI)";
	private static final String FORMAT_MARGIN_ROI_LOSS = "Margin: %s (%.1f%% ROI) - Loss";
	private static final String FORMAT_LIQUIDITY = "Liquidity: %.0f (%s) | %s";
	private static final String FORMAT_RISK = "Risk: %.0f (%s)";
	private static final String UNKNOWN_RATING = "Unknown";
	private static final String LIQUIDITY_NA = "Liquidity: N/A";
	private static final String RISK_NA = "Risk: N/A";
	
	// Colors for focused/selected items
	private static final Color COLOR_FOCUSED_BORDER = new Color(0, 200, 220);
	private static final Color COLOR_FOCUSED_BG = new Color(0, 60, 70);
	
	// Common UI colors
	private static final Color COLOR_TEXT_GRAY = new Color(200, 200, 200);
	private static final Color COLOR_TEXT_DIM_GRAY = new Color(180, 180, 180);
	private static final Color COLOR_YELLOW = new Color(255, 255, 100);
	private static final Color COLOR_PROFIT_GREEN = new Color(100, 255, 100);
	private static final Color COLOR_LOSS_RED = new Color(255, 100, 100);
	private static final Color COLOR_BUY_RED = new Color(255, 120, 120);
	private static final Color COLOR_SELL_GREEN = new Color(120, 255, 120);
	
	// Common fonts
	private static final Font FONT_PLAIN_12 = new Font(FONT_ARIAL, Font.PLAIN, 12);
	private static final Font FONT_BOLD_12 = new Font(FONT_ARIAL, Font.BOLD, 12);
	private static final Font FONT_BOLD_13 = new Font(FONT_ARIAL, Font.BOLD, 13);
	private static final Font FONT_BOLD_16 = new Font(FONT_ARIAL, Font.BOLD, 16);
	
	// Time-based sell price thresholds (in minutes)
	// High volume items (>500k daily trades): switch to loss-minimizing after 10 min
	private static final int HIGH_VOLUME_THRESHOLD = 500_000;
	private static final int HIGH_VOLUME_TIME_MINUTES = 10;
	// Regular items: switch after 20 min
	private static final int REGULAR_TIME_MINUTES = 20;
	// High value items (>250M): give them 30 min before loss-minimizing
	private static final int HIGH_VALUE_THRESHOLD = 250_000_000;
	private static final int HIGH_VALUE_TIME_MINUTES = 30;

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
	
	// Flip Assist focus tracking
	private transient FocusedFlip currentFocus = null;
	private transient JPanel currentFocusedPanel = null;
	private transient int currentFocusedItemId = -1;
	private transient java.util.function.Consumer<FocusedFlip> onFocusChanged;

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
		emailLabel.setFont(FONT_PLAIN_12);
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
		passwordLabel.setFont(FONT_PLAIN_12);
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
		loginStatusLabel.setFont(FONT_PLAIN_12);
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
		titleLabel.setFont(FONT_BOLD_16);

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
		refreshButton.addActionListener(e -> refresh(true));

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
		flipStyleLabel.setFont(FONT_PLAIN_12);

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
		statusLabel.setFont(FONT_PLAIN_12);
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
		// Always show scrollbar so layout always accounts for it
		recommendedScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

		// Active flips list container
		activeFlipsListContainer.setLayout(new BoxLayout(activeFlipsListContainer, BoxLayout.Y_AXIS));
		activeFlipsListContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		activeFlipsScrollPane = new JScrollPane(activeFlipsListContainer);
		activeFlipsScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		activeFlipsScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		activeFlipsScrollPane.setBorder(BorderFactory.createEmptyBorder());
		activeFlipsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		// Always show scrollbar so layout always accounts for it
		activeFlipsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

		// Completed flips list container
		completedFlipsListContainer.setLayout(new BoxLayout(completedFlipsListContainer, BoxLayout.Y_AXIS));
		completedFlipsListContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		completedFlipsScrollPane = new JScrollPane(completedFlipsListContainer);
		completedFlipsScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		completedFlipsScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		completedFlipsScrollPane.setBorder(BorderFactory.createEmptyBorder());
		completedFlipsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		// Always show scrollbar so layout always accounts for it
		completedFlipsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

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
		loginStatusLabel.setForeground(success ? COLOR_PROFIT_GREEN : COLOR_LOSS_RED);
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
	 * Refresh flip recommendations, active flips, and completed flips.
	 * Uses deterministic recommendations (no randomization) for auto-refresh.
	 */
	public void refresh()
	{
		refresh(false);
	}

	/**
	 * Refresh flip recommendations, active flips, and completed flips.
	 *
	 * @param shuffleSuggestions if true, randomizes suggestions within quality tiers (for manual refresh)
	 */
	public void refresh(boolean shuffleSuggestions)
	{
		refreshRecommendations(shuffleSuggestions);
		refreshActiveFlips();
		refreshCompletedFlips();
	}

	/**
	 * Refresh recommended flips
	 *
	 * @param shuffleSuggestions if true, randomizes suggestions within quality tiers
	 */
	private void refreshRecommendations(boolean shuffleSuggestions)
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
		// Use the selected flip style from dropdown
		FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
		String flipStyle = selectedStyle != null ? selectedStyle.getApiValue() : FlipSmartConfig.FlipStyle.BALANCED.getApiValue();
		int limit = Math.max(1, Math.min(50, config.flipFinderLimit()));
		// Only generate random seed for manual refresh to get variety in suggestions
		// Auto-refresh keeps same items so user can focus on setting up flips
		Integer randomSeed = shuffleSuggestions ? ThreadLocalRandom.current().nextInt() : null;

		apiClient.getFlipRecommendationsAsync(cashStack, flipStyle, limit, randomSeed).thenAccept(response ->
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
		errorPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		errorPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
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
		emptyPanel.setBorder(new EmptyBorder(60, 15, 60, 15));
		// Ensure panel fills width in BoxLayout
		emptyPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		emptyPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		JLabel titleLabel = new JLabel("No completed flips");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(FONT_BOLD_16);
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel instructionLabel = new JLabel("<html><center>Complete your first flip to see<br>it here! Buy and sell items to<br>track your profits</center></html>");
		instructionLabel.setForeground(COLOR_TEXT_DIM_GRAY);
		instructionLabel.setFont(FONT_PLAIN_12);
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
		java.util.Map<Integer, java.util.List<FlipSmartPlugin.PendingOrder>> pendingByItemId = buildPendingOrdersMap(pendingOrders);
		
		// First show pending orders (items currently in GE buy slots)
		for (FlipSmartPlugin.PendingOrder pending : pendingOrders)
		{
			activeFlipsListContainer.add(createPendingOrderPanel(pending));
			activeFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
		}
		
		// Then show active flips (items collected, waiting to sell)
		// Skip active flips if pending orders already account for those items
		for (ActiveFlip flip : activeFlips)
		{
			if (shouldShowActiveFlip(flip, pendingByItemId))
			{
				activeFlipsListContainer.add(createActiveFlipPanel(flip));
				activeFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
			}
		}

		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();
	}

	/**
	 * Build a map of pending orders grouped by item ID
	 */
	private java.util.Map<Integer, java.util.List<FlipSmartPlugin.PendingOrder>> buildPendingOrdersMap(
			java.util.List<FlipSmartPlugin.PendingOrder> pendingOrders)
	{
		java.util.Map<Integer, java.util.List<FlipSmartPlugin.PendingOrder>> pendingByItemId = new java.util.HashMap<>();
		for (FlipSmartPlugin.PendingOrder pending : pendingOrders)
		{
			pendingByItemId.computeIfAbsent(pending.itemId, k -> new java.util.ArrayList<>()).add(pending);
		}
		return pendingByItemId;
	}

	/**
	 * Determine if an active flip should be shown (not duplicated by pending orders)
	 */
	private boolean shouldShowActiveFlip(ActiveFlip flip, 
			java.util.Map<Integer, java.util.List<FlipSmartPlugin.PendingOrder>> pendingByItemId)
	{
		java.util.List<FlipSmartPlugin.PendingOrder> matchingPending = pendingByItemId.get(flip.getItemId());
		
		if (matchingPending == null || matchingPending.isEmpty())
		{
			return true;
		}
		
		// Sum up ALL filled quantities from pending orders for this item
		int totalPendingFilled = 0;
		for (FlipSmartPlugin.PendingOrder pending : matchingPending)
		{
			totalPendingFilled += pending.quantityFilled;
		}
		
		// If pending orders account for ALL or MORE of the active flip quantity,
		// skip the active flip to avoid showing duplicates
		if (totalPendingFilled >= flip.getTotalQuantity())
		{
			log.debug("Skipping active flip {} - pending orders account for {} items (flip has {})",
				flip.getItemName(), totalPendingFilled, flip.getTotalQuantity());
			return false;
		}
		
		log.debug("Showing active flip {} - {} collected items not in pending ({} pending filled)",
			flip.getItemName(), flip.getTotalQuantity() - totalPendingFilled, totalPendingFilled);
		return true;
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
		FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
		String flipStyleText = selectedStyle != null ? selectedStyle.toString() : "Balanced";
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
		errorPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		errorPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
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
		errorPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		errorPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
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
		emptyPanel.setBorder(new EmptyBorder(60, 15, 60, 15));
		// Ensure panel fills width in BoxLayout
		emptyPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		emptyPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		// Main message
		JLabel titleLabel = new JLabel("No active flips");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(FONT_BOLD_16);
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		// Instructions - use smaller text to fit better
		JLabel instructionLabel = new JLabel("<html><center>Buy items from the Recommended<br>tab to start tracking your flips</center></html>");
		instructionLabel.setForeground(COLOR_TEXT_DIM_GRAY);
		instructionLabel.setFont(FONT_PLAIN_12);
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
		JPanel panel = createBaseItemPanel(ColorScheme.DARKER_GRAY_COLOR, Integer.MAX_VALUE, true);

		// Item header with icon and name
		HeaderPanels header = createItemHeaderPanels(rec.getItemId(), rec.getItemName(), ColorScheme.DARKER_GRAY_COLOR);
		JPanel topPanel = header.topPanel;

		// Details panel with all recommendation info
		JPanel detailsPanel = createRecommendationDetailsPanel(rec);

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		// Add mouse listener for interactions
		addRecommendationPanelListeners(panel, rec);

		return panel;
	}

	/**
	 * Create the details panel for a recommendation with all stats
	 */
	private JPanel createRecommendationDetailsPanel(FlipRecommendation rec)
	{
		JPanel detailsPanel = createDetailsPanel(ColorScheme.DARKER_GRAY_COLOR);

		// Recommended Buy/Sell prices
		JLabel priceLabel = new JLabel(formatBuySellText(rec.getRecommendedBuyPrice(), rec.getRecommendedSellPrice()));
		priceLabel.setForeground(Color.LIGHT_GRAY);
		priceLabel.setFont(FONT_PLAIN_12);

		// Quantity
		JLabel quantityLabel = new JLabel(String.format("Qty: %d (Limit: %d)",
			rec.getRecommendedQuantity(), rec.getBuyLimit()));
		quantityLabel.setForeground(new Color(200, 200, 255));
		quantityLabel.setFont(FONT_PLAIN_12);

		// Margin and ROI
		JLabel marginLabel = new JLabel(String.format("Margin: %s (%s ROI)",
			formatGP(rec.getMargin()), rec.getFormattedROI()));
		marginLabel.setForeground(COLOR_PROFIT_GREEN);
		marginLabel.setFont(FONT_PLAIN_12);

		// Potential profit and total cost
		JLabel profitLabel = new JLabel(formatProfitCostText(rec.getPotentialProfit(), rec.getTotalCost()));
		profitLabel.setForeground(new Color(255, 215, 0));
		profitLabel.setFont(FONT_PLAIN_12);

		// Liquidity info
		JLabel liquidityLabel = new JLabel(formatLiquidityText(
			rec.getLiquidityScore(), rec.getLiquidityRating(), rec.getVolumePerHour()));
		liquidityLabel.setForeground(Color.CYAN);
		liquidityLabel.setFont(FONT_PLAIN_12);

		// Risk info
		JLabel riskLabel = new JLabel(formatRiskText(rec.getRiskScore(), rec.getRiskRating()));
		riskLabel.setForeground(getRiskColor(rec.getRiskScore()));
		riskLabel.setFont(FONT_PLAIN_12);

		addLabelsWithSpacing(detailsPanel, priceLabel, quantityLabel, marginLabel, 
			profitLabel, liquidityLabel, riskLabel);

		return detailsPanel;
	}

	/**
	 * Add mouse listeners for recommendation panel (focus, expand, hover)
	 */
	private void addRecommendationPanelListeners(JPanel panel, FlipRecommendation rec)
	{
		panel.addMouseListener(new MouseAdapter()
		{
			private boolean expanded = false;

			@Override
			public void mouseClicked(MouseEvent e)
			{
				handleRecommendationClick(e, panel, rec);
			}

			private void handleRecommendationClick(MouseEvent e, JPanel panel, FlipRecommendation rec)
			{
				// Left click: set as Flip Assist focus
				if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1)
				{
					setFocus(rec, panel);
					return;
				}
				
				// Right click or double click: toggle focus hint
				if (e.getButton() != MouseEvent.BUTTON3 && e.getClickCount() != 2)
				{
					return;
				}

				expanded = toggleExpandedState(panel, expanded);
				panel.revalidate();
				panel.repaint();
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
	}

	/**
	 * Toggle expanded state of a panel (show/hide focus hint)
	 */
	private boolean toggleExpandedState(JPanel panel, boolean currentlyExpanded)
	{
		if (!currentlyExpanded)
		{
			// Add focus hint
			JPanel extraDetails = new JPanel();
			extraDetails.setLayout(new BoxLayout(extraDetails, BoxLayout.Y_AXIS));
			extraDetails.setBackground(panel.getBackground());
			extraDetails.setBorder(new EmptyBorder(5, 0, 0, 0));

			JLabel focusHint = new JLabel("Click to focus • Press hotkey to auto-fill GE");
			focusHint.setForeground(COLOR_FOCUSED_BORDER);
			focusHint.setFont(new Font(FONT_ARIAL, Font.ITALIC, 10));

			extraDetails.add(focusHint);
			panel.add(extraDetails, BorderLayout.SOUTH);
			return true;
		}
		else
		{
			// Remove extra details
			if (panel.getComponentCount() > 2)
			{
				panel.remove(2);
			}
			return false;
		}
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
			return COLOR_PROFIT_GREEN; // Green
		}
		else if (score <= 40)
		{
			return new Color(150, 255, 100); // Yellow-green
		}
		else if (score <= 60)
		{
			return COLOR_YELLOW; // Yellow
		}
		else
		{
			return COLOR_LOSS_RED; // Red
		}
	}

	/**
	 * Format GP amount for display
	 */
	private String formatGP(int amount)
	{
		int absAmount = Math.abs(amount);
		String sign = amount < 0 ? "-" : "";
		
		if (absAmount >= 1_000_000)
		{
			return String.format("%s%.1fM", sign, absAmount / 1_000_000.0);
		}
		else if (absAmount >= 1_000)
		{
			return String.format("%s%.1fK", sign, absAmount / 1_000.0);
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
	 * Holder for header panels (needed for hover effects)
	 */
	private static class HeaderPanels
	{
		final JPanel topPanel;
		final JPanel namePanel;

		HeaderPanels(JPanel topPanel, JPanel namePanel)
		{
			this.topPanel = topPanel;
			this.namePanel = namePanel;
		}
	}

	/**
	 * Create the item header panel with icon and name
	 */
	private HeaderPanels createItemHeaderPanels(int itemId, String itemName, Color bgColor)
	{
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(bgColor);

		JPanel namePanel = new JPanel(new BorderLayout(5, 0));
		namePanel.setBackground(bgColor);

		// Get item image
		AsyncBufferedImage itemImage = itemManager.getImage(itemId);
		JLabel iconLabel = new JLabel();
		setupIconLabel(iconLabel, itemImage);

		JLabel nameLabel = new JLabel(itemName);
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(FONT_BOLD_13);

		namePanel.add(iconLabel, BorderLayout.WEST);
		namePanel.add(nameLabel, BorderLayout.CENTER);
		topPanel.add(namePanel, BorderLayout.CENTER);

		return new HeaderPanels(topPanel, namePanel);
	}

	/**
	 * Format liquidity text for display
	 */
	private String formatLiquidityText(Double score, String rating, Double volumePerHour)
	{
		if (score == null)
		{
			return LIQUIDITY_NA;
		}
		String displayRating = rating != null ? rating : UNKNOWN_RATING;
		String volText = volumePerHour != null ? formatGP(volumePerHour.intValue()) + "/hr" : "";
		return String.format(FORMAT_LIQUIDITY, score, displayRating, volText);
	}

	/**
	 * Format risk text for display
	 */
	private String formatRiskText(Double score, String rating)
	{
		if (score == null)
		{
			return RISK_NA;
		}
		String displayRating = rating != null ? rating : UNKNOWN_RATING;
		return String.format(FORMAT_RISK, score, displayRating);
	}

	/**
	 * Format margin text with ROI for display
	 */
	private String formatMarginText(int marginPerItem, double roi, boolean isLoss)
	{
		String marginText = Math.abs(marginPerItem) >= 1000 
			? formatGP(marginPerItem) 
			: formatGPExact(marginPerItem);
		
		if (isLoss)
		{
			return String.format(FORMAT_MARGIN_ROI_LOSS, marginText, roi);
		}
		return String.format(FORMAT_MARGIN_ROI, marginText, roi);
	}

	/**
	 * Format profit and cost text for display
	 */
	private String formatProfitCostText(int totalProfit, int totalCost)
	{
		String profitText = Math.abs(totalProfit) >= 1000 
			? formatGP(totalProfit) 
			: formatGPExact(totalProfit);
		return String.format(FORMAT_PROFIT_COST, profitText, formatGP(totalCost));
	}

	/**
	 * Format buy/sell prices text for display
	 */
	private String formatBuySellText(int buyPrice, Integer sellPrice)
	{
		String sellText = sellPrice != null && sellPrice > 0 
			? formatGPExact(sellPrice) 
			: "N/A";
		return String.format(FORMAT_BUY_SELL, formatGPExact(buyPrice), sellText);
	}

	/**
	 * Create a styled JLabel with common settings for detail rows
	 */
	private JLabel createStyledLabel(String text, Color foreground)
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
	private JPanel createDetailsPanel(Color bgColor)
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
	private void addLabelsWithSpacing(JPanel panel, JLabel... labels)
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
	private void setPanelBackgrounds(Color color, JPanel... panels)
	{
		for (JPanel panel : panels)
		{
			panel.setBackground(color);
		}
	}

	/**
	 * Create a base panel with common settings for flip/recommendation items
	 */
	private JPanel createBaseItemPanel(Color bgColor, int maxHeight, boolean handCursor)
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
	 * Update liquidity label with data from analysis
	 */
	private void updateLiquidityLabel(JLabel label, FlipAnalysis.Liquidity liquidity)
	{
		label.setText(liquidity != null 
			? formatLiquidityText(liquidity.getScore(), liquidity.getRating(), liquidity.getTotalVolumePerHour())
			: LIQUIDITY_NA);
	}

	/**
	 * Update risk label with data from analysis
	 */
	private void updateRiskLabel(JLabel label, FlipAnalysis.Risk risk)
	{
		if (risk != null && risk.getScore() != null)
		{
			label.setText(formatRiskText(risk.getScore(), risk.getRating()));
			label.setForeground(getRiskColor(risk.getScore()));
		}
		else
		{
			label.setText(RISK_NA);
		}
	}

	/**
	 * Setup icon label with async image loading
	 */
	private void setupIconLabel(JLabel iconLabel, AsyncBufferedImage itemImage)
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
			log.debug("Clearing Flip Assist focus - item {} no longer in recommendations or active flips", currentFocusedItemId);
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
	 * Set a recommendation as the current focus for Flip Assist
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
	 * Set an active flip as the current focus for Flip Assist.
	 * Fetches current market data to determine the smart sell price.
	 */
	private void setFocus(ActiveFlip flip, JPanel panel)
	{
		// Fetch current market data to calculate smart sell price
		apiClient.getItemAnalysisAsync(flip.getItemId()).thenAccept(analysis ->
		{
			Integer currentMarketPrice = null;
			Integer dailyVolume = null;
			
			if (analysis != null && analysis.getCurrentPrices() != null)
			{
				FlipAnalysis.CurrentPrices prices = analysis.getCurrentPrices();
				currentMarketPrice = prices.getHigh();
				
				if (analysis.getLiquidity() != null && analysis.getLiquidity().getTotalVolumePerHour() != null)
				{
					dailyVolume = (int)(analysis.getLiquidity().getTotalVolumePerHour() * 24);
				}
			}
			
			// Calculate smart sell price
			Integer smartSellPrice = calculateSmartSellPrice(flip, currentMarketPrice, dailyVolume);
			int sellPrice = smartSellPrice != null ? smartSellPrice : calculateMinProfitableSellPrice(flip.getAverageBuyPrice());
			
			// Create focused flip for selling
			FocusedFlip newFocus = FocusedFlip.forSell(
				flip.getItemId(),
				flip.getItemName(),
				sellPrice,
				flip.getTotalQuantity()
			);
			
			SwingUtilities.invokeLater(() -> updateFocus(newFocus, panel));
		});
	}
	
	/**
	 * Set a pending order as the current focus for Flip Assist (selling step)
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
			try
			{
				onFocusChanged.accept(newFocus);
			}
			catch (Exception e)
			{
				log.warn("Error in onFocusChanged callback", e);
			}
		}
		
		log.info("Set Flip Assist focus: {} - {} at {} gp x{}", 
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
	 * Set focus from external source (e.g., auto-focus when setting up a sell offer).
	 * This sets the logical focus without highlighting a specific panel.
	 */
	public void setExternalFocus(FocusedFlip focus)
	{
		if (focus == null)
		{
			clearFocus();
			return;
		}
		
		// Clear any existing panel highlight
		if (currentFocusedPanel != null)
		{
			resetPanelStyle(currentFocusedPanel);
			currentFocusedPanel = null;
		}
		
		// Set the focus state
		currentFocus = focus;
		currentFocusedItemId = focus.getItemId();
		
		// Note: We don't have a panel to highlight since this was set externally
		// The Flip Assist overlay will show the focused item info
		
		log.debug("External focus set: {} - {} at {} gp", 
			focus.isBuying() ? "BUY" : "SELL",
			focus.getItemName(),
			focus.getCurrentStepPrice());
	}

	/**
	 * Create a panel for an active flip with current market data
	 */
	private JPanel createActiveFlipPanel(ActiveFlip flip)
	{
		JPanel panel = createBaseItemPanel(ColorScheme.DARKER_GRAY_COLOR, 180, true);

		// Top section: Item icon and name
		HeaderPanels header = createItemHeaderPanels(flip.getItemId(), flip.getItemName(), ColorScheme.DARKER_GRAY_COLOR);
		JPanel topPanel = header.topPanel;
		JPanel namePanel = header.namePanel;

		// Details section using BoxLayout for vertical rows
		JPanel detailsPanel = createDetailsPanel(ColorScheme.DARKER_GRAY_COLOR);

		// Row 1: Buy: X | Sell: Y (placeholders until data loads)
		JLabel pricesLabel = createStyledLabel(
			String.format("Buy: %s | Sell: ...", formatGPExact(flip.getAverageBuyPrice())), Color.WHITE);

		// Row 2: Qty: X (Limit: Y)
		JLabel qtyLabel = createStyledLabel(
			String.format("Qty: %d (Limit: ...)", flip.getTotalQuantity()), COLOR_TEXT_GRAY);

		// Row 3: Tax = Z
		JLabel taxLabel = createStyledLabel("Tax = ...", Color.CYAN);

		// Row 4: Margin: X (Y% ROI)
		JLabel marginLabel = createStyledLabel("Margin: ...", COLOR_YELLOW);

		// Row 5: Profit: X | Cost: Y
		JLabel profitCostLabel = createStyledLabel(
			String.format("Profit: ... | Cost: %s", formatGP(flip.getTotalInvested())), COLOR_PROFIT_GREEN);

		// Row 6: Liquidity: X (Rating) | Y/hr
		JLabel liquidityLabel = createStyledLabel("Liquidity: ...", Color.CYAN);

		// Row 7: Risk: X (Rating)
		JLabel riskLabel = createStyledLabel("Risk: ...", COLOR_YELLOW);

		// Add all rows with small spacing
		addLabelsWithSpacing(detailsPanel, pricesLabel, qtyLabel, taxLabel, marginLabel, 
			profitCostLabel, liquidityLabel, riskLabel);

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		// Fetch current market data to populate all fields
		apiClient.getItemAnalysisAsync(flip.getItemId()).thenAccept(analysis ->
		{
			SwingUtilities.invokeLater(() ->
			{
				Integer currentMarketPrice = null;
				Integer dailyVolume = null;
				Integer buyLimit = null;
				FlipAnalysis.Liquidity liquidity = null;
				FlipAnalysis.Risk risk = null;
				
				if (analysis != null)
				{
					buyLimit = analysis.getBuyLimit();
					liquidity = analysis.getLiquidity();
					risk = analysis.getRisk();
					
					if (analysis.getCurrentPrices() != null)
					{
						FlipAnalysis.CurrentPrices prices = analysis.getCurrentPrices();
						currentMarketPrice = prices.getHigh();
					}
					
					// Get daily volume from liquidity info
					if (liquidity != null && liquidity.getTotalVolumePerHour() != null)
					{
						dailyVolume = (int)(liquidity.getTotalVolumePerHour() * 24);
					}
				}
				
				// Calculate smart sell price based on time, volume, and profitability
				Integer smartSellPrice = calculateSmartSellPrice(flip, currentMarketPrice, dailyVolume);
				boolean pastThreshold = shouldUseLossMinimizingPrice(flip, dailyVolume);
				
				if (smartSellPrice != null && smartSellPrice > 0)
				{
					// Row 1: Update Buy | Sell prices
					String priceSuffix = pastThreshold ? "*" : "";
					pricesLabel.setText(String.format("Buy: %s | Sell: %s%s", 
						formatGPExact(flip.getAverageBuyPrice()),
						formatGPExact(smartSellPrice),
						priceSuffix));

					// Calculate GE tax (2% capped at 5M)
					int geTax = Math.min((int)(smartSellPrice * 0.02), 5_000_000);
					
					// Calculate margin and profit
					int marginPerItem = smartSellPrice - flip.getAverageBuyPrice() - geTax;
					int totalProfit = marginPerItem * flip.getTotalQuantity();
					double roi = (marginPerItem * 100.0) / flip.getAverageBuyPrice();
					
					// Row 2: Update Qty (Limit) | Tax
					String limitText = buyLimit != null ? String.valueOf(buyLimit) : "?";
					qtyLabel.setText(String.format("Qty: %d (Limit: %s)", flip.getTotalQuantity(), limitText));
					taxLabel.setText(String.format("Tax = %s", formatGP(geTax * flip.getTotalQuantity())));
					
					// Row 3: Update Margin with ROI (show warning color if not profitable)
					marginLabel.setText(formatMarginText(marginPerItem, roi, totalProfit <= 0));
					marginLabel.setForeground(totalProfit <= 0 ? COLOR_LOSS_RED : COLOR_YELLOW);
					
					// Row 4: Update Profit | Cost
					profitCostLabel.setText(formatProfitCostText(totalProfit, flip.getTotalInvested()));
					profitCostLabel.setForeground(totalProfit > 0 ? COLOR_PROFIT_GREEN : COLOR_LOSS_RED);
					
					// Show warning tooltip if past threshold and losing money
					if (pastThreshold && totalProfit < 0)
					{
						panel.setToolTipText("Price adjusted to minimize loss. Original recommended price was not achievable.");
					}
				}
				else
				{
					pricesLabel.setText(formatBuySellText(flip.getAverageBuyPrice(), null));
					marginLabel.setText("Margin: N/A");
					profitCostLabel.setText(String.format("Profit: N/A | Cost: %s", formatGP(flip.getTotalInvested())));
				}
				
				// Row 5: Update Liquidity
				updateLiquidityLabel(liquidityLabel, liquidity);
				
				// Row 6: Update Risk
				updateRiskLabel(riskLabel, risk);
			});
		});

		// Add hover effect, click to focus, and context menu
		panel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Left click: set as Flip Assist focus for selling
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
					setPanelBackgrounds(ColorScheme.DARKER_GRAY_HOVER_COLOR, panel, topPanel, namePanel, detailsPanel);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != flip.getItemId() 
					|| !currentFocus.isSelling())
				{
					setPanelBackgrounds(ColorScheme.DARKER_GRAY_COLOR, panel, topPanel, namePanel, detailsPanel);
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
					JMenuItem focusItem = new JMenuItem("Set as Flip Assist Focus (Sell)");
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
	 * Uses same detailed layout as active flips with Liquidity/Risk data
	 */
	private JPanel createPendingOrderPanel(FlipSmartPlugin.PendingOrder pending)
	{
		Color bgColor = new Color(55, 55, 65); // Slightly different color for pending
		JPanel panel = createBaseItemPanel(bgColor, 180, false);

		// Top section: Item icon and name
		HeaderPanels header = createItemHeaderPanels(pending.itemId, pending.itemName, bgColor);
		JPanel topPanel = header.topPanel;
		JPanel namePanel = header.namePanel;

		// Details section using BoxLayout for vertical rows
		JPanel detailsPanel = createDetailsPanel(bgColor);

		// Row 1: Buy: X | Sell: Y (with placeholders until data loads)
		String sellText = pending.recommendedSellPrice != null && pending.recommendedSellPrice > 0
			? formatGPExact(pending.recommendedSellPrice) : "...";
		JLabel pricesLabel = createStyledLabel(
			String.format(FORMAT_BUY_SELL, formatGPExact(pending.pricePerItem), sellText), Color.WHITE);

		// Row 2: Qty: X/Y (Limit: Z)
		JLabel qtyLabel = createStyledLabel(
			String.format("Qty: %d/%d (Limit: ...)", pending.quantityFilled, pending.quantity), COLOR_TEXT_GRAY);

		// Row 3: Tax = W
		JLabel taxLabel = createStyledLabel("Tax = ...", Color.CYAN);

		// Row 4: Margin: X (Y% ROI)
		JLabel marginLabel = createStyledLabel("Margin: ...", COLOR_YELLOW);

		// Row 5: Profit: X | Cost: Y
		int potentialInvestment = pending.quantity * pending.pricePerItem;
		JLabel profitCostLabel = createStyledLabel(
			String.format("Profit: ... | Cost: %s", formatGP(potentialInvestment)), COLOR_PROFIT_GREEN);

		// Row 6: Liquidity: X (Rating) | Y/hr
		JLabel liquidityLabel = createStyledLabel("Liquidity: ...", Color.CYAN);

		// Row 7: Risk: X (Rating)
		JLabel riskLabel = createStyledLabel("Risk: ...", COLOR_YELLOW);

		// Add all rows with small spacing
		addLabelsWithSpacing(detailsPanel, pricesLabel, qtyLabel, taxLabel, marginLabel, 
			profitCostLabel, liquidityLabel, riskLabel);

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		// Fetch market data to populate all fields
		apiClient.getItemAnalysisAsync(pending.itemId).thenAccept(analysis ->
		{
			SwingUtilities.invokeLater(() ->
			{
				Integer buyLimit = null;
				FlipAnalysis.Liquidity liquidity = null;
				FlipAnalysis.Risk risk = null;
				Integer currentSellPrice = null;
				
				if (analysis != null)
				{
					buyLimit = analysis.getBuyLimit();
					liquidity = analysis.getLiquidity();
					risk = analysis.getRisk();
					
					if (analysis.getCurrentPrices() != null)
					{
						currentSellPrice = analysis.getCurrentPrices().getHigh();
					}
				}
				
				// Use recommended sell price or current market price
				Integer sellPrice = pending.recommendedSellPrice != null && pending.recommendedSellPrice > 0
					? pending.recommendedSellPrice : currentSellPrice;
				
				if (sellPrice != null && sellPrice > 0)
				{
					// Row 1: Update prices
					pricesLabel.setText(formatBuySellText(pending.pricePerItem, sellPrice));

					// Calculate GE tax (2% capped at 5M)
					int geTax = Math.min((int)(sellPrice * 0.02), 5_000_000);
					
					// Calculate margin and profit
					int marginPerItem = sellPrice - pending.pricePerItem - geTax;
					int totalProfit = marginPerItem * pending.quantity;
					double roi = (marginPerItem * 100.0) / pending.pricePerItem;
					
					// Row 2: Update Qty (Limit) | Tax
					String limitText = buyLimit != null ? String.valueOf(buyLimit) : "?";
					qtyLabel.setText(String.format("Qty: %d/%d (Limit: %s)", 
						pending.quantityFilled, pending.quantity, limitText));
					taxLabel.setText(String.format("Tax = %s", formatGP(geTax * pending.quantity)));
					
					// Row 3: Update Margin
					marginLabel.setText(formatMarginText(marginPerItem, roi, totalProfit <= 0));
					marginLabel.setForeground(totalProfit <= 0 ? COLOR_LOSS_RED : COLOR_YELLOW);
					
					// Row 4: Update Profit | Cost
					profitCostLabel.setText(formatProfitCostText(totalProfit, potentialInvestment));
					profitCostLabel.setForeground(totalProfit > 0 ? COLOR_PROFIT_GREEN : COLOR_LOSS_RED);
				}
				else
				{
					pricesLabel.setText(formatBuySellText(pending.pricePerItem, null));
					marginLabel.setText("Margin: N/A");
					profitCostLabel.setText(String.format("Profit: N/A | Cost: %s", formatGP(potentialInvestment)));
				}
				
				// Row 5: Update Liquidity
				updateLiquidityLabel(liquidityLabel, liquidity);
				
				// Row 6: Update Risk
				updateRiskLabel(riskLabel, risk);
			});
		});

		// Add click listener for focus selection
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
					setPanelBackgrounds(new Color(65, 65, 75), panel, topPanel, namePanel, detailsPanel);
				}
			}
			
			@Override
			public void mouseExited(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != pending.itemId)
				{
					setPanelBackgrounds(bgColor, panel, topPanel, namePanel, detailsPanel);
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
		// Color based on profit/loss
		Color backgroundColor = flip.isSuccessful() ? 
			new Color(40, 60, 40) : // Dark green for profit
			new Color(60, 40, 40);  // Dark red for loss
		JPanel panel = createBaseItemPanel(backgroundColor, 110, false);

		// Top section: Item icon and name
		HeaderPanels header = createItemHeaderPanels(flip.getItemId(), flip.getItemName(), backgroundColor);
		JPanel topPanel = header.topPanel;

		// Details section with profit/loss info - use GridBagLayout for tighter column spacing
		JPanel detailsPanel = new JPanel(new GridBagLayout());
		detailsPanel.setBackground(backgroundColor);
		detailsPanel.setBorder(new EmptyBorder(3, 0, 0, 0));
		
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(1, 0, 1, 15); // 15px gap between columns

		// Row 1: Quantity and Buy Price
		JLabel qtyLabel = new JLabel(String.format(FORMAT_QTY, flip.getQuantity()));
		qtyLabel.setForeground(COLOR_TEXT_GRAY);
		qtyLabel.setFont(FONT_PLAIN_12);

		JLabel buyPriceLabel = new JLabel(String.format("Buy: %s", formatGPExact(flip.getBuyPricePerItem())));
		buyPriceLabel.setForeground(COLOR_BUY_RED);
		buyPriceLabel.setFont(FONT_PLAIN_12);

		// Row 2: Invested and Sell Price
		JLabel investedLabel = new JLabel(String.format("Cost: %s", formatGP(flip.getBuyTotal())));
		investedLabel.setForeground(COLOR_TEXT_GRAY);
		investedLabel.setFont(FONT_PLAIN_12);

		JLabel sellPriceLabel = new JLabel(String.format(FORMAT_SELL, formatGPExact(flip.getSellPricePerItem())));
		sellPriceLabel.setForeground(COLOR_SELL_GREEN);
		sellPriceLabel.setFont(FONT_PLAIN_12);

		// Row 3: Net Profit and ROI
		JLabel profitLabel = new JLabel(String.format("Profit: %s", formatGP(flip.getNetProfit())));
		Color profitColor = flip.isSuccessful() ? 
			COLOR_PROFIT_GREEN : // Bright green
			COLOR_LOSS_RED;  // Bright red
		profitLabel.setForeground(profitColor);
		profitLabel.setFont(FONT_BOLD_12);

		JLabel roiLabel = new JLabel(String.format(FORMAT_ROI, flip.getRoiPercent()));
		roiLabel.setForeground(profitColor);
		roiLabel.setFont(FONT_BOLD_12);

		// Add labels with GridBagLayout - columns stay compact
		gbc.gridx = 0; gbc.gridy = 0; detailsPanel.add(qtyLabel, gbc);
		gbc.gridx = 1; gbc.gridy = 0; gbc.insets = new Insets(1, 0, 1, 0); detailsPanel.add(buyPriceLabel, gbc);
		gbc.gridx = 0; gbc.gridy = 1; gbc.insets = new Insets(1, 0, 1, 15); detailsPanel.add(investedLabel, gbc);
		gbc.gridx = 1; gbc.gridy = 1; gbc.insets = new Insets(1, 0, 1, 0); detailsPanel.add(sellPriceLabel, gbc);
		gbc.gridx = 0; gbc.gridy = 2; gbc.insets = new Insets(1, 0, 1, 15); detailsPanel.add(profitLabel, gbc);
		gbc.gridx = 1; gbc.gridy = 2; gbc.insets = new Insets(1, 0, 1, 0); detailsPanel.add(roiLabel, gbc);
		
		// Add horizontal glue to prevent stretching
		gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
		detailsPanel.add(Box.createHorizontalGlue(), gbc);

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
					durationLabel.setForeground(COLOR_TEXT_DIM_GRAY);
					durationLabel.setFont(FONT_PLAIN_12);

					// GE Tax
					JLabel taxLabel = new JLabel(String.format("GE Tax: %s", formatGP(flip.getGeTax())));
					taxLabel.setForeground(COLOR_TEXT_DIM_GRAY);
					taxLabel.setFont(FONT_PLAIN_12);

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
	
	/**
	 * Calculate the sell price threshold time for an active flip.
	 * Returns the number of minutes after which we should switch from 
	 * profit-first to loss-minimizing strategy.
	 * 
	 * Rules:
	 * - High volume items (>500k daily): 10 minutes
	 * - High value items (>250M buy price): 30 minutes
	 * - Regular items: 20 minutes
	 */
	private int getSellPriceThresholdMinutes(ActiveFlip flip, Integer dailyVolume)
	{
		// High value items get more time
		if (flip.getAverageBuyPrice() >= HIGH_VALUE_THRESHOLD)
		{
			return HIGH_VALUE_TIME_MINUTES;
		}
		
		// High volume items should sell quickly
		if (dailyVolume != null && dailyVolume >= HIGH_VOLUME_THRESHOLD)
		{
			return HIGH_VOLUME_TIME_MINUTES;
		}
		
		// Regular items
		return REGULAR_TIME_MINUTES;
	}
	
	/**
	 * Check if an active flip has exceeded its time threshold and should
	 * switch to loss-minimizing sell price.
	 */
	private boolean shouldUseLossMinimizingPrice(ActiveFlip flip, Integer dailyVolume)
	{
		String buyTimeStr = flip.getLastBuyTime();
		if (buyTimeStr == null || buyTimeStr.isEmpty())
		{
			return false;
		}
		
		try
		{
			Instant buyTime = Instant.parse(buyTimeStr);
			Duration elapsed = Duration.between(buyTime, Instant.now());
			int thresholdMinutes = getSellPriceThresholdMinutes(flip, dailyVolume);
			return elapsed.toMinutes() >= thresholdMinutes;
		}
		catch (DateTimeParseException e)
		{
			log.debug("Failed to parse buy time: {}", buyTimeStr);
			return false;
		}
	}
	
	/**
	 * Calculate the minimum profitable sell price for an active flip.
	 * This is the price that would result in zero profit after tax.
	 * Formula: minSellPrice = buyPrice / (1 - taxRate)
	 * Adding 1gp ensures a small profit.
	 */
	private int calculateMinProfitableSellPrice(int buyPrice)
	{
		// GE tax is 2%, so to break even: sellPrice * 0.98 = buyPrice
		// sellPrice = buyPrice / 0.98
		// Add 1gp to ensure profit
		return (int) Math.ceil(buyPrice / 0.98) + 1;
	}
	
	/**
	 * Determine the smart sell price for an active flip.
	 * 
	 * Strategy:
	 * 1. First try to sell at the recommended price (profitable)
	 * 2. If no recommended price, calculate minimum profitable price
	 * 3. After time threshold, switch to current market price to minimize loss
	 * 
	 * @param flip The active flip
	 * @param currentMarketPrice The current instant sell price from market
	 * @param dailyVolume Daily trade volume (optional)
	 * @return The recommended sell price, or null to indicate need to fetch market price
	 */
	private Integer calculateSmartSellPrice(ActiveFlip flip, Integer currentMarketPrice, Integer dailyVolume)
	{
		int buyPrice = flip.getAverageBuyPrice();
		int minProfitablePrice = calculateMinProfitableSellPrice(buyPrice);
		
		// Check if we've exceeded the time threshold
		boolean pastThreshold = shouldUseLossMinimizingPrice(flip, dailyVolume);
		
		if (pastThreshold && currentMarketPrice != null)
		{
			// Past threshold: prioritize selling, even at potential loss
			// Use current market price, but at minimum use the market price
			// that gives best chance of selling
			return currentMarketPrice;
		}
		
		// Before threshold: prioritize profit
		if (flip.getRecommendedSellPrice() != null && flip.getRecommendedSellPrice() >= minProfitablePrice)
		{
			// Use recommended price if it's profitable
			return flip.getRecommendedSellPrice();
		}
		
		// No recommended price or it's not profitable - use minimum profitable price
		// but only if market price is higher (otherwise the flip was never good)
		if (currentMarketPrice != null && currentMarketPrice >= minProfitablePrice)
		{
			return minProfitablePrice;
		}
		
		// Market price is below profitable threshold
		// Before time threshold: still try to sell at profitable price
		// After threshold: this would be handled above
		if (flip.getRecommendedSellPrice() != null)
		{
			return flip.getRecommendedSellPrice();
		}
		
		return minProfitablePrice;
	}
}

