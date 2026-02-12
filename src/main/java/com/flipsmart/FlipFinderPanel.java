package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
public class FlipFinderPanel extends PluginPanel
{
	// Configuration constants
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String CONFIG_KEY_FLIP_STYLE = "flipStyle";
	private static final String CONFIG_KEY_FLIP_TIMEFRAME = "flipTimeframe";
	private static final String CONFIG_KEY_EMAIL = "email";
	private static final String CONFIG_KEY_PASSWORD = "password";  // Deprecated: kept for migration
	private static final String CONFIG_KEY_REFRESH_TOKEN = "refreshToken";
	
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
	private static final String ERROR_DIALOG_TITLE = "Error";
	private static final String UNKNOWN_RATING = "Unknown";
	private static final String LIQUIDITY_NA = "Liquidity: N/A";
	private static final String RISK_NA = "Risk: N/A";
	private static final String MSG_LOGIN_TO_RUNESCAPE = "Log in to RuneScape";
	private static final String MSG_LOGIN_INSTRUCTION = "<html><center>Log in to the game to get<br>flip suggestions and track your flips</center></html>";
	
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
	
	// Pastel background colors for active flips (price comparison indicator)
	// Very light/transparent so they don't conflict with text colors
	private static final Color COLOR_PRICE_HIGHER_BG = new Color(60, 90, 60);  // Subtle green - selling higher than estimate
	private static final Color COLOR_PRICE_LOWER_BG = new Color(90, 55, 55);   // Subtle red - selling lower than estimate

	// Colors for auto-recommend highlighted item
	private static final Color COLOR_AUTO_RECOMMEND_BORDER = new Color(255, 185, 50);
	private static final Color COLOR_AUTO_RECOMMEND_BG = new Color(70, 55, 20);
	
	// Website base URL for item pages
	private static final String WEBSITE_ITEM_URL = "https://flipsmart.net/items/";

	// Premium subscription link (update when dashboard URL is available)
	private static final String SUBSCRIBE_LINK = "https://flipsmart.net/dashboard";
	private static final String SUBSCRIBE_MESSAGE = "Subscribe to Premium for all suggestions";
	
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
	private final JComboBox<FlipSmartConfig.FlipTimeframe> flipTimeframeDropdown;
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
	private JButton discordButton;

	// Premium subscribe message (shown for non-premium users)
	private JLabel subscribeLabel;
	private boolean isAuthenticated = false;
	
	// Discord device auth polling
	private ScheduledExecutorService deviceAuthScheduler;
	private ScheduledFuture<?> deviceAuthPollTask;
	private volatile String currentDeviceCode;
	
	// Callback for when authentication completes (to sync RSN)
	private transient Runnable onAuthSuccess;
	
	// Flip Assist focus tracking
	private transient FocusedFlip currentFocus = null;
	private transient JPanel currentFocusedPanel = null;
	private transient int currentFocusedItemId = -1;
	private transient java.util.function.Consumer<FocusedFlip> onFocusChanged;
	
	// Cache displayed sell prices to ensure focus uses same price as shown in UI
	// Key: itemId, Value: calculated sell price shown in the active flip panel
	private final java.util.Map<Integer, Integer> displayedSellPrices = new java.util.concurrent.ConcurrentHashMap<>();

	// Auto-recommend UI
	private JToggleButton autoRecommendButton;
	private JLabel autoRecommendStatusLabel;

	// Cache blocklists for quick access when blocking items
	private final java.util.concurrent.CopyOnWriteArrayList<BlocklistSummary> cachedBlocklists = new java.util.concurrent.CopyOnWriteArrayList<>();
	private volatile long blocklistCacheTimestamp = 0;
	private static final long BLOCKLIST_CACHE_TTL_MS = 5L * 60 * 1000; // 5 minutes

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
		// Load saved flip style from config
		flipStyleDropdown.setSelectedItem(config.flipStyle());
		flipStyleDropdown.addActionListener(e -> {
			// Save selection to config
			FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
			if (selectedStyle != null)
			{
				configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_FLIP_STYLE, selectedStyle);
			}
			// Refresh recommendations when flip style changes
			if (isAuthenticated)
			{
				refresh();
			}
		});

		// Initialize flip timeframe dropdown
		flipTimeframeDropdown = new JComboBox<>(FlipSmartConfig.FlipTimeframe.values());
		flipTimeframeDropdown.setFocusable(false);
		flipTimeframeDropdown.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		flipTimeframeDropdown.setForeground(Color.WHITE);
		// Load saved flip timeframe from config
		flipTimeframeDropdown.setSelectedItem(config.flipTimeframe());
		flipTimeframeDropdown.addActionListener(e -> {
			// Save selection to config
			FlipSmartConfig.FlipTimeframe selectedTimeframe = (FlipSmartConfig.FlipTimeframe) flipTimeframeDropdown.getSelectedItem();
			if (selectedTimeframe != null)
			{
				configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_FLIP_TIMEFRAME, selectedTimeframe);
			}
			// Refresh recommendations when timeframe changes
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

		// Set up auth failure callback to redirect to login screen
		apiClient.setOnAuthFailure(() -> SwingUtilities.invokeLater(() -> {
			// Pre-fill email if available
			String email = config.email();
			if (email != null && !email.isEmpty())
			{
				emailField.setText(email);
			}
			loginStatusLabel.setText("Session expired. Please login again.");
			loginStatusLabel.setForeground(new Color(255, 200, 100)); // Orange warning color
			showLoginPanel();
		}));

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

		// Buttons panel for Login/Sign Up
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
		
		// Divider
		JPanel dividerPanel = new JPanel(new BorderLayout());
		dividerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		dividerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		
		JLabel orLabel = new JLabel("OR", SwingConstants.CENTER);
		orLabel.setForeground(Color.GRAY);
		orLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));
		dividerPanel.add(orLabel, BorderLayout.CENTER);
		
		// Discord login button (with Discord purple color)
		discordButton = new JButton("Login with Discord");
		discordButton.setBackground(new Color(88, 101, 242)); // Discord blurple
		discordButton.setForeground(Color.WHITE);
		discordButton.setFocusPainted(false);
		discordButton.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
		discordButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
		discordButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		discordButton.setAlignmentX(Component.CENTER_ALIGNMENT);
		discordButton.addActionListener(e -> handleDiscordLogin());

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
		contentPanel.add(dividerPanel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		contentPanel.add(discordButton);
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

		// Header panel with even spacing between title and buttons
		JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JLabel titleLabel = new JLabel("Flip Finder");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(FONT_BOLD_16);

		// Logout button with compact styling
		JButton logoutButton = new JButton("Logout");
		logoutButton.setFocusable(false);
		logoutButton.setMargin(new Insets(2, 4, 2, 4));
		logoutButton.addActionListener(e -> handleLogout());

		// Refresh button with compact styling
		refreshButton.setFocusable(false);
		refreshButton.setMargin(new Insets(2, 4, 2, 4));
		refreshButton.addActionListener(e -> refresh(true));

		headerPanel.add(titleLabel);
		headerPanel.add(logoutButton);
		headerPanel.add(refreshButton);

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

		// Custom renderer for timeframe dropdown
		flipTimeframeDropdown.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
														  boolean isSelected, boolean cellHasFocus) {
				Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (c instanceof JLabel && value instanceof FlipSmartConfig.FlipTimeframe) {
					FlipSmartConfig.FlipTimeframe timeframe = (FlipSmartConfig.FlipTimeframe) value;
					((JLabel) c).setText(timeframe.toString());
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

		JLabel flipTimeframeLabel = new JLabel("Timeframe: ");
		flipTimeframeLabel.setForeground(Color.LIGHT_GRAY);
		flipTimeframeLabel.setFont(FONT_PLAIN_12);

		// Row 1: Style dropdown
		JPanel styleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		styleRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		styleRow.add(flipStyleLabel);
		styleRow.add(flipStyleDropdown);

		// Auto-recommend toggle button
		autoRecommendButton = new JToggleButton("Auto");
		autoRecommendButton.setFocusable(false);
		autoRecommendButton.setMargin(new Insets(2, 8, 2, 8));
		autoRecommendButton.setToolTipText("Auto-cycle through recommendations into Flip Assist");
		autoRecommendButton.addActionListener(e -> toggleAutoRecommend());

		// Row 2: Timeframe dropdown (left) + Auto button (right)
		JPanel timeframeRow = new JPanel(new BorderLayout());
		timeframeRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JPanel timeframeLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		timeframeLeft.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		timeframeLeft.add(flipTimeframeLabel);
		timeframeLeft.add(flipTimeframeDropdown);

		JPanel timeframeRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
		timeframeRight.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		timeframeRight.add(autoRecommendButton);

		timeframeRow.add(timeframeLeft, BorderLayout.WEST);
		timeframeRow.add(timeframeRight, BorderLayout.EAST);

		// Stack rows vertically
		JPanel dropdownWrapper = new JPanel();
		dropdownWrapper.setLayout(new BoxLayout(dropdownWrapper, BoxLayout.Y_AXIS));
		dropdownWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		dropdownWrapper.add(styleRow);
		dropdownWrapper.add(timeframeRow);

		controlsPanel.add(dropdownWrapper, BorderLayout.WEST);

		// Status panel
		JPanel statusPanel = new JPanel(new BorderLayout());
		statusPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statusPanel.setBorder(new EmptyBorder(5, 10, 5, 10));
		statusLabel.setForeground(Color.LIGHT_GRAY);
		statusLabel.setFont(FONT_PLAIN_12);
		statusPanel.add(statusLabel, BorderLayout.CENTER);

		// Auto-recommend status label (hidden by default)
		autoRecommendStatusLabel = new JLabel();
		autoRecommendStatusLabel.setForeground(new Color(255, 200, 50));
		autoRecommendStatusLabel.setFont(FONT_PLAIN_12);
		autoRecommendStatusLabel.setVisible(false);
		statusPanel.add(autoRecommendStatusLabel, BorderLayout.SOUTH);

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

		// Footer panel with subscribe message and website link
		JPanel footerPanel = new JPanel();
		footerPanel.setLayout(new BoxLayout(footerPanel, BoxLayout.Y_AXIS));
		footerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		footerPanel.setBorder(new EmptyBorder(5, 0, 5, 0));

		// Subscribe message for non-premium users (hidden by default until we know premium status)
		subscribeLabel = new JLabel(SUBSCRIBE_MESSAGE);
		subscribeLabel.setForeground(new Color(255, 200, 100));
		subscribeLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 12));
		subscribeLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		subscribeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		subscribeLabel.setToolTipText("Click to subscribe to Premium");
		subscribeLabel.setVisible(false); // Hidden until we confirm non-premium status
		subscribeLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(SUBSCRIBE_LINK);
			}
		});

		JLabel websiteLink = new JLabel("Flip Smart Website");
		websiteLink.setForeground(new Color(100, 180, 255));
		websiteLink.setFont(new Font(FONT_ARIAL, Font.PLAIN, 14));
		websiteLink.setCursor(new Cursor(Cursor.HAND_CURSOR));
		websiteLink.setAlignmentX(Component.CENTER_ALIGNMENT);
		websiteLink.setToolTipText("Visit our website to view your flips and track your performance");
		websiteLink.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse("https://flipsmart.net");
			}
		});

		footerPanel.add(websiteLink);
		footerPanel.add(Box.createVerticalStrut(3));
		footerPanel.add(subscribeLabel);

		mainPanel.add(topPanel, BorderLayout.NORTH);
		mainPanel.add(tabbedPane, BorderLayout.CENTER);
		mainPanel.add(footerPanel, BorderLayout.SOUTH);
	}

	/**
	 * Check if already authenticated and show appropriate panel.
	 * Tries refresh token first (secure), falls back to legacy password if needed.
	 */
	private void checkAuthenticationAndShow()
	{
		// First, try to authenticate with refresh token (secure method)
		String refreshToken = config.refreshToken();
		String email = config.email();

		if (refreshToken != null && !refreshToken.isEmpty())
		{
			// Pre-fill email field if available
			if (email != null && !email.isEmpty())
			{
				emailField.setText(email);
			}

			// Load refresh token into API client and try to authenticate
			apiClient.setRefreshToken(refreshToken);

			java.util.concurrent.CompletableFuture.runAsync(() -> {
				// Try refresh token authentication
				try
				{
					Boolean success = apiClient.refreshAccessTokenAsync().get();
					SwingUtilities.invokeLater(() -> {
						if (Boolean.TRUE.equals(success))
						{
							// Save new refresh token (token rotation)
							saveRefreshToken(apiClient.getRefreshToken());
							onAuthenticationSuccess(null, false);
						}
						else
						{
							// Refresh token invalid/expired, clear it
							clearRefreshToken();
							// Fall back to legacy password auth
							tryLegacyPasswordAuth();
						}
					});
				}
				catch (InterruptedException e)
				{
					log.debug("Refresh token auth interrupted: {}", e.getMessage());
					SwingUtilities.invokeLater(this::tryLegacyPasswordAuth);
				}
				catch (Exception e)
				{
					log.debug("Refresh token auth failed: {}", e.getMessage());
					SwingUtilities.invokeLater(this::tryLegacyPasswordAuth);
				}
			});
		}
		else
		{
			// No refresh token, try legacy password auth
			tryLegacyPasswordAuth();
		}
	}

	/**
	 * Try to authenticate with legacy stored password (migration path).
	 * After successful login, the password will be cleared and replaced with refresh token.
	 */
	private void tryLegacyPasswordAuth()
	{
		String email = config.email();
		String password = config.password();

		// Always pre-fill email if available (helps users who need to re-login)
		if (email != null && !email.isEmpty())
		{
			emailField.setText(email);
		}

		if (email != null && !email.isEmpty() && password != null && !password.isEmpty())
		{
			// Try to authenticate in background
			java.util.concurrent.CompletableFuture.runAsync(() -> {
				FlipSmartApiClient.AuthResult result = apiClient.login(email, password);

				SwingUtilities.invokeLater(() -> {
					if (result.success)
					{
						// Migration: save refresh token and clear password
						saveRefreshToken(apiClient.getRefreshToken());
						clearPassword();
						onAuthenticationSuccess(null, false);
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
		else
		{
			// No stored credentials - show helpful message to user
			loginStatusLabel.setText("Please login to continue");
			loginStatusLabel.setForeground(Color.LIGHT_GRAY);
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
					// Save email and refresh token (NOT password) for next session
					saveEmail(email);
					saveRefreshToken(apiClient.getRefreshToken());
					// Clear any legacy password storage
					clearPassword();
					onAuthenticationSuccess(result.message, true);
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
					// Save email and refresh token (NOT password) for next session
					saveEmail(email);
					saveRefreshToken(apiClient.getRefreshToken());
					// Clear any legacy password storage
					clearPassword();
					onAuthenticationSuccess(result.message, true);
				}
				else
				{
					showLoginStatus(result.message, false);
				}
			});
		});
	}

	/**
	 * Save email for next session
	 */
	private void saveEmail(String email)
	{
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_EMAIL, email);
	}

	/**
	 * Save refresh token for persistent login (replaces password storage)
	 */
	private void saveRefreshToken(String refreshToken)
	{
		if (refreshToken != null && !refreshToken.isEmpty())
		{
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_REFRESH_TOKEN, refreshToken);
		}
	}

	/**
	 * Clear refresh token (on logout or revocation)
	 */
	private void clearRefreshToken()
	{
		configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_REFRESH_TOKEN);
	}

	/**
	 * Clear legacy password storage (migration cleanup)
	 */
	private void clearPassword()
	{
		configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_PASSWORD);
	}

	/**
	 * @deprecated Use saveEmail() and saveRefreshToken() instead
	 */
	@Deprecated
	private void saveCredentials(String email, String password)
	{
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_EMAIL, email);
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_PASSWORD, password);
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
		discordButton.setEnabled(enabled);
		emailField.setEnabled(enabled);
		passwordField.setEnabled(enabled);
	}
	
	/**
	 * Handle Discord login button click
	 */
	private void handleDiscordLogin()
	{
		setLoginButtonsEnabled(false);
		showLoginStatus("Starting Discord login...", true);
		
		// Start device auth flow
		apiClient.startDeviceAuthAsync().thenAccept(response ->
		{
			if (response == null)
			{
				SwingUtilities.invokeLater(() ->
				{
					setLoginButtonsEnabled(true);
					showLoginStatus("Failed to start Discord login", false);
				});
				return;
			}
			
			// Store device code for polling
			currentDeviceCode = response.getDeviceCode();
			
			// Open browser with verification URL using RuneLite's LinkBrowser
            // which properly handles sandboxed environments (Flatpak, etc.)
            LinkBrowser.browse(response.getVerificationUrl());

        	SwingUtilities.invokeLater(() ->
            	showLoginStatus("Complete login in your browser...", true));

		   // Start polling for completion
		   startDeviceAuthPolling(response.getDeviceCode(), response.getPollInterval(), response.getExpiresIn());
		});
	}
	
	/**
	 * Start polling for device authorization completion
	 */
	private void startDeviceAuthPolling(String deviceCode, int pollIntervalSeconds, int expiresInSeconds)
	{
		// Cancel any existing poll task
		stopDeviceAuthPolling();
		
		// Create scheduler if needed
		if (deviceAuthScheduler == null || deviceAuthScheduler.isShutdown())
		{
			deviceAuthScheduler = Executors.newSingleThreadScheduledExecutor();
		}
		
		// Calculate max poll attempts based on expiry time
		int maxAttempts = expiresInSeconds / pollIntervalSeconds;
		final int[] attempts = {0};
		
		deviceAuthPollTask = deviceAuthScheduler.scheduleAtFixedRate(() ->
		{
			attempts[0]++;
			
			// Check if we've exceeded max attempts
			if (attempts[0] > maxAttempts)
			{
				stopDeviceAuthPolling();
				SwingUtilities.invokeLater(() ->
				{
					setLoginButtonsEnabled(true);
					showLoginStatus("Discord login timed out", false);
				});
				return;
			}
			
			// Poll status
			apiClient.pollDeviceStatusAsync(deviceCode).thenAccept(status ->
			{
				if (status == null)
				{
					return; // Network error, will retry
				}
				
				switch (status.getStatus())
				{
					case "authorized":
						// Success! Set the token and switch to main panel
						stopDeviceAuthPolling();
						apiClient.setAuthToken(status.getAccessToken());
						// Save refresh token for session persistence across client restarts
						if (status.getRefreshToken() != null)
						{
							apiClient.setRefreshToken(status.getRefreshToken());
							saveRefreshToken(status.getRefreshToken());
						}
						SwingUtilities.invokeLater(() ->
							onAuthenticationSuccess("Login successful!", true));
						break;
						
					case "expired":
						// Device code expired
						stopDeviceAuthPolling();
						SwingUtilities.invokeLater(() ->
						{
							setLoginButtonsEnabled(true);
							showLoginStatus("Discord login expired. Try again.", false);
						});
						break;
						
					case "pending":
						// Still waiting - continue polling
						break;
						
					default:
						log.warn("Unknown device status: {}", status.getStatus());
						break;
				}
			});
		}, pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
	}
	
	/**
	 * Stop device auth polling
	 */
	private void stopDeviceAuthPolling()
	{
		if (deviceAuthPollTask != null)
		{
			deviceAuthPollTask.cancel(false);
			deviceAuthPollTask = null;
		}
		currentDeviceCode = null;
	}
	
	/**
	 * Clean up resources when panel is destroyed
	 */
	public void shutdown()
	{
		stopDeviceAuthPolling();
		if (deviceAuthScheduler != null)
		{
			deviceAuthScheduler.shutdownNow();
			deviceAuthScheduler = null;
		}
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
		// Stop any pending device auth polling
		stopDeviceAuthPolling();
		// Re-enable all login buttons
		setLoginButtonsEnabled(true);
		removeAll();
		add(loginPanel, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	/**
	 * Handle successful authentication - notify plugin and transition to main panel.
	 * @param successMessage message to display, or null to skip showing message
	 * @param showDelay if true, show message briefly before transitioning
	 */
	private void onAuthenticationSuccess(String successMessage, boolean showDelay)
	{
		if (onAuthSuccess != null)
		{
			onAuthSuccess.run();
		}
		if (showDelay && successMessage != null)
		{
			showLoginStatus(successMessage, true);
			Timer timer = new Timer(500, e -> showMainPanel());
			timer.setRepeats(false);
			timer.start();
		}
		else
		{
			showMainPanel();
		}
	}

	/**
	 * Handle logout button click
	 */
	private void handleLogout()
	{
		// Clear API client authentication (includes refresh token)
		apiClient.clearAuth();

		// Clear stored refresh token
		clearRefreshToken();

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
	 * Update the premium status display (subscribe label visibility).
	 * Called when entitlements are fetched on game login.
	 */
	public void updatePremiumStatus()
	{
		subscribeLabel.setVisible(!apiClient.isPremium());
	}

	/**
	 * Refresh flip recommendations, active flips, and completed flips.
	 *
	 * @param shuffleSuggestions if true, randomizes suggestions within quality tiers (for manual refresh)
	 */
	public void refresh(boolean shuffleSuggestions)
	{
		// Skip API calls if player is not logged into RuneScape
		// This saves API requests and battery when at the login screen
		if (!plugin.isLoggedIntoRunescape())
		{
			log.debug("Skipping refresh - player not logged into RuneScape");
			showLoggedOutOfGameState();
			return;
		}
		
		// Skip recommendations refresh during auto-refresh if user is focused on a flip
		// This prevents the focused item from disappearing while user is mid-transaction
		// Manual refresh (shuffleSuggestions=true) always refreshes
		boolean skipRecommendationsRefresh = !shuffleSuggestions && currentFocus != null;
		
		if (skipRecommendationsRefresh)
		{
			log.debug("Skipping recommendations refresh - user is focused on {} ({})", 
				currentFocus.getItemName(), currentFocus.isBuying() ? "BUY" : "SELL");
		}
		else
		{
			refreshRecommendations(shuffleSuggestions);
		}
		
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
		// Don't clear container yet - keep showing old recommendations until new data arrives
		// This prevents the UI flash when recommendations disappear and reappear

		// Fetch recommendations asynchronously
		Integer cashStack = getCashStack();
		int limit = Math.max(1, Math.min(50, config.flipFinderLimit()));

		// Get selected flip style
		FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
		String flipStyle = selectedStyle != null ? selectedStyle.getApiValue() : FlipSmartConfig.FlipStyle.BALANCED.getApiValue();

		// Get selected timeframe (null for Active mode)
		FlipSmartConfig.FlipTimeframe selectedTimeframe = (FlipSmartConfig.FlipTimeframe) flipTimeframeDropdown.getSelectedItem();
		String timeframe = null;
		if (selectedTimeframe != null && selectedTimeframe.isTimeframeBased())
		{
			timeframe = selectedTimeframe.getApiValue();
		}

		// Only generate random seed for manual refresh to get variety in suggestions
		// Auto-refresh keeps same items so user can focus on setting up flips
		Integer randomSeed = shuffleSuggestions ? ThreadLocalRandom.current().nextInt() : null;

		// Use unified /flip-finder endpoint with all parameters
		apiClient.getFlipRecommendationsAsync(cashStack, flipStyle, limit, randomSeed, timeframe).thenAccept(response ->
		{
			handleRecommendationsResponse(response, scrollPos);
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
	 * Handle the recommendations response from either endpoint
	 */
	private void handleRecommendationsResponse(FlipFinderResponse response, int scrollPos)
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

			// Update premium status from flip-finder response and show/hide subscribe message
			apiClient.setPremium(response.isPremium());
			subscribeLabel.setVisible(!response.isPremium());

			// Validate focus after refresh in case focused item is no longer recommended
			validateFocus();

			// If auto-recommend is active, refresh the queue with new recommendations
			AutoRecommendService service = plugin.getAutoRecommendService();
			if (service != null && service.isActive())
			{
				service.refreshQueue(new ArrayList<>(currentRecommendations));
			}
		});
	}

	/**
	 * Refresh active flips
	 */
	public void refreshActiveFlips()
	{
		// Save scroll position before refresh
		final int scrollPos = getScrollPosition(activeFlipsScrollPane);
		// Don't clear container yet - keep showing old flips until new data arrives
		// This prevents the UI flash when flips disappear and reappear
		
		// Clear cached sell prices - they'll be recalculated when panels are created
		displayedSellPrices.clear();

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
					// Show flips that are either:
					// 1. Currently in GE slots or collected items (thread-safe check)
					// 2. Had activity in the last 7 days (covers client restart scenarios)
					// We use a generous 7-day threshold because:
					// - On client restart, GE tracking takes time to populate
					// - collectedItemIds is session-only and resets on restart
					// - The backend handles proper stale flip cleanup via /flips/cleanup
					// Note: Using getActiveFlipItemIds() instead of WithInventory() to avoid thread issues
					java.util.Set<Integer> activeItemIds = plugin.getActiveFlipItemIds();
					java.time.Instant sevenDaysAgo = java.time.Instant.now().minus(java.time.Duration.ofDays(7));
					
					for (ActiveFlip flip : response.getActiveFlips())
					{
						boolean inGeOrCollected = activeItemIds.contains(flip.getItemId());
						boolean isRecent = false;
						
						// Check if flip had activity in the last 7 days
						// Use lastBuyTime if available (more accurate), fall back to firstBuyTime
						String timeStr = flip.getLastBuyTime();
						if (timeStr == null || timeStr.isEmpty())
						{
							timeStr = flip.getFirstBuyTime();
						}
						
						if (timeStr != null && !timeStr.isEmpty())
						{
							try
							{
								java.time.Instant buyTime = java.time.Instant.parse(timeStr);
								isRecent = buyTime.isAfter(sevenDaysAgo);
							}
							catch (Exception e)
							{
								// Can't parse, assume recent to be safe
								isRecent = true;
							}
						}
						else
						{
							// No timestamp, assume recent
							isRecent = true;
						}
						
						if (inGeOrCollected || isRecent)
						{
							currentActiveFlips.add(flip);
							log.debug("Including flip: {} (inGE={}, recent={})", 
								flip.getItemName(), inGeOrCollected, isRecent);
						}
						else
						{
							log.debug("Filtering stale flip: {} (not in GE and older than 7 days)", flip.getItemName());
						}
					}
					log.debug("Loaded {} active flips ({} from backend, {} filtered)", 
						currentActiveFlips.size(), response.getActiveFlips().size(),
						response.getActiveFlips().size() - currentActiveFlips.size());
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
		// Don't clear container yet - keep showing old flips until new data arrives
		// This prevents the UI flash when flips disappear and reappear

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
		showErrorInContainer(completedFlipsListContainer, "Completed Flips", message);
	}

	/**
	 * Show message when there are no completed flips
	 */
	private void showNoCompletedFlips()
	{
		completedFlipsListContainer.removeAll();
		completedFlipsListContainer.add(createEmptyStatePanel(
			"No completed flips",
			"<html><center>Complete your first flip to see<br>it here! Buy and sell items to<br>track your profits</center></html>",
			60
		));
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
	 * 
	 * Logic: If an item has ANY pending buy order in the GE, skip the active flip.
	 * The pending order is the source of truth for items currently in buy slots.
	 * Active flips should only show for COLLECTED items (no longer in GE, waiting to sell).
	 */
	private boolean shouldShowActiveFlip(ActiveFlip flip, 
			java.util.Map<Integer, java.util.List<FlipSmartPlugin.PendingOrder>> pendingByItemId)
	{
		java.util.List<FlipSmartPlugin.PendingOrder> matchingPending = pendingByItemId.get(flip.getItemId());
		
		if (matchingPending == null || matchingPending.isEmpty())
		{
			// No pending buy orders for this item - show the active flip
			return true;
		}
		
		// There's a pending buy order for this item in the GE.
		// Skip the active flip to avoid duplicates - the pending order panel shows the current state.
		log.debug("Skipping active flip {} - has {} pending buy order(s) in GE",
			flip.getItemName(), matchingPending.size());
		return false;
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
	 * Update the status label with response info (for Recommended tab)
	 */
	private void updateStatusLabel(FlipFinderResponse response)
	{
		FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
		String flipStyleText = selectedStyle != null ? selectedStyle.toString() : "Balanced";
		int count = response.getRecommendations().size();
		String itemWord = count == 1 ? "suggestion" : "suggestions";
		
		if (response.getCashStack() != null)
		{
			statusLabel.setText(String.format("%s | %d %s | Cash: %s",
				flipStyleText,
				count,
				itemWord,
				formatGP(response.getCashStack())));
		}
		else
		{
			statusLabel.setText(String.format("%s | %d %s", flipStyleText, count, itemWord));
		}
	}

	/**
	 * Show an error message in recommended tab
	 */
	private void showErrorInRecommended(String message)
	{
		statusLabel.setText(ERROR_DIALOG_TITLE);
		showErrorInContainer(recommendedListContainer, "Flip Finder", message);
	}

	/**
	 * Show an error message in active flips tab
	 */
	private void showErrorInActiveFlips(String message)
	{
		showErrorInContainer(activeFlipsListContainer, "Active Flips", message);
	}

	/**
	 * Helper method to show an error panel in any container.
	 * Reduces code duplication across error display methods.
	 *
	 * @param container The panel container to show the error in
	 * @param title The title for the error panel
	 * @param message The error message to display
	 */
	private void showErrorInContainer(JPanel container, String title, String message)
	{
		container.removeAll();

		PluginErrorPanel errorPanel = new PluginErrorPanel();
		errorPanel.setContent(title, message);
		errorPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		errorPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		container.add(errorPanel);

		container.revalidate();
		container.repaint();
	}

	/**
	 * Create an empty state panel with a title and instruction message.
	 * This is a helper to reduce code duplication across empty state displays.
	 * 
	 * @param title The main title text
	 * @param instruction The instruction/explanation HTML text
	 * @param topPadding Top padding for the panel
	 * @return A configured empty state panel
	 */
	private JPanel createEmptyStatePanel(String title, String instruction, int topPadding)
	{
		JPanel emptyPanel = new JPanel();
		emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
		emptyPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		emptyPanel.setBorder(new EmptyBorder(topPadding, 15, topPadding, 15));
		emptyPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		emptyPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		JLabel titleLabel = new JLabel(title);
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(FONT_BOLD_16);
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel instructionLabel = new JLabel(instruction);
		instructionLabel.setForeground(COLOR_TEXT_DIM_GRAY);
		instructionLabel.setFont(FONT_PLAIN_12);
		instructionLabel.setHorizontalAlignment(SwingConstants.CENTER);
		instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		emptyPanel.add(titleLabel);
		emptyPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		emptyPanel.add(instructionLabel);

		return emptyPanel;
	}

	/**
	 * Show message when player is logged out of RuneScape (at login screen).
	 * This saves API requests and battery by not polling while the player can't use the GE anyway.
	 */
	public void showLoggedOutOfGameState()
	{
		statusLabel.setText(MSG_LOGIN_TO_RUNESCAPE);
		
		// Update all tabs with logged out message
		recommendedListContainer.removeAll();
		recommendedListContainer.add(createEmptyStatePanel(MSG_LOGIN_TO_RUNESCAPE, MSG_LOGIN_INSTRUCTION, 80));
		recommendedListContainer.revalidate();
		recommendedListContainer.repaint();
		
		activeFlipsListContainer.removeAll();
		activeFlipsListContainer.add(createEmptyStatePanel(MSG_LOGIN_TO_RUNESCAPE, MSG_LOGIN_INSTRUCTION, 80));
		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();
		
		completedFlipsListContainer.removeAll();
		completedFlipsListContainer.add(createEmptyStatePanel(MSG_LOGIN_TO_RUNESCAPE, MSG_LOGIN_INSTRUCTION, 80));
		completedFlipsListContainer.revalidate();
		completedFlipsListContainer.repaint();
	}

	/**
	 * Show message when there are no active flips
	 */
	private void showNoActiveFlips()
	{
		activeFlipsListContainer.removeAll();
		activeFlipsListContainer.add(createEmptyStatePanel(
			"No active flips",
			"<html><center>Buy items from the Recommended<br>tab to start tracking your flips</center></html>",
			60
		));
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

		// Check if auto-recommend is active and get current focused item
		AutoRecommendService service = plugin.getAutoRecommendService();
		FlipRecommendation autoRecCurrent = (service != null && service.isActive())
			? service.getCurrentRecommendation() : null;

		for (FlipRecommendation rec : recommendations)
		{
			JPanel panel = createRecommendationPanel(rec);

			// Highlight if this is the current auto-recommend item
			if (autoRecCurrent != null && autoRecCurrent.getItemId() == rec.getItemId())
			{
				applyAutoRecommendStyle(panel);
			}

			recommendedListContainer.add(panel);
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
		return GpUtils.formatGPSigned(amount);
	}

	/**
	 * Format GP amount with commas for exact input (e.g., "1,234,567")
	 */
	private String formatGPExact(int amount)
	{
		return GpUtils.formatGPExact(amount);
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
		JPanel topPanel = new JPanel(new BorderLayout(4, 0));
		topPanel.setBackground(bgColor);

		JPanel namePanel = new JPanel(new BorderLayout(5, 0));
		namePanel.setBackground(bgColor);

		// Get item image
		AsyncBufferedImage itemImage = itemManager.getImage(itemId);
		JLabel iconLabel = new JLabel();
		setupIconLabel(iconLabel, itemImage);

		// Use HTML to allow text wrapping for long item names
		String escapedName = escapeHtml(itemName);
		JLabel nameLabel = new JLabel("<html>" + escapedName + "</html>");
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(FONT_BOLD_13);
		// Limit the name label width to ensure icons always fit
		// Panel is ~220px wide, minus item icon (36px), block+chart icons (40px), padding (14px) = ~130px for name
		nameLabel.setPreferredSize(new Dimension(130, nameLabel.getPreferredSize().height));
		nameLabel.setMaximumSize(new Dimension(130, Integer.MAX_VALUE));

		namePanel.add(iconLabel, BorderLayout.WEST);
		namePanel.add(nameLabel, BorderLayout.CENTER);

		// Create icons panel with chart icon and block icon
		JPanel iconsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		iconsPanel.setOpaque(false);

		// Create block icon
		JLabel blockIconLabel = createBlockIconLabel(itemId, itemName);
		iconsPanel.add(blockIconLabel);

		// Create chart icon
		JLabel chartIconLabel = createChartIconLabel(itemId);
		iconsPanel.add(chartIconLabel);

		topPanel.add(namePanel, BorderLayout.CENTER);
		topPanel.add(iconsPanel, BorderLayout.EAST);

		return new HeaderPanels(topPanel, namePanel);
	}
	
	/**
	 * Escape HTML special characters in a string.
	 * Used when embedding text in HTML labels.
	 */
	private String escapeHtml(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;");
	}
	
	/**
	 * Draw a bar chart icon onto a 14x14 image with the given colors.
	 */
	private java.awt.image.BufferedImage drawChartIcon(Color barColor, Color baselineColor)
	{
		java.awt.image.BufferedImage icon = new java.awt.image.BufferedImage(14, 14, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = icon.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

		g.setComposite(java.awt.AlphaComposite.Clear);
		g.fillRect(0, 0, 14, 14);
		g.setComposite(java.awt.AlphaComposite.SrcOver);

		g.setColor(barColor);
		g.fillRect(1, 9, 3, 4);   // Short bar
		g.fillRect(5, 5, 3, 8);   // Medium bar
		g.fillRect(9, 2, 3, 11);  // Tall bar

		g.setColor(baselineColor);
		g.drawLine(0, 13, 13, 13);

		g.dispose();
		return icon;
	}

	/**
	 * Create a clickable chart icon label that opens the item's page on the website.
	 * Uses a simple bar chart icon drawn with Java 2D graphics.
	 */
	private JLabel createChartIconLabel(int itemId)
	{
		java.awt.image.BufferedImage chartIcon = drawChartIcon(new Color(100, 180, 255), new Color(150, 150, 150));

		JLabel chartLabel = new JLabel(new ImageIcon(chartIcon));
		chartLabel.setToolTipText("View price history on Flip Smart website");
		chartLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		chartLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
		chartLabel.setOpaque(false);

		// Add click listener to open website
		chartLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Prevent the click from propagating to the parent panel
				e.consume();
				LinkBrowser.browse(WEBSITE_ITEM_URL + itemId);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				chartLabel.setIcon(new ImageIcon(drawChartIcon(new Color(150, 220, 255), new Color(200, 200, 200))));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				chartLabel.setIcon(new ImageIcon(chartIcon));
			}
		});

		return chartLabel;
	}

	/**
	 * Draw a ban/circle-slash icon onto a 14x14 image with the given color.
	 */
	private java.awt.image.BufferedImage drawBlockIcon(Color color)
	{
		java.awt.image.BufferedImage icon = new java.awt.image.BufferedImage(14, 14, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = icon.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

		g.setComposite(java.awt.AlphaComposite.Clear);
		g.fillRect(0, 0, 14, 14);
		g.setComposite(java.awt.AlphaComposite.SrcOver);

		g.setColor(color);
		g.setStroke(new java.awt.BasicStroke(1.5f));
		g.drawOval(1, 1, 11, 11);
		g.drawLine(3, 11, 11, 3);

		g.dispose();
		return icon;
	}

	/**
	 * Create a clickable block icon label that adds the item to a blocklist.
	 * Uses a ban/circle-slash icon drawn with Java 2D graphics.
	 */
	private JLabel createBlockIconLabel(int itemId, String itemName)
	{
		java.awt.image.BufferedImage blockIcon = drawBlockIcon(new Color(180, 100, 100));

		JLabel blockLabel = new JLabel(new ImageIcon(blockIcon));
		blockLabel.setToolTipText("Block this item from recommendations");
		blockLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		blockLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
		blockLabel.setOpaque(false);

		// Add click listener to block the item
		blockLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				e.consume();
				handleBlockItemClick(itemId, itemName);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				blockLabel.setIcon(new ImageIcon(drawBlockIcon(new Color(255, 100, 100))));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				blockLabel.setIcon(new ImageIcon(blockIcon));
			}
		});

		return blockLabel;
	}

	/**
	 * Handle click on the block icon for an item.
	 * Shows a confirmation dialog and adds to the first available blocklist.
	 */
	private void handleBlockItemClick(int itemId, String itemName)
	{
		// Fetch blocklists if not cached or cache has expired
		boolean cacheExpired = System.currentTimeMillis() - blocklistCacheTimestamp > BLOCKLIST_CACHE_TTL_MS;
		if (cachedBlocklists.isEmpty() || cacheExpired)
		{
			fetchBlocklistsAndShowDialog(itemId, itemName);
		}
		else
		{
			showBlockConfirmationDialog(itemId, itemName);
		}
	}

	/**
	 * Fetch blocklists from the API and then show the block dialog.
	 */
	private void fetchBlocklistsAndShowDialog(int itemId, String itemName)
	{
		apiClient.getBlocklistsAsync().thenAccept(response -> {
			if (response != null && response.getBlocklists() != null)
			{
				cachedBlocklists.clear();
				cachedBlocklists.addAll(response.getBlocklists());
				blocklistCacheTimestamp = System.currentTimeMillis();
			}
			SwingUtilities.invokeLater(() -> showBlockConfirmationDialog(itemId, itemName));
		}).exceptionally(ex -> {
			log.warn("Failed to fetch blocklists: {}", ex.getMessage());
			SwingUtilities.invokeLater(() -> {
				JOptionPane.showMessageDialog(
					FlipFinderPanel.this,
					"Failed to load blocklists. Please try again.",
					ERROR_DIALOG_TITLE,
					JOptionPane.ERROR_MESSAGE
				);
			});
			return null;
		});
	}

	/**
	 * Show a confirmation dialog and add the item to a blocklist.
	 */
	private void showBlockConfirmationDialog(int itemId, String itemName)
	{
		if (cachedBlocklists.isEmpty())
		{
			// No blocklists exist - offer to create one
			int result = JOptionPane.showConfirmDialog(
				this,
				"You don't have any blocklists yet.\nWould you like to create one on the FlipSmart website?",
				"No Blocklists",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.QUESTION_MESSAGE
			);
			if (result == JOptionPane.YES_OPTION)
			{
				LinkBrowser.browse("https://flipsmart.net/blocklists");
			}
			return;
		}

		// Find the first active blocklist, or use the first one
		BlocklistSummary targetBlocklist = cachedBlocklists.stream()
			.filter(BlocklistSummary::isActive)
			.findFirst()
			.orElse(cachedBlocklists.get(0));

		String message = String.format(
			"Block \"%s\" from recommendations?\n\nThis will add it to your \"%s\" blocklist.",
			itemName,
			targetBlocklist.getName()
		);

		int result = JOptionPane.showConfirmDialog(
			this,
			message,
			"Block Item",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.QUESTION_MESSAGE
		);

		if (result == JOptionPane.YES_OPTION)
		{
			blockItem(targetBlocklist.getId(), itemId, itemName);
		}
	}

	/**
	 * Add an item to a blocklist via the API.
	 */
	private void blockItem(int blocklistId, int itemId, String itemName)
	{
		apiClient.addItemToBlocklistAsync(blocklistId, itemId).thenAccept(success -> {
			SwingUtilities.invokeLater(() -> {
				if (Boolean.TRUE.equals(success))
				{
					JOptionPane.showMessageDialog(
						FlipFinderPanel.this,
						String.format("\"%s\" has been blocked.%nIt will no longer appear in recommendations.", itemName),
						"Item Blocked",
						JOptionPane.INFORMATION_MESSAGE
					);
					// Refresh recommendations to remove the blocked item
					refresh();
				}
				else
				{
					JOptionPane.showMessageDialog(
						FlipFinderPanel.this,
						"Failed to block item. Please try again.",
						ERROR_DIALOG_TITLE,
						JOptionPane.ERROR_MESSAGE
					);
				}
			});
		}).exceptionally(ex -> {
			log.warn("Failed to block item {}: {}", itemId, ex.getMessage());
			SwingUtilities.invokeLater(() -> {
				JOptionPane.showMessageDialog(
					FlipFinderPanel.this,
					"Failed to block item. Please try again.",
					ERROR_DIALOG_TITLE,
					JOptionPane.ERROR_MESSAGE
				);
			});
			return null;
		});
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
		
		// Handle Infinity/NaN ROI (happens when cost is 0, e.g. pending orders with no fills)
		if (Double.isInfinite(roi) || Double.isNaN(roi))
		{
			return String.format("Margin: %s (pending)", marginText);
		}
		
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
	 * Apply a price indicator background color to the active flip panel and its children.
	 * Used to visually indicate whether the sell price is higher or lower than the original recommendation.
	 */
	private void applyPriceIndicatorBackground(JPanel panel, JPanel topPanel, JPanel namePanel, JPanel detailsPanel, Color bgColor)
	{
		panel.setBackground(bgColor);
		topPanel.setBackground(bgColor);
		namePanel.setBackground(bgColor);
		detailsPanel.setBackground(bgColor);
		// Store the base color as a client property so hover handlers can access it
		panel.putClientProperty("baseBackgroundColor", bgColor);
		panel.repaint();
	}
	
	/**
	 * Brighten a color for hover effects.
	 * Increases RGB values by a fixed amount while keeping them within valid range.
	 */
	private Color brightenColor(Color color, int amount)
	{
		return new Color(
			Math.min(255, color.getRed() + amount),
			Math.min(255, color.getGreen() + amount),
			Math.min(255, color.getBlue() + amount)
		);
	}
	
	/**
	 * Get the base background color for a panel (either price indicator color or default).
	 * Checks for stored client property first, falls back to default color.
	 */
	private Color getBaseBackgroundColor(JPanel panel, Color defaultColor)
	{
		Object stored = panel.getClientProperty("baseBackgroundColor");
		if (stored instanceof Color)
		{
			return (Color) stored;
		}
		return defaultColor;
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
	 * Set the callback for when authentication succeeds.
	 * This allows the plugin to sync RSN after Discord login.
	 */
	public void setOnAuthSuccess(Runnable callback)
	{
		this.onAuthSuccess = callback;
	}
	
	/**
	 * Set a recommendation as the current focus for Flip Assist
	 */
	private void setFocus(FlipRecommendation rec, JPanel panel)
	{
		// Create focused flip for buying with price offset applied
		int priceOffset = config.priceOffset();
		FocusedFlip newFocus = FocusedFlip.forBuy(
			rec.getItemId(),
			rec.getItemName(),
			rec.getRecommendedBuyPrice(),
			rec.getRecommendedQuantity(),
			rec.getRecommendedSellPrice(),
			priceOffset
		);
		
		updateFocus(newFocus, panel);
	}
	
	/**
	 * Set an active flip as the current focus for Flip Assist.
	 * Uses the cached sell price from the panel display to ensure consistency.
	 */
	private void setFocus(ActiveFlip flip, JPanel panel)
	{
		// Use the cached sell price that's already displayed in the panel
		// This ensures the Flip Assist shows the same price as the Active Flips tab
		Integer cachedSellPrice = displayedSellPrices.get(flip.getItemId());
		int priceOffset = config.priceOffset();
		
		if (cachedSellPrice != null && cachedSellPrice > 0)
		{
			// Use the price that's already shown in the UI with offset applied
			FocusedFlip newFocus = FocusedFlip.forSell(
				flip.getItemId(),
				flip.getItemName(),
				cachedSellPrice,
				flip.getTotalQuantity(),
				priceOffset
			);
			updateFocus(newFocus, panel);
		}
		else
		{
			// Fallback: fetch market data if no cached price (shouldn't normally happen)
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
				
				// Cache this price for future use
				displayedSellPrices.put(flip.getItemId(), sellPrice);
				
				// Create focused flip for selling with offset applied
				FocusedFlip newFocus = FocusedFlip.forSell(
					flip.getItemId(),
					flip.getItemName(),
					sellPrice,
					flip.getTotalQuantity(),
					priceOffset
				);
				
				SwingUtilities.invokeLater(() -> updateFocus(newFocus, panel));
			});
		}
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
		
		// Create focused flip for selling the filled items with price offset applied
		int priceOffset = config.priceOffset();
		FocusedFlip newFocus = FocusedFlip.forSell(
			pending.itemId,
			pending.itemName,
			sellPrice,
			pending.quantityFilled > 0 ? pending.quantityFilled : pending.quantity,
			priceOffset
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

		updateChildBackgrounds(panel, COLOR_FOCUSED_BG);

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

		updateChildBackgrounds(panel, ColorScheme.DARKER_GRAY_COLOR);

		panel.revalidate();
		panel.repaint();
	}

	/**
	 * Apply the auto-recommend highlight style to a recommendation panel.
	 */
	private void applyAutoRecommendStyle(JPanel panel)
	{
		panel.setBackground(COLOR_AUTO_RECOMMEND_BG);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(COLOR_AUTO_RECOMMEND_BORDER, 2),
			new EmptyBorder(6, 8, 6, 8)
		));
		updateChildBackgrounds(panel, COLOR_AUTO_RECOMMEND_BG);
	}
	
	/**
	 * Get the current focused flip
	 */
	public FocusedFlip getCurrentFocus()
	{
		return currentFocus;
	}
	
	/**
	 * Get the displayed sell price for an item from the Active Flips panel.
	 * This is the "smart" price that considers time thresholds and market conditions.
	 * @param itemId The item ID
	 * @return The displayed sell price, or null if not cached
	 */
	public Integer getDisplayedSellPrice(int itemId)
	{
		return displayedSellPrices.get(itemId);
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
					// Cache the displayed sell price so Flip Assist uses the same value
					displayedSellPrices.put(flip.getItemId(), smartSellPrice);
					
					// Apply background color based on price comparison with original recommendation
					// Green if selling higher than recommended, red if selling lower
					Integer recommendedPrice = flip.getRecommendedSellPrice();
					if (recommendedPrice != null && recommendedPrice > 0 && !smartSellPrice.equals(recommendedPrice))
					{
						Color priceIndicatorBg = smartSellPrice > recommendedPrice 
							? COLOR_PRICE_HIGHER_BG  // Green - selling higher than estimate
							: COLOR_PRICE_LOWER_BG;  // Red - selling lower than estimate
						
						// Apply to panel and all child panels (not focused panel - that has its own style)
						if (currentFocus == null || currentFocus.getItemId() != flip.getItemId())
						{
							applyPriceIndicatorBackground(panel, topPanel, namePanel, detailsPanel, priceIndicatorBg);
						}
					}
					
					// Update Flip Assist if this item is currently focused
					if (currentFocus != null && currentFocus.getItemId() == flip.getItemId() && currentFocus.isSelling())
					{
						int priceOffset = config.priceOffset();
						FocusedFlip updatedFocus = FocusedFlip.forSell(
							flip.getItemId(),
							flip.getItemName(),
							smartSellPrice,
							flip.getTotalQuantity(),
							priceOffset
						);
						currentFocus = updatedFocus;
						if (onFocusChanged != null)
						{
							onFocusChanged.accept(updatedFocus);
						}
					}
					
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
					
					// Row 4: Update Profit | Cost - use cyan for higher sell, orange for lower
					profitCostLabel.setText(formatProfitCostText(totalProfit, flip.getTotalInvested()));
					if (totalProfit <= 0)
					{
						profitCostLabel.setForeground(COLOR_LOSS_RED);
					}
					else if (recommendedPrice != null && recommendedPrice > 0 && smartSellPrice > recommendedPrice)
					{
						profitCostLabel.setForeground(Color.CYAN);  // Cyan for higher-than-expected profit
					}
					else
					{
						profitCostLabel.setForeground(COLOR_PROFIT_GREEN);
					}
					
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

		// Store default background color as client property (will be overwritten if price indicator is applied)
		panel.putClientProperty("baseBackgroundColor", ColorScheme.DARKER_GRAY_COLOR);
		
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
					// Get the base color (may be price indicator color) and brighten it for hover
					Color baseColor = getBaseBackgroundColor(panel, ColorScheme.DARKER_GRAY_COLOR);
					Color hoverColor = brightenColor(baseColor, 15);
					setPanelBackgrounds(hoverColor, panel, topPanel, namePanel, detailsPanel);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != flip.getItemId() 
					|| !currentFocus.isSelling())
				{
					// Restore the base color (may be price indicator color)
					Color baseColor = getBaseBackgroundColor(panel, ColorScheme.DARKER_GRAY_COLOR);
					setPanelBackgrounds(baseColor, panel, topPanel, namePanel, detailsPanel);
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
			// Dismiss asynchronously with RSN filter for multi-account support
			String rsn = plugin.getCurrentRsnSafe().orElse(null);
			apiClient.dismissActiveFlipAsync(flip.getItemId(), rsn).thenAccept(success ->
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
							ERROR_DIALOG_TITLE,
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

	/**
	 * Toggle auto-recommend on/off.
	 * When turned on, starts cycling through current recommendations into Flip Assist.
	 */
	private void toggleAutoRecommend()
	{
		AutoRecommendService service = plugin.getAutoRecommendService();
		if (service == null)
		{
			autoRecommendButton.setSelected(false);
			return;
		}

		if (autoRecommendButton.isSelected())
		{
			// Starting auto-recommend
			if (currentRecommendations.isEmpty())
			{
				autoRecommendButton.setSelected(false);
				autoRecommendStatusLabel.setText("No recommendations - refresh first");
				autoRecommendStatusLabel.setVisible(true);
				return;
			}

			// Wire status callback
			service.setOnStatusChanged(status -> SwingUtilities.invokeLater(() -> {
				autoRecommendStatusLabel.setText(status);
				autoRecommendStatusLabel.setVisible(true);
			}));

			// Wire queue advanced callback to repaint panel highlight
			service.setOnQueueAdvanced(() ->
				populateRecommendations(new ArrayList<>(currentRecommendations)));

			service.start(new ArrayList<>(currentRecommendations));

			// Switch to Recommended tab
			tabbedPane.setSelectedIndex(0);

			// Start the 2-minute refresh timer
			plugin.startAutoRecommendRefreshTimer();

			autoRecommendButton.setBackground(ColorScheme.BRAND_ORANGE);
			autoRecommendButton.setText("Auto \u25CF");
			log.info("Auto-recommend enabled with {} recommendations", currentRecommendations.size());
		}
		else
		{
			// Stopping auto-recommend
			service.stop();
			plugin.stopAutoRecommendRefreshTimer();

			autoRecommendButton.setBackground(null);
			autoRecommendButton.setText("Auto");
			autoRecommendStatusLabel.setVisible(false);

			// Repaint recommendations to remove highlight
			populateRecommendations(new ArrayList<>(currentRecommendations));

			log.info("Auto-recommend disabled");
		}
	}

	/**
	 * Update the auto-recommend button state (e.g., when service is stopped externally).
	 */
	public void updateAutoRecommendButton(boolean active)
	{
		SwingUtilities.invokeLater(() -> {
			autoRecommendButton.setSelected(active);
			if (active)
			{
				autoRecommendButton.setBackground(ColorScheme.BRAND_ORANGE);
				autoRecommendButton.setText("Auto \u25CF");
			}
			else
			{
				autoRecommendButton.setBackground(null);
				autoRecommendButton.setText("Auto");
				autoRecommendStatusLabel.setVisible(false);
			}
		});
	}
}

