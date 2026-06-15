package com.flipsmart;
import com.flipsmart.domain.offer.OfferAction;
import com.flipsmart.api.dto.OfferAdviceResponse;
import com.flipsmart.domain.flip.CompletedFlip;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.api.dto.FlipFinderResponse;
import com.flipsmart.domain.flip.FlipAnalysis;
import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.api.dto.BlocklistSummary;
import com.flipsmart.domain.offer.PendingOrder;
import com.flipsmart.recommend.SmartSellPricer;
import com.flipsmart.ui.panel.CardWidgets;
import com.flipsmart.ui.panel.LoginPanel;
import com.flipsmart.ui.panel.PanelFormat;
import com.flipsmart.util.BuyPriceLookup;
import com.flipsmart.util.GeTax;
import com.flipsmart.util.GpUtils;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class FlipFinderPanel extends PluginPanel
{
	// Configuration constants
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String CONFIG_KEY_FLIP_STYLE = "flipStyle";
	private static final String CONFIG_KEY_FLIP_TIMEFRAME = "flipTimeframe";

	// Constants for duplicated literals
	private static final String FONT_ARIAL = "Arial";
	private static final String ERROR_PREFIX = "Error: ";
	private static final String FORMAT_QTY = "Qty: %d";
	private static final String FORMAT_SELL = "Sell: %s";
	private static final String FORMAT_ROI = "ROI: %.1f%%";
	private static final String FORMAT_BUY_SELL = "Buy: %s | Sell: %s";
	private static final String ERROR_DIALOG_TITLE = "Error";
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
	private static final Color COLOR_AUTO_RECOMMEND_ACTIVE = new Color(200, 150, 0);
	
	// Website base URL for item pages
	private static final String WEBSITE_ITEM_URL = "https://flipsmart.net/items/";

	// Discord invite link
	private static final String DISCORD_INVITE_URL = "https://discord.gg/8CrcM9qYF9";

	// Premium subscription link (update when dashboard URL is available)
	private static final String SUBSCRIBE_LINK = "https://flipsmart.net/dashboard";
	private static final String SUBSCRIBE_MESSAGE = "Subscribe to Premium for all slots & RSNs";

	// FlipSmart website links (header). Authenticated users land on the dashboard.
	private static final String WEBSITE_LANDING_URL = "https://flipsmart.net";
	private static final String WEBSITE_DASHBOARD_URL = "https://flipsmart.net/dashboard";
	private static final String WEBSITE_LINK_TEXT = "flipsmart.net";
	private static final Color LINK_COLOR = new Color(110, 159, 255);
	private static final Color OVERRIDE_ACTIVE_COLOR = new Color(255, 200, 50);
	private static final Color OVERRIDE_ERROR_COLOR = new Color(220, 90, 90);

	// Common fonts
	private static final Font FONT_PLAIN_11 = new Font(FONT_ARIAL, Font.PLAIN, 11);
	private static final Font FONT_PLAIN_12 = new Font(FONT_ARIAL, Font.PLAIN, 12);
	private static final Font FONT_BOLD_12 = new Font(FONT_ARIAL, Font.BOLD, 12);
	private static final Font FONT_BOLD_13 = new Font(FONT_ARIAL, Font.BOLD, 13);
	private static final Font FONT_BOLD_16 = new Font(FONT_ARIAL, Font.BOLD, 16);
	

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

	// Login / auth subsystem (UI construction, credential + device-auth flow)
	private transient LoginPanel loginPanel;
	private JPanel mainPanel;

	// Premium subscribe message (shown for non-premium users)
	private JLabel subscribeLabel;

	// Callback for when authentication completes (to sync RSN)
	private transient Runnable onAuthSuccess;

	// Flip Assist focus tracking
	private transient FocusedFlip currentFocus = null;
	private transient JPanel currentFocusedPanel = null;
	private transient int currentFocusedItemId = -1;
	private transient java.util.function.Consumer<FocusedFlip> onFocusChanged;
	private transient java.util.function.IntFunction<OfferAdviceResponse> offerDispositionLookup;

	// Cache displayed sell prices to ensure focus uses same price as shown in UI
	// Key: itemId, Value: calculated sell price shown in the active flip panel
	private final java.util.Map<Integer, Integer> displayedSellPrices = new java.util.concurrent.ConcurrentHashMap<>();

	// Auto-recommend UI
	private JToggleButton autoRecommendButton;
	private JButton skipButton;
	private JLabel autoRecommendStatusLabel;

	// Cashstack override indicator (AC3) — shows the active override or an error
	private JLabel cashstackOverrideLabel;

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
			if (apiClient.isAuthenticated())
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
			if (apiClient.isAuthenticated())
			{
				refresh();
			}
		});

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Build the login subsystem and the main panel
		loginPanel = new LoginPanel(apiClient, config, configManager,
			() -> { if (onAuthSuccess != null) onAuthSuccess.run(); },
			this::showMainPanel);
		buildMainPanel();

		// Start with login panel, then check authentication
		add(loginPanel.getComponent(), BorderLayout.CENTER);

		// Set up auth failure callback to redirect to login screen
		apiClient.setOnAuthFailure(() -> SwingUtilities.invokeLater(() -> {
			// Pre-fill email if available
			loginPanel.setEmailField(config.email());
			loginPanel.setLoginStatusText("Session expired. Please login again.",
				new Color(255, 200, 100)); // Orange warning color
			showLoginPanel();
		}));

		// Check if already authenticated and switch to main panel if so
		loginPanel.checkAuthenticationAndShow();
	}

	/**
	 * Build the main flip finder panel
	 */
	private void buildMainPanel()
	{
		mainPanel = new JPanel(new BorderLayout());
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header: brand title + website link stacked on the left, buttons on the right
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JPanel titleBox = new JPanel();
		titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
		titleBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel titleLabel = new JLabel("FlipSmart");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(FONT_BOLD_16);
		titleLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		JLabel brandLink = buildWebsiteLink();
		brandLink.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		titleBox.add(titleLabel);
		titleBox.add(brandLink);

		// Logout button with compact styling
		JButton logoutButton = new JButton("Logout");
		logoutButton.setFocusable(false);
		logoutButton.setMargin(new Insets(2, 4, 2, 4));
		logoutButton.addActionListener(e -> handleLogout());

		// Refresh button with compact styling
		refreshButton.setFocusable(false);
		refreshButton.setMargin(new Insets(2, 4, 2, 4));
		refreshButton.addActionListener(e -> refresh(true));

		JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		headerButtons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerButtons.add(logoutButton);
		headerButtons.add(refreshButton);

		headerPanel.add(titleBox, BorderLayout.WEST);
		headerPanel.add(headerButtons, BorderLayout.EAST);

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

		// Row 1: Style dropdown (left) + Auto button (right)
		JPanel styleRow = new JPanel(new BorderLayout());
		styleRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JPanel styleLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		styleLeft.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		styleLeft.add(flipStyleLabel);
		styleLeft.add(flipStyleDropdown);

		// Auto-recommend toggle button
		autoRecommendButton = new JToggleButton("Auto") {
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				super.paintComponent(g);
				if (isSelected())
				{
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
						java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
					Font f = getFont().deriveFont(Font.BOLD);
					g2.setFont(f);
					java.awt.FontMetrics fm = g2.getFontMetrics();
					String text = "Auto";
					int x = (getWidth() - fm.stringWidth(text)) / 2;
					int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
					// Draw dark outline
					g2.setColor(new Color(40, 30, 0));
					for (int dx = -1; dx <= 1; dx++)
					{
						for (int dy = -1; dy <= 1; dy++)
						{
							if (dx != 0 || dy != 0)
							{
								g2.drawString(text, x + dx, y + dy);
							}
						}
					}
					// Draw white text on top
					g2.setColor(Color.WHITE);
					g2.drawString(text, x, y);
					g2.dispose();
				}
			}
		};
		autoRecommendButton.setFocusable(false);
		autoRecommendButton.setMargin(new Insets(2, 8, 2, 8));
		autoRecommendButton.setForeground(Color.WHITE);
		autoRecommendButton.setToolTipText("Auto-cycle through recommendations into Flip Assist");
		autoRecommendButton.addActionListener(e -> toggleAutoRecommend());
		autoRecommendButton.setVisible(config.enableAutoRecommend());

		// Skip button — visible only when auto-recommend is active
		skipButton = new JButton("Skip");
		skipButton.setFocusable(false);
		skipButton.setMargin(new Insets(2, 6, 2, 6));
		skipButton.setForeground(Color.WHITE);
		skipButton.setToolTipText("Skip the current recommendation");
		skipButton.addActionListener(e -> {
			AutoRecommendService service = plugin.getAutoRecommendService();
			if (service != null)
			{
				service.skip();
			}
		});
		skipButton.setVisible(false);

		JPanel styleRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
		styleRight.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		styleRight.add(autoRecommendButton);

		styleRow.add(styleLeft, BorderLayout.WEST);
		styleRow.add(styleRight, BorderLayout.EAST);

		// Row 2: Timeframe dropdown (left) + Skip button (right)
		JPanel timeframeRow = new JPanel(new BorderLayout());
		timeframeRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JPanel timeframeLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		timeframeLeft.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		timeframeLeft.add(flipTimeframeLabel);
		timeframeLeft.add(flipTimeframeDropdown);

		JPanel timeframeRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
		timeframeRight.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		timeframeRight.add(skipButton);

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

		// Cashstack override indicator (AC3) — hidden unless the override is enabled
		cashstackOverrideLabel = new JLabel();
		cashstackOverrideLabel.setFont(FONT_PLAIN_12);
		cashstackOverrideLabel.setVisible(false);
		JPanel overridePanel = new JPanel(new BorderLayout());
		overridePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		overridePanel.setBorder(new EmptyBorder(0, 10, 5, 10));
		overridePanel.add(cashstackOverrideLabel, BorderLayout.CENTER);

		// Combine controls and status into top panel
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topPanel.add(headerPanel);
		topPanel.add(controlsPanel);
		topPanel.add(statusPanel);
		topPanel.add(overridePanel);
		updateCashstackOverrideIndicator();

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
					PanelFormat.formatGP(invested)));
			}
			else if (selectedIndex == 2)
			{
				// Switched to Completed Flips tab — fetch 30-day aggregate stats
				String rsn = plugin.getCurrentRsnSafe().orElse(null);
				apiClient.getFlipStatisticsAsync(30, rsn).thenAccept(stats ->
				{
					SwingUtilities.invokeLater(() ->
					{
						if (stats != null)
						{
							statusLabel.setText(String.format("%d flips (30d) | %s profit",
								stats.getTotalFlips(),
								PanelFormat.formatGP(stats.getTotalProfit())));
						}
					});
				});
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

		JLabel websiteLink = new JLabel("Website");
		websiteLink.setForeground(new Color(100, 180, 255));
		websiteLink.setFont(new Font(FONT_ARIAL, Font.PLAIN, 14));
		websiteLink.setCursor(new Cursor(Cursor.HAND_CURSOR));
		websiteLink.setToolTipText("Visit our website to view your flips and track your performance");
		websiteLink.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse("https://flipsmart.net");
			}
		});

		JLabel discordLink = new JLabel("Discord");
		discordLink.setForeground(new Color(88, 101, 242));
		discordLink.setFont(new Font(FONT_ARIAL, Font.PLAIN, 14));
		discordLink.setCursor(new Cursor(Cursor.HAND_CURSOR));
		discordLink.setToolTipText("Join our Discord community");
		discordLink.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(DISCORD_INVITE_URL);
			}
		});

		JPanel linksPanel = new JPanel();
		linksPanel.setLayout(new BoxLayout(linksPanel, BoxLayout.X_AXIS));
		linksPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		linksPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		linksPanel.add(websiteLink);
		linksPanel.add(Box.createHorizontalStrut(12));
		linksPanel.add(discordLink);

		footerPanel.add(linksPanel);
		footerPanel.add(Box.createVerticalStrut(3));
		footerPanel.add(subscribeLabel);

		mainPanel.add(topPanel, BorderLayout.NORTH);
		mainPanel.add(tabbedPane, BorderLayout.CENTER);
		mainPanel.add(footerPanel, BorderLayout.SOUTH);
	}

	/**
	 * Clean up resources when panel is destroyed
	 */
	public void shutdown()
	{
		loginPanel.shutdown();
	}

	/**
	 * Switch from login panel to main panel
	 */
	private void showMainPanel()
	{
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
		// Stop any pending device auth polling and re-enable login buttons
		loginPanel.resetLoginView();
		removeAll();
		add(loginPanel.getComponent(), BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	/**
	 * Handle logout button click
	 */
	private void handleLogout()
	{
		// Clear API client authentication (includes refresh token)
		apiClient.clearAuth();

		// Clear stored refresh token
		loginPanel.clearRefreshToken();

		// Clear password field but keep email
		loginPanel.clearPasswordField();

		// Reset status
		loginPanel.setLoginStatusText("Logged out successfully", Color.LIGHT_GRAY);

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
	 * Sync the subscribe banner's text and visibility with the locally-stored
	 * premium / RSN-entitlement state. Safe to call from any UI-transition path
	 * (login, logout, away-from-GE, empty/failed responses, entitlements fetch);
	 * it never depends on a fresh API response.
	 */
	public void updatePremiumStatus()
	{
		if (apiClient.isRsnBlocked())
		{
			subscribeLabel.setText("Subscribe to Premium for this account");
			subscribeLabel.setVisible(true);
		}
		else
		{
			subscribeLabel.setText(SUBSCRIBE_MESSAGE);
			subscribeLabel.setVisible(!apiClient.isPremium());
		}
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

		if (!plugin.isAtGrandExchange())
		{
			showNotAtGeMessage();
			return;
		}

		// If RSN is blocked, show subscribe message instead of fetching recommendations
		if (apiClient.isRsnBlocked())
		{
			showRsnBlockedMessage();
			return;
		}

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

		// Pass RSN for RSN-level access enforcement
		String rsn = plugin.getCurrentRsnSafe().orElse(null);

		// Pass filled GE slots for strategy tier inference
		Integer filledSlots = getFilledSlots();

		// Use unified /flip-finder endpoint with all parameters
		apiClient.getFlipRecommendationsAsync(cashStack, flipStyle, limit, randomSeed, timeframe, rsn, filledSlots, plugin.isMembersWorld()).thenAccept(response ->
		{
			handleRecommendationsResponse(response, scrollPos);
		}).exceptionally(throwable ->
		{
			SwingUtilities.invokeLater(() ->
			{
				refreshButton.setEnabled(true);
				showErrorInRecommended(ERROR_PREFIX + throwable.getMessage());
				updatePremiumStatus();
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
				if (apiClient.isRsnBlocked())
				{
					showRsnBlockedMessage();
				}
				else
				{
					showErrorInRecommended("Failed to fetch recommendations. Check your API settings.");
					updatePremiumStatus();
				}
				restoreScrollPosition(recommendedScrollPane, scrollPos);
				return;
			}

			if (response.getRecommendations() == null || response.getRecommendations().isEmpty())
			{
				currentRecommendations.clear();
				showNoRecommendationsAvailable();
				updatePremiumStatus();
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

			// Update premium status from flip-finder response and show/hide subscribe message
			apiClient.setPremium(response.isPremium());

			// If free user has hit their slot limit, show upgrade message over recommendations
			// but keep currentRecommendations populated so auto-flip can still use them
			PlayerSession session = plugin.getSession();
			if (session != null && !response.isPremium()
				&& !session.hasAvailableGESlots(plugin.getFlipSlotLimit()))
			{
				showSlotLimitMessage();
			}
			else
			{
				updateStatusLabel(response);
				populateRecommendations(response.getRecommendations());
				subscribeLabel.setVisible(!response.isPremium());
			}

			restoreScrollPosition(recommendedScrollPane, scrollPos);

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

				// Build the filtered list locally first, then swap atomically into
				// currentActiveFlips so cross-thread readers (the GE slot-hover
				// overlay) never observe an empty/half-populated intermediate
				// state. Previously the clear+add loop ran inline, allowing the
				// overlay's render thread to snapshot a transient empty list and
				// silently hide the profit line. See issue #685 Bug 2.
				List<ActiveFlip> filtered = new ArrayList<>();
				int filteredCount = 0;
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
							filtered.add(flip);
							log.debug("Including flip: {} (inGE={}, recent={})",
								flip.getItemName(), inGeOrCollected, isRecent);
						}
						else
						{
							log.debug("Filtering stale flip: {} (not in GE and older than 7 days)", flip.getItemName());
						}
					}
					filteredCount = response.getActiveFlips().size() - filtered.size();
				}
				synchronized (currentActiveFlips)
				{
					currentActiveFlips.clear();
					currentActiveFlips.addAll(filtered);
				}
				if (response.getActiveFlips() != null)
				{
					log.debug("Loaded {} active flips ({} from backend, {} filtered)",
						currentActiveFlips.size(), response.getActiveFlips().size(), filteredCount);
				}

				// Get pending orders from plugin
				java.util.List<PendingOrder> pendingOrders = plugin.getPendingBuyOrders();

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
							PanelFormat.formatGP(invested)));
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
	public void updatePendingOrders(java.util.List<PendingOrder> pendingOrders)
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

		String rsn = plugin.getCurrentRsnSafe().orElse(null);

		// Fetch 30-day aggregate stats (source of truth for profit summary)
		apiClient.getFlipStatisticsAsync(30, rsn).thenAccept(stats ->
		{
			SwingUtilities.invokeLater(() ->
			{
				if (stats != null && tabbedPane.getSelectedIndex() == 2)
				{
					statusLabel.setText(String.format("%d flips (30d) | %s profit",
						stats.getTotalFlips(),
						PanelFormat.formatGP(stats.getTotalProfit())));
				}
			});
		});

		// Fetch recent completed flips for the list display
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
	private void displayActiveFlipsAndPending(java.util.List<ActiveFlip> activeFlips, java.util.List<PendingOrder> pendingOrders)
	{
		activeFlipsListContainer.removeAll();
		
		// Build a map of pending orders by itemId for smart deduplication
		java.util.Map<Integer, java.util.List<PendingOrder>> pendingByItemId = buildPendingOrdersMap(pendingOrders);
		
		// First show pending orders (items currently in GE buy slots)
		for (PendingOrder pending : pendingOrders)
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
	private java.util.Map<Integer, java.util.List<PendingOrder>> buildPendingOrdersMap(
			java.util.List<PendingOrder> pendingOrders)
	{
		java.util.Map<Integer, java.util.List<PendingOrder>> pendingByItemId = new java.util.HashMap<>();
		for (PendingOrder pending : pendingOrders)
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
			java.util.Map<Integer, java.util.List<PendingOrder>> pendingByItemId)
	{
		java.util.List<PendingOrder> matchingPending = pendingByItemId.get(flip.getItemId());
		
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
	 * Build the clickable "flipsmart.net" link shown under the header title.
	 * Opens the dashboard when authenticated, otherwise the public landing page;
	 * if auth state cannot be determined it degrades to the landing page.
	 */
	private JLabel buildWebsiteLink()
	{
		JLabel link = new JLabel(WEBSITE_LINK_TEXT);
		link.setFont(FONT_PLAIN_11);
		link.setForeground(LINK_COLOR);
		link.setCursor(new Cursor(Cursor.HAND_CURSOR));
		link.setToolTipText("Open FlipSmart in your browser");
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				boolean authed = apiClient != null && apiClient.isAuthenticated();
				LinkBrowser.browse(authed ? WEBSITE_DASHBOARD_URL : WEBSITE_LANDING_URL);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				link.setText("<html><u>" + WEBSITE_LINK_TEXT + "</u></html>");
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				link.setText(WEBSITE_LINK_TEXT);
			}
		});
		return link;
	}

	/**
	 * Refresh the cashstack-override indicator (AC3). Shows the resolved override
	 * amount when enabled with a valid value, an inline error when enabled with an
	 * unparseable value, and hides itself when the override is off.
	 */
	public void updateCashstackOverrideIndicator()
	{
		if (cashstackOverrideLabel == null)
		{
			return;
		}

		if (!config.cashstackOverrideEnabled())
		{
			cashstackOverrideLabel.setVisible(false);
			return;
		}

		java.util.OptionalInt parsed = GpUtils.parseGp(config.cashstackOverrideAmount());
		if (parsed.isPresent())
		{
			cashstackOverrideLabel.setForeground(OVERRIDE_ACTIVE_COLOR);
			cashstackOverrideLabel.setText("Cashstack override: " + GpUtils.formatGPExact(parsed.getAsInt()) + " gp");
		}
		else
		{
			cashstackOverrideLabel.setForeground(OVERRIDE_ERROR_COLOR);
			cashstackOverrideLabel.setText("⚠ Invalid override amount — using live cash");
		}
		cashstackOverrideLabel.setVisible(true);
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
	 * Get the number of currently filled GE slots.
	 * Returns null if not available.
	 * Can be overridden by subclasses to provide actual filled slot count.
	 */
	protected Integer getFilledSlots()
	{
		return null;
	}

	/**
	 * Update the status label with response info (for Recommended tab)
	 */
	private void updateStatusLabel(FlipFinderResponse response)
	{
		FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
		String flipStyleText = selectedStyle != null ? selectedStyle.toString() : "Balanced";
		int count = countDisplayed(response.getRecommendations());
		String itemWord = count == 1 ? "suggestion" : "suggestions";

		if (response.getCashStack() != null)
		{
			statusLabel.setText(String.format("%s | %d %s | Cash: %s",
				flipStyleText,
				count,
				itemWord,
				PanelFormat.formatGP(response.getCashStack())));
		}
		else
		{
			statusLabel.setText(String.format("%s | %d %s", flipStyleText, count, itemWord));
		}
	}

	/** Returns true if a recommendation passes the current profit filter. */
	private boolean shouldDisplay(FlipRecommendation rec)
	{
		return FocusedFlip.calculateAdjustedProfit(rec, config.priceOffset()) >= config.minimumProfit();
	}

	/** Returns the number of recommendations that pass the current profit filter. */
	private int countDisplayed(List<FlipRecommendation> recommendations)
	{
		int count = 0;
		for (FlipRecommendation rec : recommendations)
		{
			if (shouldDisplay(rec))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Show an error message in recommended tab
	 */
	private void showErrorInRecommended(String message)
	{
		statusLabel.setText(ERROR_DIALOG_TITLE);
		showErrorInContainer(recommendedListContainer, "Flip Finder", message);
	}

	private void showNoRecommendationsAvailable()
	{
		statusLabel.setText("No available trades");
		showErrorInContainer(recommendedListContainer, "Flip Finder",
			"There are currently no available trades for your cash stack amount and flip style.");
	}

	/**
	 * Show a "not at Grand Exchange" message in the recommendations panel.
	 * Clears current recommendations until the player travels to the GE.
	 */
	private void showNotAtGeMessage()
	{
		currentRecommendations.clear();
		showErrorInContainer(recommendedListContainer, "Flip Finder", "You must be in the Grand Exchange to load suggestions.");
		statusLabel.setText("Visit the Grand Exchange");
		refreshButton.setEnabled(true);
		updatePremiumStatus();
	}

	/**
	 * Show a subscribe message when the current RSN is blocked (3rd+ account without premium).
	 * Clears current recommendations and shows a CTA to subscribe.
	 */
	private void showRsnBlockedMessage()
	{
		currentRecommendations.clear();
		showErrorInRecommended("Subscribe to Premium to get flip suggestions for this account");
		subscribeLabel.setText("Subscribe to Premium for this account");
		subscribeLabel.setVisible(true);
		refreshButton.setEnabled(true);
	}

	/**
	 * Show an upgrade message when a free user has reached their flip slot limit.
	 * Keeps currentRecommendations so auto-flip can still use them to manage slots.
	 */
	private void showSlotLimitMessage()
	{
		showErrorInRecommended("Upgrade to Premium for more flip slots");
		subscribeLabel.setText(SUBSCRIBE_MESSAGE);
		subscribeLabel.setVisible(true);
		refreshButton.setEnabled(true);
	}

	/**
	 * Re-evaluate slot limits and update the Recommended tab accordingly.
	 * Called when GE slot state changes (e.g., new buy order placed or offer collected).
	 */
	public void reevaluateSlotLimitDisplay()
	{
		SwingUtilities.invokeLater(() ->
		{
			PlayerSession session = plugin.getSession();
			if (session == null)
			{
				return;
			}

			boolean atLimit = !plugin.isPremium() && !session.hasAvailableGESlots(plugin.getFlipSlotLimit());

			if (atLimit)
			{
				showSlotLimitMessage();
			}
			else if (!currentRecommendations.isEmpty())
			{
				// Refresh recommendation display and status when a slot frees up
				int count = countDisplayed(currentRecommendations);
				String itemWord = count == 1 ? "suggestion" : "suggestions";
				FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
				String flipStyleText = selectedStyle != null ? selectedStyle.toString() : "Balanced";
				statusLabel.setText(String.format("%s | %d %s", flipStyleText, count, itemWord));

				populateRecommendations(new ArrayList<>(currentRecommendations));
				subscribeLabel.setVisible(!plugin.isPremium());
			}
		});
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
		String wrappedMessage = "<html><table width='160'><tr><td align='center'>" + message + "</td></tr></table></html>";
		container.add(createEmptyStatePanel(title, wrappedMessage, 80));
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

		updatePremiumStatus();
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
			if (!shouldDisplay(rec))
			{
				continue;
			}

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
		JPanel panel = CardWidgets.createBaseItemPanel(ColorScheme.DARKER_GRAY_COLOR, Integer.MAX_VALUE, true);

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
		JPanel detailsPanel = CardWidgets.createDetailsPanel(ColorScheme.DARKER_GRAY_COLOR);

		// Apply price offset to displayed values so they match Flip Assist
		int priceOffset = config.priceOffset();
		int displayBuyPrice = Math.max(1, rec.getRecommendedBuyPrice() + priceOffset);
		int displaySellPrice = Math.max(1, rec.getRecommendedSellPrice() - priceOffset);
		int displayMargin = displaySellPrice - displayBuyPrice;
		int geTax = GeTax.taxFor(rec.getItemId(), displaySellPrice);
		int displayProfit = (displayMargin - geTax) * rec.getRecommendedQuantity();
		int displayCost = displayBuyPrice * rec.getRecommendedQuantity();
		double displayRoi = displayBuyPrice > 0 ? ((double)(displayMargin - geTax) / displayBuyPrice) * 100 : 0;

		// Recommended Buy/Sell prices
		JLabel priceLabel = new JLabel(PanelFormat.formatBuySellText(displayBuyPrice, displaySellPrice));
		priceLabel.setForeground(Color.LIGHT_GRAY);
		priceLabel.setFont(FONT_PLAIN_12);

		// Quantity
		JLabel quantityLabel = new JLabel(String.format("Qty: %d (Limit: %d)",
			rec.getRecommendedQuantity(), rec.getBuyLimit()));
		quantityLabel.setForeground(new Color(200, 200, 255));
		quantityLabel.setFont(FONT_PLAIN_12);

		// Margin and ROI
		JLabel marginLabel = new JLabel(String.format("Margin: %s (%s ROI)",
			PanelFormat.formatGP(displayMargin - geTax), GpUtils.formatROI(displayRoi)));
		marginLabel.setForeground(COLOR_PROFIT_GREEN);
		marginLabel.setFont(FONT_PLAIN_12);

		// Potential profit and total cost
		JLabel profitLabel = new JLabel(PanelFormat.formatProfitCostText(displayProfit, displayCost));
		profitLabel.setForeground(new Color(255, 215, 0));
		profitLabel.setFont(FONT_PLAIN_12);

		// Volume info
		JLabel volumeLabel = new JLabel(PanelFormat.formatVolumeText(rec.getDailyVolume()));
		volumeLabel.setForeground(Color.CYAN);
		volumeLabel.setFont(FONT_PLAIN_12);

		// Risk info
		JLabel riskLabel = new JLabel(PanelFormat.formatRiskText(rec.getRiskScore(), rec.getRiskRating()));
		riskLabel.setForeground(PanelFormat.getRiskColor(rec.getRiskScore()));
		riskLabel.setFont(FONT_PLAIN_12);

		CardWidgets.addLabelsWithSpacing(detailsPanel, priceLabel, quantityLabel, marginLabel,
			profitLabel, volumeLabel, riskLabel);

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
		CardWidgets.setupIconLabel(iconLabel, itemImage);

		// Use HTML to allow text wrapping for long item names
		String escapedName = PanelFormat.escapeHtml(itemName);
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
	 * Create a clickable chart icon label that opens the item's page on the website.
	 * Uses a simple bar chart icon drawn with Java 2D graphics.
	 */
	private JLabel createChartIconLabel(int itemId)
	{
		java.awt.image.BufferedImage chartIcon = PanelFormat.drawChartIcon(new Color(100, 180, 255), new Color(150, 150, 150));

		JLabel chartLabel = new JLabel(new ImageIcon(chartIcon));
		chartLabel.setToolTipText("View price history on FlipSmart website");
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
				chartLabel.setIcon(new ImageIcon(PanelFormat.drawChartIcon(new Color(150, 220, 255), new Color(200, 200, 200))));
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
	 * Create a clickable block icon label that adds the item to a blocklist.
	 * Uses a ban/circle-slash icon drawn with Java 2D graphics.
	 */
	private JLabel createBlockIconLabel(int itemId, String itemName)
	{
		java.awt.image.BufferedImage blockIcon = PanelFormat.drawBlockIcon(new Color(180, 100, 100));

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
				blockLabel.setIcon(new ImageIcon(PanelFormat.drawBlockIcon(new Color(255, 100, 100))));
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
	 * Update liquidity label with data from analysis
	 */
	private void updateLiquidityLabel(JLabel label, FlipAnalysis.Liquidity liquidity)
	{
		label.setText(liquidity != null 
			? PanelFormat.formatLiquidityText(liquidity.getScore(), liquidity.getRating(), liquidity.getTotalVolumePerHour())
			: LIQUIDITY_NA);
	}

	/**
	 * Update risk label with data from analysis
	 */
	private void updateRiskLabel(JLabel label, FlipAnalysis.Risk risk)
	{
		if (risk != null && risk.getScore() != null)
		{
			label.setText(PanelFormat.formatRiskText(risk.getScore(), risk.getRating()));
			label.setForeground(PanelFormat.getRiskColor(risk.getScore()));
		}
		else
		{
			label.setText(RISK_NA);
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
	 * Get the current active flips.
	 *
	 * Returns a synchronized snapshot. The list is mutated on the EDT by
	 * refreshActiveFlips, but cross-thread callers (e.g. the GE slot-hover
	 * overlay, which runs on the render thread and reads via
	 * BuyPriceLookup.findAverageBuyPrice) need to see either the old
	 * complete list or the new complete list — never a half-populated
	 * intermediate state. Without this lock the overlay can intermittently
	 * see an empty list during the clear+addAll swap and silently hide the
	 * hover-state profit line. See issue #685 Bug 2.
	 */
	public List<ActiveFlip> getCurrentActiveFlips()
	{
		synchronized (currentActiveFlips)
		{
			return new ArrayList<>(currentActiveFlips);
		}
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
		synchronized (currentActiveFlips)
		{
			for (ActiveFlip flip : currentActiveFlips)
			{
				if (flip.getItemId() == itemId)
				{
					return true;
				}
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

		// Don't clear focus when auto-recommend is active — it manages its own focus
		AutoRecommendService service = plugin.getAutoRecommendService();
		if (service != null && service.isActive())
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

	public void setOfferDispositionLookup(java.util.function.IntFunction<OfferAdviceResponse> lookup)
	{
		this.offerDispositionLookup = lookup;
	}

	private static String activeOfferVerb(OfferAction action, Integer netProfitEstimate)
	{
		boolean isLoss = netProfitEstimate != null && netProfitEstimate < 0;
		boolean isProfit = netProfitEstimate != null && netProfitEstimate > 0;
		switch (action)
		{
			case MOVE_PRICE_DOWN:
				return "Move price down";
			case EXIT_AT_BREAKEVEN:
				if (isLoss)
				{
					return "Exit at a loss";
				}
				return isProfit ? "Take profit" : "Exit at breakeven";
			case EXIT_AT_LOSS:
				return "Exit at a loss";
			default:
				return "";
		}
	}

	private static String formatSignedGp(int amount)
	{
		return (amount >= 0 ? "+" : "-") + GpUtils.formatGP(Math.abs(amount));
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
		// Block new buy-side flips when free user has hit their slot limit
		PlayerSession session = plugin.getSession();
		if (session != null && !plugin.isPremium()
			&& !session.hasAvailableGESlots(plugin.getFlipSlotLimit()))
		{
			return;
		}

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
		// Prefer session — it carries any sell-price adjustments. The displayed
		// cache is only a fallback when session hasn't been populated yet.
		PlayerSession session = plugin.getSession();
		Integer sessionPrice = session != null ? session.getRecommendedPrice(flip.getItemId()) : null;
		Integer cachedSellPrice = (sessionPrice != null && sessionPrice > 0)
			? sessionPrice
			: displayedSellPrices.get(flip.getItemId());
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
				Integer smartSellPrice = SmartSellPricer.calculateSmartSellPrice(flip, currentMarketPrice);
				int sellPrice = smartSellPrice != null ? smartSellPrice : SmartSellPricer.calculateMinProfitableSellPrice(flip.getAverageBuyPrice());
				
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
	private void setFocus(PendingOrder pending, JPanel panel)
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
		// Manual pick overrides the auto-mode offer-screen lock.
		AutoRecommendService autoService = plugin.getAutoRecommendService();
		if (autoService != null)
		{
			autoService.releaseOfferLock();
		}

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
		
		log.debug("Set Flip Assist focus: {} - {} at {} gp x{}", 
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
		JPanel panel = CardWidgets.createBaseItemPanel(ColorScheme.DARKER_GRAY_COLOR, 180, true);

		// Top section: Item icon and name
		HeaderPanels header = createItemHeaderPanels(flip.getItemId(), flip.getItemName(), ColorScheme.DARKER_GRAY_COLOR);
		JPanel topPanel = header.topPanel;
		JPanel namePanel = header.namePanel;

		// Details section using BoxLayout for vertical rows
		JPanel detailsPanel = CardWidgets.createDetailsPanel(ColorScheme.DARKER_GRAY_COLOR);

		// Row 1: Buy: X | Sell: Y (placeholders until data loads)
		JLabel pricesLabel = CardWidgets.createStyledLabel(
			String.format("Buy: %s | Sell: ...", PanelFormat.formatGPExact(flip.getAverageBuyPrice())), Color.WHITE);

		// Row 2: Qty: X (Limit: Y)
		JLabel qtyLabel = CardWidgets.createStyledLabel(
			String.format("Qty: %d (Limit: ...)", flip.getTotalQuantity()), COLOR_TEXT_GRAY);

		// Row 3: Tax = Z
		JLabel taxLabel = CardWidgets.createStyledLabel("Tax = ...", Color.CYAN);

		// Row 4: Margin: X (Y% ROI)
		JLabel marginLabel = CardWidgets.createStyledLabel("Margin: ...", COLOR_YELLOW);

		// Row 5: Profit: X | Cost: Y
		JLabel profitCostLabel = CardWidgets.createStyledLabel(
			String.format("Profit: ... | Cost: %s", PanelFormat.formatGP(flip.getTotalInvested())), COLOR_PROFIT_GREEN);

		// Row 6: Liquidity: X (Rating) | Y/hr
		JLabel liquidityLabel = CardWidgets.createStyledLabel("Liquidity: ...", Color.CYAN);

		// Row 7: Risk: X (Rating)
		JLabel riskLabel = CardWidgets.createStyledLabel("Risk: ...", COLOR_YELLOW);

		// Add all rows with small spacing
		CardWidgets.addLabelsWithSpacing(detailsPanel, pricesLabel, qtyLabel, taxLabel, marginLabel,
			profitCostLabel, liquidityLabel, riskLabel);

		if (offerDispositionLookup != null)
		{
			OfferAdviceResponse advice = offerDispositionLookup.apply(flip.getItemId());
			String verb = advice != null && advice.getActionEnum() != null
				? activeOfferVerb(advice.getActionEnum(), advice.getNetProfitEstimate())
				: "";
			if (!verb.isEmpty())
			{
				detailsPanel.add(Box.createRigidArea(new Dimension(0, 2)));

				String verbLine = advice.getNewPrice() != null
					? verb + ": " + String.format("%,d", advice.getNewPrice()) + "gp"
					: verb;
				JLabel verbLabel = CardWidgets.createStyledLabel(verbLine, Color.ORANGE);
				verbLabel.setToolTipText(advice.getReason());
				detailsPanel.add(verbLabel);

				if (advice.getNetProfitEstimate() != null)
				{
					int net = advice.getNetProfitEstimate();
					String keyword = net < 0 ? "Loss" : (net > 0 ? "Profit" : "Breakeven");
					Color netColor = net < 0 ? new Color(255, 100, 100) : new Color(80, 255, 120);
					detailsPanel.add(CardWidgets.createStyledLabel(keyword + ": " + formatSignedGp(net), netColor));
				}
			}
		}

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
				
				// Session is the source of truth for sell price (initial smart-sell or
				// any subsequent adjustment writes there). Only fall through to the
				// freshly-computed smart-sell when the session has nothing yet.
				PlayerSession session = plugin.getSession();
				Integer sessionPrice = session != null ? session.getRecommendedPrice(flip.getItemId()) : null;
				Integer smartSellPrice = (sessionPrice != null && sessionPrice > 0)
					? sessionPrice
					: SmartSellPricer.calculateSmartSellPrice(flip, currentMarketPrice);
				boolean pastThreshold = SmartSellPricer.shouldUseLossMinimizingPrice(flip, dailyVolume);

				if (smartSellPrice != null && smartSellPrice > 0)
				{
					if ((sessionPrice == null || sessionPrice <= 0) && session != null)
					{
						session.setRecommendedPrice(flip.getItemId(), smartSellPrice);
					}
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
							CardWidgets.applyPriceIndicatorBackground(panel, topPanel, namePanel, detailsPanel, priceIndicatorBg);
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
						PanelFormat.formatGPExact(flip.getAverageBuyPrice()),
						PanelFormat.formatGPExact(smartSellPrice),
						priceSuffix));

					int geTax = GeTax.taxFor(flip.getItemId(), smartSellPrice);

					// Calculate margin and profit
					int marginPerItem = smartSellPrice - flip.getAverageBuyPrice() - geTax;
					int totalProfit = marginPerItem * flip.getTotalQuantity();
					double roi = (marginPerItem * 100.0) / flip.getAverageBuyPrice();
					
					// Row 2: Update Qty (Limit) | Tax
					String limitText = buyLimit != null ? String.valueOf(buyLimit) : "?";
					qtyLabel.setText(String.format("Qty: %d (Limit: %s)", flip.getTotalQuantity(), limitText));
					taxLabel.setText(String.format("Tax = %s", PanelFormat.formatGP(geTax * flip.getTotalQuantity())));
					
					// Row 3: Update Margin with ROI (show warning color if not profitable)
					marginLabel.setText(PanelFormat.formatMarginText(marginPerItem, roi, totalProfit <= 0));
					marginLabel.setForeground(totalProfit <= 0 ? COLOR_LOSS_RED : COLOR_YELLOW);
					
					// Row 4: Update Profit | Cost - use cyan for higher sell, orange for lower
					profitCostLabel.setText(PanelFormat.formatProfitCostText(totalProfit, flip.getTotalInvested()));
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
					pricesLabel.setText(PanelFormat.formatBuySellText(flip.getAverageBuyPrice(), null));
					marginLabel.setText("Margin: N/A");
					profitCostLabel.setText(String.format("Profit: N/A | Cost: %s", PanelFormat.formatGP(flip.getTotalInvested())));
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
					Color baseColor = PanelFormat.getBaseBackgroundColor(panel, ColorScheme.DARKER_GRAY_COLOR);
					Color hoverColor = PanelFormat.brightenColor(baseColor, 15);
					CardWidgets.setPanelBackgrounds(hoverColor, panel, topPanel, namePanel, detailsPanel);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != flip.getItemId() 
					|| !currentFocus.isSelling())
				{
					// Restore the base color (may be price indicator color)
					Color baseColor = PanelFormat.getBaseBackgroundColor(panel, ColorScheme.DARKER_GRAY_COLOR);
					CardWidgets.setPanelBackgrounds(baseColor, panel, topPanel, namePanel, detailsPanel);
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
	private JPanel createPendingOrderPanel(PendingOrder pending)
	{
		Color bgColor = new Color(55, 55, 65); // Slightly different color for pending
		JPanel panel = CardWidgets.createBaseItemPanel(bgColor, 180, false);

		// Top section: Item icon and name
		HeaderPanels header = createItemHeaderPanels(pending.itemId, pending.itemName, bgColor);
		JPanel topPanel = header.topPanel;
		JPanel namePanel = header.namePanel;

		// Details section using BoxLayout for vertical rows
		JPanel detailsPanel = CardWidgets.createDetailsPanel(bgColor);

		// Row 1: Buy: X | Sell: Y (with placeholders until data loads)
		String sellText = pending.recommendedSellPrice != null && pending.recommendedSellPrice > 0
			? PanelFormat.formatGPExact(pending.recommendedSellPrice) : "...";
		JLabel pricesLabel = CardWidgets.createStyledLabel(
			String.format(FORMAT_BUY_SELL, PanelFormat.formatGPExact(pending.pricePerItem), sellText), Color.WHITE);

		// Row 2: Qty: X/Y (Limit: Z)
		JLabel qtyLabel = CardWidgets.createStyledLabel(
			String.format("Qty: %d/%d (Limit: ...)", pending.quantityFilled, pending.quantity), COLOR_TEXT_GRAY);

		// Row 3: Tax = W
		JLabel taxLabel = CardWidgets.createStyledLabel("Tax = ...", Color.CYAN);

		// Row 4: Margin: X (Y% ROI)
		JLabel marginLabel = CardWidgets.createStyledLabel("Margin: ...", COLOR_YELLOW);

		// Row 5: Profit: X | Cost: Y
		int potentialInvestment = pending.quantity * pending.pricePerItem;
		JLabel profitCostLabel = CardWidgets.createStyledLabel(
			String.format("Profit: ... | Cost: %s", PanelFormat.formatGP(potentialInvestment)), COLOR_PROFIT_GREEN);

		// Row 6: Liquidity: X (Rating) | Y/hr
		JLabel liquidityLabel = CardWidgets.createStyledLabel("Liquidity: ...", Color.CYAN);

		// Row 7: Risk: X (Rating)
		JLabel riskLabel = CardWidgets.createStyledLabel("Risk: ...", COLOR_YELLOW);

		// Add all rows with small spacing
		CardWidgets.addLabelsWithSpacing(detailsPanel, pricesLabel, qtyLabel, taxLabel, marginLabel, 
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
					pricesLabel.setText(PanelFormat.formatBuySellText(pending.pricePerItem, sellPrice));

					int geTax = GeTax.taxFor(pending.itemId, sellPrice);

					// Calculate margin and profit
					int marginPerItem = sellPrice - pending.pricePerItem - geTax;
					int totalProfit = marginPerItem * pending.quantity;
					double roi = (marginPerItem * 100.0) / pending.pricePerItem;
					
					// Row 2: Update Qty (Limit) | Tax
					String limitText = buyLimit != null ? String.valueOf(buyLimit) : "?";
					qtyLabel.setText(String.format("Qty: %d/%d (Limit: %s)", 
						pending.quantityFilled, pending.quantity, limitText));
					taxLabel.setText(String.format("Tax = %s", PanelFormat.formatGP(geTax * pending.quantity)));
					
					// Row 3: Update Margin
					marginLabel.setText(PanelFormat.formatMarginText(marginPerItem, roi, totalProfit <= 0));
					marginLabel.setForeground(totalProfit <= 0 ? COLOR_LOSS_RED : COLOR_YELLOW);
					
					// Row 4: Update Profit | Cost
					profitCostLabel.setText(PanelFormat.formatProfitCostText(totalProfit, potentialInvestment));
					profitCostLabel.setForeground(totalProfit > 0 ? COLOR_PROFIT_GREEN : COLOR_LOSS_RED);
				}
				else
				{
					pricesLabel.setText(PanelFormat.formatBuySellText(pending.pricePerItem, null));
					marginLabel.setText("Margin: N/A");
					profitCostLabel.setText(String.format("Profit: N/A | Cost: %s", PanelFormat.formatGP(potentialInvestment)));
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
					CardWidgets.setPanelBackgrounds(new Color(65, 65, 75), panel, topPanel, namePanel, detailsPanel);
				}
			}
			
			@Override
			public void mouseExited(MouseEvent e)
			{
				if (currentFocus == null || currentFocus.getItemId() != pending.itemId)
				{
					CardWidgets.setPanelBackgrounds(bgColor, panel, topPanel, namePanel, detailsPanel);
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
		JPanel panel = CardWidgets.createBaseItemPanel(backgroundColor, 110, false);

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

		JLabel buyPriceLabel = new JLabel(String.format("Buy: %s", PanelFormat.formatGPExact(flip.getBuyPricePerItem())));
		buyPriceLabel.setForeground(COLOR_BUY_RED);
		buyPriceLabel.setFont(FONT_PLAIN_12);

		// Row 2: Invested and Sell Price
		JLabel investedLabel = new JLabel(String.format("Cost: %s", PanelFormat.formatGP(flip.getBuyTotal())));
		investedLabel.setForeground(COLOR_TEXT_GRAY);
		investedLabel.setFont(FONT_PLAIN_12);

		JLabel sellPriceLabel = new JLabel(String.format(FORMAT_SELL, PanelFormat.formatGPExact(flip.getSellPricePerItem())));
		sellPriceLabel.setForeground(COLOR_SELL_GREEN);
		sellPriceLabel.setFont(FONT_PLAIN_12);

		// Row 3: Net Profit and ROI
		JLabel profitLabel = new JLabel(String.format("Profit: %s", PanelFormat.formatGP(flip.getNetProfit())));
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
					JLabel taxLabel = new JLabel(String.format("GE Tax: %s", PanelFormat.formatGP(flip.getGeTax())));
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

			// Wire skip-exhausted callback to fetch fresh recommendations
			service.setOnQueueExhausted(() -> refreshRecommendations(true));

			service.start(new ArrayList<>(currentRecommendations));

			// Check if start() actually activated the service (it may bail if all items are filtered)
			if (!service.isActive())
			{
				autoRecommendButton.setSelected(false);
				return;
			}

			// Switch to Recommended tab
			tabbedPane.setSelectedIndex(0);

			// Start the 2-minute refresh timer
			plugin.startAutoRecommendRefreshTimer();

			autoRecommendButton.setBackground(COLOR_AUTO_RECOMMEND_ACTIVE);
			autoRecommendButton.setForeground(Color.WHITE);
			autoRecommendButton.setText("Auto");
			skipButton.setVisible(true);
			log.debug("Auto-recommend enabled with {} recommendations", currentRecommendations.size());
		}
		else
		{
			// Stopping auto-recommend
			service.stop();
			plugin.stopAutoRecommendRefreshTimer();

			autoRecommendButton.setBackground(null);
			autoRecommendButton.setForeground(Color.WHITE);
			autoRecommendButton.setText("Auto");
			skipButton.setVisible(false);
			autoRecommendStatusLabel.setVisible(false);

			// Repaint recommendations to remove highlight
			populateRecommendations(new ArrayList<>(currentRecommendations));

			log.debug("Auto-recommend disabled");
		}
	}

	/**
	 * Show or hide the auto-recommend button based on config setting.
	 */
	public void setAutoRecommendVisible(boolean visible)
	{
		SwingUtilities.invokeLater(() -> {
			autoRecommendButton.setVisible(visible);
			if (!visible && autoRecommendButton.isSelected())
			{
				autoRecommendButton.setSelected(false);
				AutoRecommendService service = plugin.getAutoRecommendService();
				if (service != null && service.isActive())
				{
					service.stop();
					plugin.stopAutoRecommendRefreshTimer();
				}
				skipButton.setVisible(false);
				autoRecommendStatusLabel.setVisible(false);
			}
		});
	}

	/**
	 * Update the auto-recommend button state (e.g., when service is stopped externally).
	 */
	public void updateAutoRecommendButton(boolean active)
	{
		SwingUtilities.invokeLater(() -> {
			autoRecommendButton.setSelected(active);
			skipButton.setVisible(active);
			if (active)
			{
				autoRecommendButton.setBackground(COLOR_AUTO_RECOMMEND_ACTIVE);
				autoRecommendButton.setForeground(Color.WHITE);
				autoRecommendButton.setText("Auto");
			}
			else
			{
				autoRecommendButton.setBackground(null);
				autoRecommendButton.setForeground(Color.WHITE);
				autoRecommendButton.setText("Auto");
				autoRecommendStatusLabel.setVisible(false);
			}
		});
	}
}

