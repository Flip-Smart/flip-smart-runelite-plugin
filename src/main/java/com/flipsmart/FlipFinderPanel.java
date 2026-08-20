package com.flipsmart;

import com.flipsmart.api.dto.Dtos.ActiveFlipsResponse;
import com.flipsmart.api.dto.Dtos.BlocklistSummary;
import com.flipsmart.api.dto.Dtos.CompletedFlipsResponse;
import com.flipsmart.api.dto.Dtos.FavoriteItem;
import com.flipsmart.api.dto.Dtos.FlipFinderResponse;
import com.flipsmart.api.dto.Dtos.FlipStatisticsResponse;
import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.flip.CompletedFlip;
import com.flipsmart.domain.flip.FlipAnalysis;
import com.flipsmart.domain.flip.FlipRecommendation;
import com.flipsmart.domain.offer.OfferTransition;
import com.flipsmart.domain.offer.PendingOrder;
import com.flipsmart.exit.ExitTradesDialog;
import com.flipsmart.recommend.SmartSellPricer;
import com.flipsmart.session.OpenPositions;
import com.flipsmart.session.SessionClock;
import com.flipsmart.session.SessionStats;
import com.flipsmart.session.SessionStatsView;
import com.flipsmart.trading.ActiveFlipCardMetrics;
import com.flipsmart.trading.RealizedFlipProfit;
import com.flipsmart.ui.panel.CardWidgets;
import com.flipsmart.ui.panel.FavoritesSort;
import com.flipsmart.ui.panel.ItemNameFit;
import com.flipsmart.ui.panel.LoginPanel;
import com.flipsmart.ui.panel.Paginator;
import com.flipsmart.ui.panel.PanelFormat;
import com.flipsmart.util.GeTax;
import com.flipsmart.util.GpUtils;
import com.flipsmart.util.TimeUtils;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

import java.awt.*;
import javax.swing.*;

@Slf4j
public class FlipFinderPanel extends PluginPanel
{
	// Configuration constants
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String CONFIG_KEY_FLIP_STYLE = "flipStyle";
	private static final String CONFIG_KEY_FLIP_TIMEFRAME = "flipTimeframe";
	private static final String CONFIG_KEY_COMPLETED_SORT = "completedSort";
	private static final String CONFIG_KEY_FAVORITES_SORT = "favoritesSort";
	private static final String CONFIG_KEY_FLIP_ONLY_FAVORITES = "flipOnlyFavorites";
	private static final int FAVORITES_PAGE_SIZE = 10;
	private static final String CONFIG_KEY_MIN_PROFIT = "minProfit";
	private static final String CONFIG_KEY_MIN_VOLUME = "minVolume";
	private static final String CONFIG_KEY_ENABLE_FLIP_ASSISTANT = "enableFlipAssistant";
	private static final String CONFIG_KEY_SESSION_STATS_COLLAPSED = "sessionStatsCollapsed";
	private static final String FILTER_TOOLTIP =
		"Adding a minimum filter may limit the number of results you see.";

	/** Sort options for the Completed tab. Recency is the default (AC9). */
	private enum CompletedSort
	{
		RECENCY("Recency"),
		PROFIT("Profit");

		private final String label;

		CompletedSort(String label)
		{
			this.label = label;
		}
	}

	// Constants for duplicated literals
	private static final String FONT_ARIAL = "Arial";
	private static final String ERROR_PREFIX = "Error: ";
	private static final String FORMAT_QTY = "Qty: %d";
	private static final String FORMAT_SELL = "Sell: %s";
	private static final String FORMAT_ROI = "ROI: %.1f%%";
	private static final String ERROR_DIALOG_TITLE = "Error";
	private static final String LIQUIDITY_NA = "Liquidity: N/A";
	private static final String RISK_NA = "Risk: N/A";
	private static final String MSG_LOGIN_TO_RUNESCAPE = "Log in to RuneScape";
	private static final String MSG_LOGIN_INSTRUCTION = "<html><center>Log in to the game to get<br>flip suggestions and track your flips</center></html>";
	private static final long SETTINGS_POPOUT_REOPEN_DEBOUNCE_MS = 200;
	private static final int FILTER_SETTING_DEBOUNCE_MS = 300;

	private final Map<String, Timer> filterSettingDebounceTimers = new ConcurrentHashMap<>();

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

	private static final Color STAR_ON_COLOR = new Color(255, 175, 45);
	private static final Color STAR_OFF_COLOR = new Color(120, 120, 120);

	// Yellow used for the completed-flip anomaly badge (HTML label markup).
	private static final String COLOR_ANOMALY_HEX = "#FFCC33";
	private static final int BADGE_SECOND_LINE_HEIGHT = 18;

	// Website base URL for item pages
	private static final String WEBSITE_ITEM_URL = "https://flipsmart.net/items/";

	// Deep-links a flagged flip to the website's Flip History edit flow.
	private static final String FLIP_EDIT_URL = "https://flipsmart.net/dashboard/flips?adjust=";

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
	private static final Font FONT_BOLD_16 = new Font(FONT_ARIAL, Font.BOLD, 16);

	private static final int ACTIVE_FLIPS_PRICE_REFRESH_MS = 60_000;
	static final int TAB_RECOMMENDED = 0;
	static final int TAB_ACTIVE_FLIPS = 1;
	static final int TAB_COMPLETED = 2;

	private final transient FlipSmartConfig config;
	private final transient FlipSmartApiClient apiClient;
	private final transient ItemManager itemManager;
	private final transient ConfigManager configManager;
	private final JPanel recommendedListContainer = new JPanel();
	private final JPanel favoritesListContainer = new JPanel();
	private final JPanel activeFlipsListContainer = new JPanel();
	private final JPanel completedFlipsListContainer = new JPanel();
	private final JLabel statusLabel = new JLabel("Loading...");
	private final JButton refreshButton = new JButton("Refresh");
	private final JComboBox<FlipSmartConfig.FlipStyle> flipStyleDropdown;
	private final JComboBox<FlipSmartConfig.FlipTimeframe> flipTimeframeDropdown;
	private final List<FlipRecommendation> currentRecommendations = new ArrayList<>();
	private final List<FavoriteItem> currentFavorites = new ArrayList<>();
	// Backend active-flips list keyed by itemId: an enrichment lookup for the
	// store-derived projection, not the render source. Guarded by its own monitor.
	private final Map<Integer, ActiveFlip> enrichmentByItemId = new java.util.HashMap<>();
	private final List<CompletedFlip> currentCompletedFlips = new ArrayList<>();
	private final JTabbedPane tabbedPane = new JTabbedPane();
	private final SessionClock sessionClock = new SessionClock(System.currentTimeMillis());
	private final SessionStatsView sessionStatsView = new SessionStatsView();
	private Timer sessionStatsTimer;
	private SessionStats.ProfitBase sessionProfitBase = SessionStats.ProfitBase.EMPTY;
	private final transient FlipSmartPlugin plugin;  // Reference to plugin to store recommended prices
	
	// Scroll panes for preserving scroll position during refresh
	private JScrollPane recommendedScrollPane;
	private JScrollPane favoritesScrollPane;
	private JScrollPane activeFlipsScrollPane;
	private JScrollPane completedFlipsScrollPane;

	// Completed tab sort controls
	private CompletedSort completedSort = CompletedSort.RECENCY;
	private final Map<CompletedSort, JLabel> completedSortTabs = new java.util.EnumMap<>(CompletedSort.class);

	// Favorites tab sort + pagination controls
	private FavoritesSort favoritesSort = FavoritesSort.PROFIT;
	private int favoritesPage = 0;
	private final Map<FavoritesSort, JLabel> favoritesSortTabs = new java.util.EnumMap<>(FavoritesSort.class);
	private JLabel favoritesPageLabel;
	private JLabel favoritesPrevPageLabel;
	private JLabel favoritesNextPageLabel;
	private JPanel favoritesPaginationRow;

	// In-tab favorites view (toggled from within the Recommended tab, no separate tab)
	private boolean favoritesViewActive;
	private JButton favoritesToggleButton;
	private JPanel favoritesControlsPanel;
	private JPanel recommendedCardPanel;
	private final CardLayout recommendedCardLayout = new CardLayout();
	private static final String CARD_ALGORITHM = "algorithm";
	private static final String CARD_FAVORITES = "favorites";

	// Premium-gated "flip only from favorites" toggle
	private boolean flipOnlyFavorites;
	private JCheckBox flipOnlyFavoritesCheck;

	// Login / auth subsystem (UI construction, credential + device-auth flow)
	private transient LoginPanel loginPanel;
	private JPanel mainPanel;

	// Premium subscribe message (shown for non-premium users)
	private JLabel subscribeLabel;

	// Callback for when authentication completes (to sync RSN)
	@Setter
	private transient Runnable onAuthSuccess;

	// Flip Assist focus tracking
	@Getter
	private transient FocusedFlip currentFocus = null;
	private transient JPanel currentFocusedPanel = null;
	private transient int currentFocusedItemId = -1;
	@Setter
	private transient Consumer<FocusedFlip> onFocusChanged;

	/** Refresh closures for the cards currently on the Active Flips tab; rebuilt with the list. */
	private final transient List<Runnable> activeFlipCardRefreshers = new ArrayList<>();
	private transient Timer activeFlipsPriceTimer;

	// Cache displayed sell prices to ensure focus uses same price as shown in UI
	// Key: itemId, Value: calculated sell price shown in the active flip panel
	private final Map<Integer, Integer> displayedSellPrices = new ConcurrentHashMap<>();

	// Auto-recommend UI
	private JToggleButton autoRecommendButton;
	private JButton skipButton;
	private JLabel autoRecommendStatusLabel;

	// Recommendation refresh countdown — fixed-interval, top-right, low-emphasis.
	// The next-refresh deadline itself lives on the PluginScheduler (the single source
	// of truth shared with the actual refresh trigger); this ticker only renders it.
	private JLabel refreshCountdownLabel;
	private transient Timer refreshCountdownTimer;

	// Cashstack override indicator (AC3) — shows the active override or an error
	private JLabel cashstackOverrideLabel;

	// Cache blocklists for quick access when blocking items
	private final java.util.concurrent.CopyOnWriteArrayList<BlocklistSummary> cachedBlocklists = new java.util.concurrent.CopyOnWriteArrayList<>();
	private volatile long blocklistCacheTimestamp = 0;
	private static final long BLOCKLIST_CACHE_TTL_MS = 5L * 60 * 1000; // 5 minutes

	// Favorited item ids, hydrated from the sync payload. Card rebuilds are full removeAll(),
	// so star fill is read from here at build time rather than stored on the widget.
	private final Set<Integer> favoriteItemIds = new java.util.concurrent.CopyOnWriteArraySet<>();

	public FlipFinderPanel(FlipSmartConfig config, FlipSmartApiClient apiClient, ItemManager itemManager, FlipSmartPlugin plugin, ConfigManager configManager)
	{
		super(false);
		this.config = config;
		this.apiClient = apiClient;
		this.itemManager = itemManager;
		this.plugin = plugin;
		this.configManager = configManager;

		// Restore the persisted Completed-tab sort (defaults to Recency)
		this.completedSort = loadCompletedSort();

		// Restore the persisted Favorites-tab sort (defaults to Profit)
		this.favoritesSort = loadFavoritesSort();

		// Restore the persisted "flip only from favorites" toggle
		this.flipOnlyFavorites = loadFlipOnlyFavorites();

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
	/** A footer link label that opens {@code url} in the system browser. */
	private static JLabel externalLink(String text, Color colour, String tooltip, String url)
	{
		JLabel link = CardWidgets.label(text, colour, new Font(FONT_ARIAL, Font.PLAIN, 14));
		link.setCursor(new Cursor(Cursor.HAND_CURSOR));
		link.setToolTipText(tooltip);
		onIconClick(link, () -> LinkBrowser.browse(url));
		return link;
	}

	/**
	 * Brand-styled renderer for the two settings dropdowns: orange when selected, dark
	 * otherwise, with {@code label} supplying the display text for values of {@code type}.
	 */
	private static <T> void applyDropdownRenderer(JComboBox<?> dropdown, Class<T> type,
		Function<T, String> label)
	{
		dropdown.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean isSelected, boolean cellHasFocus)
			{
				Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (c instanceof JLabel && type.isInstance(value))
				{
					((JLabel) c).setText(label.apply(type.cast(value)));
				}
				c.setBackground(isSelected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
				c.setForeground(Color.WHITE);
				return c;
			}
		});
	}

	/**
	 * A tab's vertical list container wrapped in its scroll pane. All four tabs are
	 * styled identically, and the vertical bar is always shown so the layout does not
	 * shift the moment a list grows past one screen.
	 */
	private static JScrollPane listScrollPane(JPanel container)
	{
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane pane = new JScrollPane(container);
		pane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		pane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		pane.setBorder(BorderFactory.createEmptyBorder());
		pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		return pane;
	}

	private void buildMainPanel()
	{
		mainPanel = new JPanel(new BorderLayout());
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header: brand title + website link stacked on the left, buttons on the right
		JPanel headerPanel = CardWidgets.panel(new BorderLayout(), ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JPanel titleBox = new JPanel();
		titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
		titleBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel titleLabel = CardWidgets.label("FlipSmart", Color.WHITE, FONT_BOLD_16);
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel brandLink = buildWebsiteLink();
		brandLink.setAlignmentX(Component.LEFT_ALIGNMENT);

		titleBox.add(titleLabel);
		titleBox.add(brandLink);

		// Logout button with compact styling
		JButton logoutButton = new JButton("Logout");
		logoutButton.setFocusable(false);
		logoutButton.setFont(FONT_PLAIN_11);
		logoutButton.setMargin(new Insets(2, 4, 2, 4));
		logoutButton.addActionListener(e -> handleLogout());

		// Refresh button with compact styling
		refreshButton.setFocusable(false);
		refreshButton.setFont(FONT_PLAIN_11);
		refreshButton.setMargin(new Insets(2, 4, 2, 4));
		refreshButton.addActionListener(e -> manualRefresh());

		JButton settingsButton = new JButton(new ImageIcon(PanelFormat.icon("gear")));
		settingsButton.setFocusable(false);
		settingsButton.setFont(FONT_PLAIN_11);
		settingsButton.setMargin(new Insets(1, 2, 1, 2));
		settingsButton.setToolTipText("Quick settings");
		settingsButton.addActionListener(e -> showSettingsPopout(settingsButton));

		JPanel headerButtons = CardWidgets.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0), ColorScheme.DARKER_GRAY_COLOR);
		headerButtons.add(logoutButton);
		headerButtons.add(refreshButton);
		headerButtons.add(settingsButton);

		headerPanel.add(titleBox, BorderLayout.WEST);
		headerPanel.add(headerButtons, BorderLayout.EAST);

		// Low-emphasis refresh countdown, right-aligned beneath the header buttons
		JPanel countdownRow = CardWidgets.panel(new FlowLayout(FlowLayout.RIGHT, 4, 0), ColorScheme.DARKER_GRAY_COLOR);
		refreshCountdownLabel = new JLabel();
		refreshCountdownLabel.setIcon(new ImageIcon(PanelFormat.icon("clock")));
		refreshCountdownLabel.setForeground(COLOR_TEXT_DIM_GRAY);
		refreshCountdownLabel.setFont(new Font(FONT_ARIAL, Font.ITALIC, 10));
		countdownRow.add(refreshCountdownLabel);
		headerPanel.add(countdownRow, BorderLayout.SOUTH);

		// Controls panel (flip style dropdown)
		JPanel controlsPanel = CardWidgets.panel(new BorderLayout(), ColorScheme.DARKER_GRAY_COLOR);
		controlsPanel.setBorder(new EmptyBorder(5, 10, 5, 10));

		JLabel flipStyleLabel = CardWidgets.label("Style: ", Color.LIGHT_GRAY, FONT_PLAIN_12);

		applyDropdownRenderer(flipStyleDropdown, FlipSmartConfig.FlipStyle.class,
			style -> style.name().charAt(0) + style.name().substring(1).toLowerCase());

		applyDropdownRenderer(flipTimeframeDropdown, FlipSmartConfig.FlipTimeframe.class,
			FlipSmartConfig.FlipTimeframe::toString);

		JLabel flipTimeframeLabel = CardWidgets.label("Timeframe: ", Color.LIGHT_GRAY, FONT_PLAIN_12);

		// Row 1: Style dropdown (left) + Auto button (right)
		JPanel styleRow = CardWidgets.panel(new BorderLayout(), ColorScheme.DARKER_GRAY_COLOR);

		JPanel styleLeft = CardWidgets.panel(new FlowLayout(FlowLayout.LEFT, 5, 0), ColorScheme.DARKER_GRAY_COLOR);
		styleLeft.add(flipStyleLabel);
		styleLeft.add(flipStyleDropdown);

		// Auto-recommend toggle button
		autoRecommendButton = new JToggleButton("Auto") {
			@Override
			protected void paintComponent(Graphics g)
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
			resetRefreshCadence(); // AC19: Skip restarts the refresh cadence
		});
		skipButton.setVisible(false);

		JPanel styleRight = CardWidgets.panel(new FlowLayout(FlowLayout.RIGHT, 5, 0), ColorScheme.DARKER_GRAY_COLOR);
		styleRight.add(autoRecommendButton);

		styleRow.add(styleLeft, BorderLayout.WEST);
		styleRow.add(styleRight, BorderLayout.EAST);

		// Row 2: Timeframe dropdown (left) + Skip button (right)
		JPanel timeframeRow = CardWidgets.panel(new BorderLayout(), ColorScheme.DARKER_GRAY_COLOR);

		JPanel timeframeLeft = CardWidgets.panel(new FlowLayout(FlowLayout.LEFT, 5, 0), ColorScheme.DARKER_GRAY_COLOR);
		timeframeLeft.add(flipTimeframeLabel);
		timeframeLeft.add(flipTimeframeDropdown);

		JPanel timeframeRight = CardWidgets.panel(new FlowLayout(FlowLayout.RIGHT, 5, 0), ColorScheme.DARKER_GRAY_COLOR);
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
		JPanel statusPanel = CardWidgets.panel(new BorderLayout(), ColorScheme.DARKER_GRAY_COLOR);
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
		JPanel overridePanel = CardWidgets.panel(new BorderLayout(), ColorScheme.DARKER_GRAY_COLOR);
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
		topPanel.add(sessionStatsView.getComponent());
		// Restore the persisted collapsed state, then persist future toggles.
		sessionStatsView.setCollapsed(config.sessionStatsCollapsed());
		sessionStatsView.setToggleListener(collapsed ->
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SESSION_STATS_COLLAPSED, collapsed));
		updateCashstackOverrideIndicator();

		// Recommended flips list container
		recommendedScrollPane = listScrollPane(recommendedListContainer);

		// Favorites list container
		favoritesScrollPane = listScrollPane(favoritesListContainer);

		// Active flips list container
		activeFlipsScrollPane = listScrollPane(activeFlipsListContainer);

		// Completed flips list container
		completedFlipsScrollPane = listScrollPane(completedFlipsListContainer);

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
			protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
											  int x, int y, int w, int h, boolean isSelected) {
				// Paint background for tabs
				g.setColor(isSelected ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
				g.fillRect(x, y, w, h);
			}
			
			@Override
			protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
										  int x, int y, int w, int h, boolean isSelected) {
				// Paint border/underline for selected tab
				if (isSelected) {
					g.setColor(ColorScheme.BRAND_ORANGE);
					g.fillRect(x, y + h - 3, w, 3);
				}
			}
			
			@Override
			protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
				// Don't paint content border
			}
		});
		
		// The Recommended tab swaps between the algorithm list and the favorites list via a
		// centered toggle button, so the favorites view no longer needs its own tab.
		JPanel toggleBar = CardWidgets.panel(new FlowLayout(FlowLayout.CENTER, 0, 4), ColorScheme.DARKER_GRAY_COLOR);
		favoritesToggleButton = new JButton("★ Favorites");
		favoritesToggleButton.setFocusable(false);
		favoritesToggleButton.setFont(FONT_PLAIN_11);
		favoritesToggleButton.setMargin(new Insets(2, 10, 2, 10));
		favoritesToggleButton.setForeground(STAR_ON_COLOR);
		favoritesToggleButton.setToolTipText("Toggle between algorithm recommendations and your favorites");
		favoritesToggleButton.addActionListener(e -> toggleFavoritesView());
		toggleBar.add(favoritesToggleButton);

		// Favorites controls (sort row + flip-only checkbox), shown only in favorites view
		favoritesControlsPanel = new JPanel();
		favoritesControlsPanel.setLayout(new BoxLayout(favoritesControlsPanel, BoxLayout.Y_AXIS));
		favoritesControlsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		favoritesControlsPanel.add(buildFavoritesSortRow());
		favoritesControlsPanel.setVisible(false);

		JPanel recommendedControls = new JPanel();
		recommendedControls.setLayout(new BoxLayout(recommendedControls, BoxLayout.Y_AXIS));
		recommendedControls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		recommendedControls.add(toggleBar);
		recommendedControls.add(favoritesControlsPanel);

		recommendedCardPanel = new JPanel(recommendedCardLayout);
		recommendedCardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		recommendedCardPanel.add(recommendedScrollPane, CARD_ALGORITHM);
		recommendedCardPanel.add(favoritesScrollPane, CARD_FAVORITES);

		JPanel recommendedTabPanel = CardWidgets.panel(new BorderLayout(), ColorScheme.DARK_GRAY_COLOR);
		recommendedTabPanel.add(recommendedControls, BorderLayout.NORTH);
		recommendedTabPanel.add(recommendedCardPanel, BorderLayout.CENTER);
		recommendedTabPanel.add(buildFavoritesPaginationRow(), BorderLayout.SOUTH);
		tabbedPane.addTab("Recommended", recommendedTabPanel);

		tabbedPane.addTab("Active Flips", activeFlipsScrollPane);

		// Completed tab carries a secondary sort row (Profit / Recency) above its list
		JPanel completedTabPanel = CardWidgets.panel(new BorderLayout(), ColorScheme.DARK_GRAY_COLOR);
		completedTabPanel.add(buildCompletedSortRow(), BorderLayout.NORTH);
		completedTabPanel.add(completedFlipsScrollPane, BorderLayout.CENTER);
		tabbedPane.addTab("Completed", completedTabPanel);
		
		// Add listener to update status when switching tabs
		tabbedPane.addChangeListener(e ->
		{
			int selectedIndex = tabbedPane.getSelectedIndex();
			if (selectedIndex == TAB_ACTIVE_FLIPS)
			{
				startActiveFlipsPriceTimer();
			}
			else
			{
				stopActiveFlipsPriceTimer();
			}
			if (selectedIndex == TAB_ACTIVE_FLIPS)
			{
				// Tab selection is a render trigger: re-derive from the store projection.
				renderActiveFlips();
			}
			else if (selectedIndex == TAB_COMPLETED)
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
			else if (selectedIndex == TAB_RECOMMENDED && !currentRecommendations.isEmpty())
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

		JLabel websiteLink = externalLink("Website", new Color(100, 180, 255),
			"Visit our website to view your flips and track your performance", "https://flipsmart.net");

		JLabel discordLink = externalLink("Discord", new Color(88, 101, 242),
			"Join our Discord community", DISCORD_INVITE_URL);

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

	private long settingsPopupClosedAt;

	// Live references to the open pop-out and its filter spinners, so the Update button
	// and the click-out (pop-out dismiss) handler can commit whatever the user typed.
	private JPopupMenu activeSettingsPopup;
	private JSpinner minProfitSpinner;
	private JSpinner minVolumeSpinner;

	private void showItemContextMenu(int itemId, String itemName, JLabel starLabel,
		Component invoker, int x, int y)
	{
		showItemContextMenu(itemId, itemName, starLabel, invoker, x, y, false, null);
	}

	private void showItemContextMenu(int itemId, String itemName, JLabel starLabel,
		Component invoker, int x, int y, boolean includeDismiss, Runnable onDismiss)
	{
		JPopupMenu menu = new JPopupMenu();
		menu.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel header = new JLabel(itemName);
		header.setFont(new Font(FONT_ARIAL, Font.BOLD, 12));
		header.setForeground(Color.WHITE);
		header.setBorder(new EmptyBorder(2, 6, 2, 6));
		menu.add(header);
		menu.addSeparator();

		boolean favorited = isFavorite(favoriteItemIds, itemId);
		boolean appendDismiss = includeDismiss && onDismiss != null;
		List<String> labels = itemContextMenuLabels(favorited, appendDismiss);
		// Actions align positionally with the core three labels itemContextMenuLabels always returns;
		// dismiss (when present) is always the fourth/last entry.
		List<Runnable> actions = List.of(
			() -> handleStarClick(itemId, starLabel),
			() -> handleBlockItemClick(itemId, itemName),
			() -> LinkBrowser.browse(WEBSITE_ITEM_URL + itemId));

		for (int i = 0; i < labels.size(); i++)
		{
			if (appendDismiss && i == labels.size() - 1)
			{
				menu.addSeparator();
			}
			JMenuItem menuItem = new JMenuItem(labels.get(i));
			Runnable action = i < actions.size() ? actions.get(i) : onDismiss;
			menuItem.addActionListener(e -> action.run());
			menu.add(menuItem);
		}

		menu.show(invoker, x, y);
	}

	private void showSettingsPopout(JComponent anchor)
	{
		// A click on the gear while the pop-out is open first dismisses it
		// (light-weight popups close on any outside press), so without this
		// guard the same click would immediately reopen it.
		if (System.currentTimeMillis() - settingsPopupClosedAt < SETTINGS_POPOUT_REOPEN_DEBOUNCE_MS)
		{
			return;
		}

		JPopupMenu popup = new JPopupMenu();
		popup.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		popup.addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e)
			{
			}

			@Override
			@SuppressWarnings("PMD.NullAssignment")
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
			{
				settingsPopupClosedAt = System.currentTimeMillis();
				// Capture whatever the user typed but didn't Enter before the click-out
				// tears the pop-out down, then release the references.
				commitFilterSpinners();
				activeSettingsPopup = null;
				minProfitSpinner = null;
				minVolumeSpinner = null;
				flipOnlyFavoritesCheck = null;
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e)
			{
			}
		});

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		body.setBorder(new EmptyBorder(8, 10, 8, 10));

		body.add(buildMinProfitRow());
		body.add(Box.createVerticalStrut(6));
		body.add(buildMinVolumeRow());
		body.add(Box.createVerticalStrut(6));
		body.add(buildFlipOnlyFavoritesRow());
		body.add(Box.createVerticalStrut(6));
		body.add(buildUpdateButtonRow());
		body.add(Box.createVerticalStrut(6));
		body.add(buildExitTradesRow());
		body.add(Box.createVerticalStrut(6));
		body.add(buildHideButtonsRow());

		popup.add(body);
		activeSettingsPopup = popup;
		popup.show(anchor, 0, anchor.getHeight());
	}

	private JPanel buildMinProfitRow()
	{
		return buildFilterRow("Min Profit: ", config.minimumProfit(), 1000,
			CONFIG_KEY_MIN_PROFIT, spinner -> minProfitSpinner = spinner);
	}

	/**
	 * One "label + spinner" filter row. The two filters differ only in their label,
	 * starting value, step, config key, and which field caches the spinner.
	 */
	private JPanel buildFilterRow(String labelText, int initial, int step, String configKey,
		Consumer<JSpinner> capture)
	{
		JPanel row = CardWidgets.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), ColorScheme.DARKER_GRAY_COLOR);

		JLabel label = CardWidgets.label(labelText, Color.LIGHT_GRAY, FONT_PLAIN_12);
		label.setToolTipText(FILTER_TOOLTIP);

		JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, 0, Integer.MAX_VALUE, step));
		spinner.setToolTipText(FILTER_TOOLTIP);
		commitOnFocusLost(spinner);
		spinner.addChangeListener(e -> applyFilterSettingDebounced(configKey, (Integer) spinner.getValue()));
		capture.accept(spinner);

		row.add(label);
		row.add(spinner);
		return row;
	}

	/**
	 * Make the spinner's editor commit typed text to its model on focus loss (Swing's
	 * default reverts unparseable text but does not commit valid text without Enter),
	 * so tabbing between the two filter fields captures what was typed.
	 */
	private static void commitOnFocusLost(JSpinner spinner)
	{
		JComponent editor = spinner.getEditor();
		if (editor instanceof JSpinner.DefaultEditor)
		{
			((JSpinner.DefaultEditor) editor).getTextField()
				.setFocusLostBehavior(JFormattedTextField.COMMIT);
		}
	}

	private JPanel buildMinVolumeRow()
	{
		return buildFilterRow("Min Volume: ", config.minimumVolume(), 100,
			CONFIG_KEY_MIN_VOLUME, spinner -> minVolumeSpinner = spinner);
	}

	private JPanel buildUpdateButtonRow()
	{
		JPanel row = CardWidgets.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), ColorScheme.DARKER_GRAY_COLOR);

		JButton update = new JButton("Update");
		update.setFont(FONT_PLAIN_12);
		update.setFocusable(false);
		update.setToolTipText("Apply the profit and volume filters and refresh the list");
		// Closing the pop-out runs commitFilterSpinners() via popupMenuWillBecomeInvisible,
		// so the button shares the single commit+apply path used by click-out.
		update.addActionListener(e -> {
			if (activeSettingsPopup != null)
			{
				activeSettingsPopup.setVisible(false);
			}
		});

		row.add(update);
		return row;
	}

	private JPanel buildExitTradesRow()
	{
		JPanel row = CardWidgets.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), ColorScheme.DARKER_GRAY_COLOR);

		boolean exitActive = plugin.getExitTradesController() != null
			&& plugin.getExitTradesController().isActive();

		JButton exit = new JButton(exitActive ? "Buy/Sell Mode" : "Exit Trades / Sell Only");
		exit.setFont(FONT_PLAIN_12);
		exit.setFocusable(false);
		exit.setToolTipText(exitActive
			? "Leave sell-only mode and resume normal buy/sell recommendations"
			: "Unwind all open GE trades at breakeven or instant-sell prices");
		exit.addActionListener(e -> {
			if (activeSettingsPopup != null)
			{
				activeSettingsPopup.setVisible(false);
			}
			if (plugin.getExitTradesController() == null)
			{
				return;
			}
			if (plugin.getExitTradesController().isActive())
			{
				plugin.exitSellOnlyMode();
			}
			else
			{
				ExitTradesDialog.open(this, plugin::startExitTrades);
			}
		});

		row.add(exit);
		return row;
	}

	private JPanel buildHideButtonsRow()
	{
		JPanel row = CardWidgets.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), ColorScheme.DARKER_GRAY_COLOR);

		JCheckBox hide = new JCheckBox("Hide FlipSmart Buttons");
		hide.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		hide.setForeground(Color.LIGHT_GRAY);
		hide.setFont(FONT_PLAIN_12);
		hide.setFocusable(false);
		hide.setSelected(!config.enableFlipAssistant());
		hide.addActionListener(e -> configManager.setConfiguration(
			CONFIG_GROUP, CONFIG_KEY_ENABLE_FLIP_ASSISTANT, !hide.isSelected()));

		row.add(hide);
		return row;
	}

	private void applyFilterSetting(String key, int value)
	{
		configManager.setConfiguration(CONFIG_GROUP, key, value);
		repopulateRecommendationsGated();
	}

	/**
	 * Force the spinner's edited text into its model and return the resulting value,
	 * so a value the user TYPED is captured without pressing Enter. Unparseable text
	 * (letters, empty) is discarded and the last valid value is kept.
	 */
	static int commitSpinner(JSpinner spinner)
	{
		try
		{
			spinner.commitEdit();
		}
		catch (ParseException ex)
		{
			JComponent editor = spinner.getEditor();
			if (editor instanceof JSpinner.DefaultEditor)
			{
				((JSpinner.DefaultEditor) editor).getTextField().setValue(spinner.getValue());
			}
		}
		return (Integer) spinner.getValue();
	}

	/**
	 * Commit both filter spinners and apply them in a single pass — cancelling any
	 * pending debounce so the values take effect immediately and the list re-renders
	 * once. Used by the Update button and by dismissing the pop-out (click-out).
	 */
	private void commitFilterSpinners()
	{
		if (minProfitSpinner == null || minVolumeSpinner == null)
		{
			return;
		}
		int minProfit = commitSpinner(minProfitSpinner);
		int minVolume = commitSpinner(minVolumeSpinner);

		cancelFilterDebounce(CONFIG_KEY_MIN_PROFIT);
		cancelFilterDebounce(CONFIG_KEY_MIN_VOLUME);

		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_MIN_PROFIT, minProfit);
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_MIN_VOLUME, minVolume);
		// Re-apply filters through the slot-limit gate so the cogwheel "Update" can't render
		// the full list past a free user's filled slots (the auto-load path already gates).
		reevaluateSlotLimitDisplay();
	}

	private void cancelFilterDebounce(String key)
	{
		Timer existing = filterSettingDebounceTimers.get(key);
		if (existing != null && existing.isRunning())
		{
			existing.stop();
		}
	}

	// Coalesces rapid spinner adjustments (e.g. holding the up/down arrows)
	// into a single config save + re-populate after the user pauses.
	private void applyFilterSettingDebounced(String key, int value)
	{
		Timer existing = filterSettingDebounceTimers.get(key);
		if (existing != null && existing.isRunning())
		{
			existing.stop();
		}

		Timer timer = new Timer(FILTER_SETTING_DEBOUNCE_MS, e -> applyFilterSetting(key, value));
		timer.setRepeats(false);
		timer.start();
		filterSettingDebounceTimers.put(key, timer);
	}

	/**
	 * Clean up resources when panel is destroyed
	 */
	public void shutdown()
	{
		loginPanel.shutdown();
		if (refreshCountdownTimer != null)
		{
			refreshCountdownTimer.stop();
			refreshCountdownTimer = null;
		}
		if (activeFlipsPriceTimer != null)
		{
			activeFlipsPriceTimer.stop();
		}
		if (sessionStatsTimer != null)
		{
			sessionStatsTimer.stop();
			sessionStatsTimer = null;
		}
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

		startRefreshCountdownTimer();
		recomputeSessionProfitBase();
		startSessionStatsTimer();

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

		// A lapsed subscription should drop the checkbox back to unselected (and persist that)
		// rather than leaving it checked while the resolved flag silently stops applying.
		if (flipOnlyFavoritesCheck != null && flipOnlyFavoritesCheck.isSelected() && !apiClient.isPremium())
		{
			flipOnlyFavoritesCheck.setSelected(false);
			flipOnlyFavorites = false;
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_FLIP_ONLY_FAVORITES, flipOnlyFavorites);
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

		// One /plugin/sync call replaces the four separate reads this cycle used to
		// fire (recommendations, active flips, statistics, completed flips). The
		// bundle always returns recommendations; the same focus/GE/RSN-block gating
		// as before decides whether we apply them.
		boolean skipRecommendationsRefresh = !shuffleSuggestions && currentFocus != null;

		final int recScrollPos = getScrollPosition(recommendedScrollPane);
		boolean applyRecommendations;
		if (skipRecommendationsRefresh)
		{
			log.debug("Skipping recommendations refresh - user is focused on {} ({})",
				currentFocus.getItemName(), currentFocus.isBuying() ? "BUY" : "SELL");
			applyRecommendations = false;
		}
		else
		{
			if (!plugin.isAtGrandExchange())
			{
				showNotAtGeMessage();
				applyRecommendations = false;
			}
			else if (apiClient.isRsnBlocked())
			{
				showRsnBlockedMessage();
				applyRecommendations = false;
			}
			else
			{
				statusLabel.setText("Loading recommendations...");
				refreshButton.setEnabled(false);
				applyRecommendations = true;
			}
		}

		final int activeScrollPos = getScrollPosition(activeFlipsScrollPane);
		final int completedScrollPos = getScrollPosition(completedFlipsScrollPane);

		// Recommendation parameters mirror the standalone /flip-finder call.
		Integer cashStack = getCashStack();
		int limit = Math.max(1, Math.min(50, config.flipFinderLimit()));
		FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
		String flipStyle = selectedStyle != null
			? selectedStyle.getApiValue() : FlipSmartConfig.FlipStyle.BALANCED.getApiValue();
		FlipSmartConfig.FlipTimeframe selectedTimeframe =
			(FlipSmartConfig.FlipTimeframe) flipTimeframeDropdown.getSelectedItem();
		String timeframe = (selectedTimeframe != null && selectedTimeframe.isTimeframeBased())
			? selectedTimeframe.getApiValue() : null;
		// Seed only on manual refresh for variety; auto-refresh keeps items stable.
		Integer randomSeed = shuffleSuggestions ? ThreadLocalRandom.current().nextInt() : null;
		String rsn = plugin.getCurrentRsnSafe().orElse(null);
		Integer filledSlots = getFilledSlots();

		final boolean applyRecs = applyRecommendations;
		boolean favoritesOnly = resolveFavoritesOnly(flipOnlyFavorites, plugin.isPremium());
		apiClient.getPluginSyncAsync(cashStack, getInventoryGp(), flipStyle, limit, randomSeed, timeframe, rsn,
			filledSlots, plugin.isMembersWorld(), favoritesOnly).thenAccept(sync ->
		{
			if (sync == null)
			{
				showBundleError(applyRecs, recScrollPos, activeScrollPos, completedScrollPos, null);
				return;
			}
			applyFavoriteItemIds(sync.getFavoriteItemIds());
			// Each apply method hops to the EDT internally and null-skips a missing
			// sub-payload, leaving that section's prior state untouched.
			if (applyRecs)
			{
				handleRecommendationsResponse(sync.getFlipFinder(), recScrollPos);
			}
			if (sync.getActiveFlips() != null)
			{
				applyActiveFlipsResponse(sync.getActiveFlips(), activeScrollPos);
			}
			applyStatisticsResponse(sync.getStatistics());
			if (sync.getCompletedFlips() != null)
			{
				applyCompletedFlipsResponse(sync.getCompletedFlips(), completedScrollPos);
			}
			// Premium status is set from the flip-finder payload inside
			// handleRecommendationsResponse, matching the pre-bundle behavior. It is
			// deliberately NOT sourced from the entitlements payload here: that snapshot
			// carries premium under rsn_entitlement, not a top-level is_premium, so a
			// naive read reports free and would wrongly drop the player to the 2-slot tier.
		}).exceptionally(throwable ->
		{
			showBundleError(applyRecs, recScrollPos, activeScrollPos, completedScrollPos, throwable);
			return null;
		});
	}

	/** Surface a bundle-level failure (null response or transport error) across all three tabs. */
	private void showBundleError(boolean applyRecs, int recScrollPos, int activeScrollPos, int completedScrollPos,
		Throwable throwable)
	{
		String detail = throwable != null
			? ERROR_PREFIX + throwable.getMessage()
			: "Failed to fetch data. Check your API settings.";
		SwingUtilities.invokeLater(() ->
		{
			refreshButton.setEnabled(true);
			if (applyRecs)
			{
				showErrorInRecommended(detail);
				updatePremiumStatus();
				restoreScrollPosition(recommendedScrollPane, recScrollPos);
			}
			showErrorInActiveFlips(detail);
			restoreScrollPosition(activeFlipsScrollPane, activeScrollPos);
			showErrorInCompletedFlips(detail);
			restoreScrollPosition(completedFlipsScrollPane, completedScrollPos);
		});
	}

	/** Fixed refresh interval in millis (AC18 — independent of the selected Timeframe). */
	private long refreshIntervalMillis()
	{
		int minutes = Math.max(1, Math.min(60, config.flipFinderRefreshMinutes()));
		return minutes * 60_000L;
	}

	/**
	 * Restart the actual refresh scheduler and immediately re-render the countdown as a
	 * single synchronized reset. Because both the label and the scheduled trigger derive
	 * from the scheduler's next-fire deadline, a manual refresh (or Skip, AC19) can never
	 * leave the two out of step — the next auto-refresh is a full interval from now.
	 */
	private void resetRefreshCadence()
	{
		plugin.resetFlipFinderRefreshTimer();
		updateRefreshCountdownLabel();
	}

	/** Manual refresh: realign the scheduler with the countdown, then fetch fresh data. */
	private void manualRefresh()
	{
		resetRefreshCadence();
		refresh(true);
	}

	/** Start the 1-second ticker that drives the live countdown label (AC15). */
	private void startRefreshCountdownTimer()
	{
		if (refreshCountdownTimer == null)
		{
			refreshCountdownTimer = new Timer(1000, e -> updateRefreshCountdownLabel());
			refreshCountdownTimer.start();
		}
		updateRefreshCountdownLabel();
	}

	private void renderSessionStats()
	{
		sessionStatsView.render(SessionStats.snapshot(
			sessionProfitBase, sessionClock.activeMs(System.currentTimeMillis())));
	}

	/**
	 * The one derive path for the active-flip card list: the offer-store projection
	 * (live sells + awaiting-sale lots) enriched by the last backend snapshot. All
	 * consumers — render, session P&L, cross-thread overlay reads — go through here so
	 * there is a single source of truth and no maintained cache.
	 */
	private List<ActiveFlip> projectedActiveFlips()
	{
		Map<Integer, ActiveFlip> enrichment;
		synchronized (enrichmentByItemId)
		{
			enrichment = new java.util.HashMap<>(enrichmentByItemId);
		}
		return plugin.getProjectedActiveFlips(enrichment);
	}

	/**
	 * Recompute the cached profit base and repaint. Self-guards onto the EDT: offer and
	 * inventory events arrive on the client thread, and this both writes the cached base and
	 * drives Swing labels through {@link #renderSessionStats()}.
	 */
	private void recomputeSessionProfitBase()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::recomputeSessionProfitBase);
			return;
		}
		sessionProfitBase = SessionStats.computeBase(currentCompletedFlips,
			OpenPositions.derive(projectedActiveFlips(), plugin::getLiveSellFilledQuantity),
			sessionClock.startMs());
		log.debug("Session P&L recompute: {} completed flips, sessionStart={}, realised={}, unrealised={}",
			currentCompletedFlips.size(), sessionClock.startMs(),
			sessionProfitBase.realisedProfit, sessionProfitBase.unrealisedProfit);
		renderSessionStats();
	}

	private void startSessionStatsTimer()
	{
		if (sessionStatsTimer == null)
		{
			sessionStatsTimer = new Timer(1000, e ->
			{
				sessionClock.update(plugin.isLoggedIntoRunescape(), System.currentTimeMillis());
				renderSessionStats();
			});
		}
		if (!sessionStatsTimer.isRunning())
		{
			sessionStatsTimer.start();
		}
	}

	/**
	 * Live prices are only worth polling while someone is looking at them. The wiki feed
	 * the backend reads is itself ~60s CDN-cached, so a faster cadence would buy nothing.
	 */
	private void startActiveFlipsPriceTimer()
	{
		if (activeFlipsPriceTimer == null)
		{
			activeFlipsPriceTimer = new Timer(ACTIVE_FLIPS_PRICE_REFRESH_MS,
				e -> runActiveFlipsPriceRefresh());
		}
		if (!activeFlipsPriceTimer.isRunning())
		{
			activeFlipsPriceTimer.start();
		}
	}

	private void stopActiveFlipsPriceTimer()
	{
		if (activeFlipsPriceTimer != null && activeFlipsPriceTimer.isRunning())
		{
			activeFlipsPriceTimer.stop();
		}
	}

	/** A manual refresh is a full interval's worth of freshness, so restart the cadence. */
	private void restartActiveFlipsPriceTimer()
	{
		startActiveFlipsPriceTimer();
		activeFlipsPriceTimer.restart();
	}

	@Override
	public void onActivate()
	{
		// The sidebar reopening fires no tab-change event, so this is the only signal
		// that re-arms the price timer after a collapse.
		if (tabbedPane.getSelectedIndex() == TAB_ACTIVE_FLIPS)
		{
			startActiveFlipsPriceTimer();
		}
	}

	@Override
	public void onDeactivate()
	{
		stopActiveFlipsPriceTimer();
	}

	private void runActiveFlipsPriceRefresh()
	{
		if (!isShowing() || tabbedPane.getSelectedIndex() != TAB_ACTIVE_FLIPS)
		{
			stopActiveFlipsPriceTimer();
			return;
		}
		// Copy: a refresh repopulates cards, which can rebuild the list underneath us.
		for (Runnable refresher : new ArrayList<>(activeFlipCardRefreshers))
		{
			try
			{
				refresher.run();
			}
			catch (RuntimeException ex)
			{
				// One bad card must not starve the rest of the sweep.
				if (log.isDebugEnabled())
				{
					log.debug("Active flip price refresh failed for a card: {}", ex.getMessage());
				}
			}
		}
	}

	private void updateRefreshCountdownLabel()
	{
		if (refreshCountdownLabel == null)
		{
			return;
		}
		// Read the scheduler's next-fire deadline — the single source of truth shared with
		// the actual refresh trigger. Fall back to a full interval before the timer reports.
		long nextRefreshAt = plugin.getNextFlipFinderRefreshAtMillis();
		if (nextRefreshAt <= 0)
		{
			nextRefreshAt = System.currentTimeMillis() + refreshIntervalMillis();
		}
		long remainingMs = nextRefreshAt - System.currentTimeMillis();
		long seconds = Math.max(0, (remainingMs + 999) / 1000);
		refreshCountdownLabel.setText(seconds > 0
			? String.format("Item refresh in %ds…", seconds)
			: "Refreshing…");
	}

	/**
	 * Refresh recommended flips
	 *
	 * @param shuffleSuggestions if true, randomizes suggestions within quality tiers
	 */
	private void refreshRecommendations(boolean shuffleSuggestions)
	{
		resetRefreshCadence();
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
		boolean favoritesOnly = resolveFavoritesOnly(flipOnlyFavorites, plugin.isPremium());
		apiClient.getFlipRecommendationsAsync(cashStack, flipStyle, limit, randomSeed, timeframe, rsn, filledSlots, plugin.isMembersWorld(), favoritesOnly).thenAccept(response ->
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

			// If free user has hit their Flip Finder item cap, show the limit message over
			// recommendations but keep currentRecommendations populated so auto-flip can still use them
			PlayerSession session = plugin.getSession();
			if (session != null && plugin.isFlipFinderLimitReached())
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

		// Pass current RSN to filter data for the logged-in account
		String rsn = plugin.getCurrentRsnSafe().orElse(null);
		apiClient.getActiveFlipsAsync(rsn).thenAccept(response ->
		{
			if (response == null)
			{
				SwingUtilities.invokeLater(() ->
				{
					showErrorInActiveFlips("Failed to fetch active flips. Check your API settings.");
					restoreScrollPosition(activeFlipsScrollPane, scrollPos);
				});
				return;
			}
			applyActiveFlipsResponse(response, scrollPos);
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
	 * Render a non-null active-flips payload into the Active Flips tab. Hops to
	 * the EDT internally, so it is safe to call from a background completion
	 * stage or from the bundled /plugin/sync fan-out.
	 */
	private void applyActiveFlipsResponse(ActiveFlipsResponse response, int scrollPos)
	{
		SwingUtilities.invokeLater(() ->
		{
			// Clear cached sell prices - they'll be recalculated when panels are created
			displayedSellPrices.clear();

			// Demote the backend list to an enrichment lookup keyed by itemId; the store
			// projection decides which cards exist, this only fills in numbers.
			List<ActiveFlip> flipsFromBackend = response.getActiveFlips();
			synchronized (enrichmentByItemId)
			{
				enrichmentByItemId.clear();
				if (flipsFromBackend != null)
				{
					for (ActiveFlip f : flipsFromBackend)
					{
						enrichmentByItemId.put(f.getItemId(), f);
					}
				}
			}
			recomputeSessionProfitBase();
			if (flipsFromBackend != null)
			{
				log.debug("Loaded enrichment for {} backend active flips", flipsFromBackend.size());
			}

			// Derive and render from the store projection; the backend list is now
			// only the enrichment lookup rebuilt above.
			renderActiveFlips();

			// Validate focus after refresh in case focused item is no longer active
			validateFocus();
		});
	}

	/**
	 * Reflect the projected active/pending counts in the tab's status label.
	 * Counts derive from the same lists that were just rendered — every
	 * projected flip counts as active, plus the store-derived pending buys.
	 * Only writes the header while the Active Flips tab is selected.
	 */
	private void updateActiveFlipsStatusLabel(List<ActiveFlip> active, List<PendingOrder> pending)
	{
		if (tabbedPane.getSelectedIndex() != TAB_ACTIVE_FLIPS)
		{
			return;
		}
		long invested = active.stream()
			.mapToLong(ActiveFlip::getTotalInvested)
			.sum();
		statusLabel.setText(String.format("%d active · %d pending | %s invested",
			active.size(),
			pending.size(),
			PanelFormat.formatGP(invested)));
	}

	/**
	 * The one derive+render path for the Active Flips tab. Computes the card
	 * list from the offer-store projection (live sells + awaiting-sale lots,
	 * enriched by itemId) plus the store-derived pending buys, renders both,
	 * and updates the header. Runs on the EDT.
	 */
	private void renderActiveFlips()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::renderActiveFlips);
			return;
		}
		final int scrollPos = getScrollPosition(activeFlipsScrollPane);
		List<ActiveFlip> active = projectedActiveFlips();
		List<PendingOrder> pending = plugin.getPendingBuyOrders();
		if (active.isEmpty() && pending.isEmpty())
		{
			showNoActiveFlips();
			return;
		}
		displayActiveFlipsAndPending(active, pending);
		restoreScrollPosition(activeFlipsScrollPane, scrollPos);
		updateActiveFlipsStatusLabel(active, pending);
	}
	
	/**
	 * Update pending orders display (called when GE offers change)
	 * @param pendingOrders the list of pending orders (used to trigger refresh)
	 */
	@SuppressWarnings("unused")
	public void updatePendingOrders(List<PendingOrder> pendingOrders)
	{
		redisplayActiveFlipsLocally();
	}

	/**
	 * Reflect a GE offer state change in the Active Flips tab immediately, using only
	 * data the plugin already holds. Every non-terminal event re-derives the whole tab
	 * from the offer-store projection — the store already carries the collected/live
	 * facts, so there is nothing to patch into a cache first. Must be called on the EDT.
	 */
	public void applyLocalOfferEvent(com.flipsmart.trading.OfferEvent event)
	{
		if (event.kind == OfferTransition.Kind.NONE || event.kind == OfferTransition.Kind.REJECTED)
		{
			return;
		}
		redisplayActiveFlipsLocally();
		// A fill or lifecycle change moves the unsold quantity, which is derived locally — so
		// recompute now rather than letting the session figures wait on the next API refresh.
		recomputeSessionProfitBase();
	}

	/** Rebuild the Active Flips tab from the store projection — no list/aggregate API calls. */
	private void redisplayActiveFlipsLocally()
	{
		renderActiveFlips();
	}

	/**
	 * Inventory-change render trigger. A bought lot becomes an awaiting-sale card
	 * once it lands in the inventory and drops off when it is sold, so a change to
	 * the held-item snapshot must re-derive the projected list independently of any
	 * offer event. Self-hops to the EDT.
	 */
	public void onInventoryChanged()
	{
		renderActiveFlips();
		// Held-item changes add or remove awaiting-sale lots, so the unrealised half of the
		// session figures moves with them.
		recomputeSessionProfitBase();
	}

	/** Update the 30-day profit summary label from a stats payload. Null-safe; hops to the EDT. */
	private void applyStatisticsResponse(FlipStatisticsResponse stats)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (stats != null && tabbedPane.getSelectedIndex() == TAB_COMPLETED)
			{
				statusLabel.setText(String.format("%d flips (30d) | %s profit",
					stats.getTotalFlips(),
					PanelFormat.formatGP(stats.getTotalProfit())));
			}
		});
	}

	/**
	 * Render a non-null completed-flips payload into the Completed tab. Hops to
	 * the EDT internally; safe to call from a background stage or the bundled
	 * /plugin/sync fan-out.
	 */
	private void applyCompletedFlipsResponse(CompletedFlipsResponse response, int scrollPos)
	{
		SwingUtilities.invokeLater(() ->
		{
			currentCompletedFlips.clear();
			if (response.getFlips() != null)
			{
				currentCompletedFlips.addAll(response.getFlips());
			}
			recomputeSessionProfitBase();

			if (currentCompletedFlips.isEmpty())
			{
				showNoCompletedFlips();
				restoreScrollPosition(completedFlipsScrollPane, scrollPos);
				return;
			}

			populateCompletedFlips(currentCompletedFlips);
			restoreScrollPosition(completedFlipsScrollPane, scrollPos);
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
	private void populateCompletedFlips(List<CompletedFlip> flips)
	{
		completedFlipsListContainer.removeAll();

		for (CompletedFlip flip : sortedCompletedFlips(flips))
		{
			completedFlipsListContainer.add(createCompletedFlipPanel(flip));
			completedFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
		}

		completedFlipsListContainer.revalidate();
		completedFlipsListContainer.repaint();
	}

	/**
	 * Return a copy of {@code flips} ordered by the active Completed-tab sort:
	 * Profit (highest net profit first, AC7) or Recency (most recently sold first, AC8).
	 */
	private List<CompletedFlip> sortedCompletedFlips(List<CompletedFlip> flips)
	{
		List<CompletedFlip> sorted = new ArrayList<>(flips);
		Comparator<CompletedFlip> comparator;
		if (completedSort == CompletedSort.PROFIT)
		{
			comparator = Comparator.comparingLong(CompletedFlip::getNetProfit).reversed();
		}
		else
		{
			Map<CompletedFlip, Long> tsCache = new java.util.IdentityHashMap<>();
			for (CompletedFlip f : sorted)
			{
				tsCache.put(f, TimeUtils.parseIsoToMillis(f.getSellTime()));
			}
			comparator = Comparator
				.comparingLong((CompletedFlip f) -> tsCache.get(f))
				.thenComparingInt(CompletedFlip::getId)
				.reversed();
		}
		sorted.sort(comparator);
		return sorted;
	}

	/**
	 * Build the secondary sort row shown above the Completed list (AC5/AC6).
	 * Two tab-style labels — Recency and Profit — styled like the main tab row.
	 */
	private JPanel buildCompletedSortRow()
	{
		return buildSortRow(CompletedSort.values(), sort -> sort.label,
			this::selectCompletedSort, completedSortTabs, this::applyCompletedSortTabStyles);
	}

	/**
	 * Shared "Sort: [tab][tab]..." row for the completed and favourites lists. Both
	 * differ only in their enum, its label accessor, and what a click does.
	 */
	private <T extends Enum<T>> JPanel buildSortRow(T[] sorts, Function<T, String> label,
		Consumer<T> onSelect, Map<T, JLabel> tabs, Runnable applyStyles)
	{
		JPanel row = CardWidgets.panel(new FlowLayout(FlowLayout.LEFT, 6, 4), ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(0, 4, 0, 0));

		JLabel sortByLabel = CardWidgets.label("Sort:", COLOR_TEXT_DIM_GRAY, new Font(FONT_ARIAL, Font.ITALIC, 10));
		row.add(sortByLabel);

		Font tabFont = new Font(FONT_ARIAL, Font.BOLD, 11);
		EmptyBorder tabBorder = new EmptyBorder(2, 7, 2, 7);
		Cursor handCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
		for (T sort : sorts)
		{
			JLabel tab = new JLabel(label.apply(sort));
			tab.setFont(tabFont);
			tab.setBorder(tabBorder);
			tab.setCursor(handCursor);
			tab.setOpaque(true);
			tab.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					onSelect.accept(sort);
				}
			});
			tabs.put(sort, tab);
			row.add(tab);
		}

		applyStyles.run();
		return row;
	}

	/** Persist the chosen sort (AC10), restyle the tabs, and re-render the list. */
	private void selectCompletedSort(CompletedSort sort)
	{
		if (sort == completedSort)
		{
			return;
		}
		completedSort = sort;
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_COMPLETED_SORT, sort.name());
		applyCompletedSortTabStyles();
		if (!currentCompletedFlips.isEmpty())
		{
			populateCompletedFlips(currentCompletedFlips);
		}
	}

	/** Highlight the active sort tab (orange) and dim the others. */
	private void applyCompletedSortTabStyles()
	{
		for (Map.Entry<CompletedSort, JLabel> entry : completedSortTabs.entrySet())
		{
			boolean selected = entry.getKey() == completedSort;
			JLabel tab = entry.getValue();
			tab.setForeground(selected ? Color.WHITE : COLOR_TEXT_DIM_GRAY);
			tab.setBackground(selected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARK_GRAY_COLOR);
		}
	}

	/** Read the persisted Completed-tab sort, defaulting to Recency (AC9). */
	private CompletedSort loadCompletedSort()
	{
		String saved = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_COMPLETED_SORT);
		if (saved != null)
		{
			try
			{
				return CompletedSort.valueOf(saved);
			}
			catch (IllegalArgumentException ignored)
			{
				// Unknown/legacy value — fall through to the default.
			}
		}
		return CompletedSort.RECENCY;
	}
	
	/**
	 * Display both active flips and pending orders. The projection is non-overlapping by
	 * construction — a pending buy row is a live BUY offer, a projected active flip is a
	 * live SELL offer or an awaiting-sale inventory lot — so every projected flip renders
	 * with no dedup against the pending rows.
	 */
	private void displayActiveFlipsAndPending(List<ActiveFlip> activeFlips, List<PendingOrder> pendingOrders)
	{
		activeFlipsListContainer.removeAll();
		activeFlipCardRefreshers.clear();

		// First show pending orders (items currently in GE buy slots)
		for (PendingOrder pending : pendingOrders)
		{
			activeFlipsListContainer.add(createPendingOrderPanel(pending));
			activeFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
		}

		// Then show active flips (items collected, waiting to sell)
		for (ActiveFlip flip : activeFlips)
		{
			activeFlipsListContainer.add(createActiveFlipPanel(flip));
			activeFlipsListContainer.add(Box.createRigidArea(new Dimension(0, 5)));
		}

		activeFlipsListContainer.revalidate();
		activeFlipsListContainer.repaint();
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
	 * The player's real inventory coins, reported for the web "Capital Active" card.
	 * Deliberately separate from {@link #getCashStack()}, which the cashstack
	 * override may replace with a user-supplied figure. Null means "unknown, do not
	 * record"; zero is a real reading and must be sent as such.
	 */
	protected Integer getInventoryGp()
	{
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
		// The status label is shared across tabs; only write the Recommended header while that tab is
		// selected so a background recommendation refresh can't overwrite the Active Flips header.
		if (tabbedPane.getSelectedIndex() != TAB_RECOMMENDED)
		{
			return;
		}
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

	/**
	 * Returns true if a recommendation passes the current profit and volume filters.
	 * Shares one predicate with the Flip Assist queue so the two features surface the
	 * same items.
	 */
	private boolean shouldDisplay(FlipRecommendation rec)
	{
		return FocusedFlip.passesRecommendationFilters(
			rec, config.priceOffset(), config.minimumProfit(), config.minimumVolume());
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
		showErrorInRecommended("Free users are limited to flipping only two items at a time from Flip Finder");
		subscribeLabel.setText(SUBSCRIBE_MESSAGE);
		subscribeLabel.setVisible(true);
		refreshButton.setEnabled(true);
	}

	/**
	 * Repaint the recommendation list through the free-tier slot gate. Repaints that bypass
	 * this gate paint the full list back over the limit message once a free user is capped,
	 * handing them recommendations they cannot act on. Caller must already be on the EDT.
	 */
	private void repopulateRecommendationsGated()
	{
		if (plugin.getSession() != null && plugin.isFlipFinderLimitReached())
		{
			showSlotLimitMessage();
			return;
		}
		populateRecommendations(new ArrayList<>(currentRecommendations));
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

			boolean atLimit = plugin.isFlipFinderLimitReached();

			if (atLimit)
			{
				showSlotLimitMessage();
			}
			else if (!currentRecommendations.isEmpty())
			{
				// Only touch the shared status label while the Recommended tab is selected, so a
				// slot-change during Auto doesn't overwrite the Active Flips header.
				if (tabbedPane.getSelectedIndex() == TAB_RECOMMENDED)
				{
					int count = countDisplayed(currentRecommendations);
					String itemWord = count == 1 ? "suggestion" : "suggestions";
					FlipSmartConfig.FlipStyle selectedStyle = (FlipSmartConfig.FlipStyle) flipStyleDropdown.getSelectedItem();
					String flipStyleText = selectedStyle != null ? selectedStyle.toString() : "Balanced";
					statusLabel.setText(String.format("%s | %d %s", flipStyleText, count, itemWord));
				}

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

		JLabel titleLabel = CardWidgets.label(title, Color.WHITE, FONT_BOLD_16);
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel instructionLabel = CardWidgets.label(instruction, COLOR_TEXT_DIM_GRAY, FONT_PLAIN_12);
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
		if (tabbedPane.getSelectedIndex() == TAB_ACTIVE_FLIPS)
		{
			statusLabel.setText("0 active flips");
		}
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

		int displayed = 0;
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
			displayed++;
		}

		if (displayed == 0)
		{
			// Recommendations exist but the user's profit/volume filters removed them all —
			// show a clear empty state rather than a blank panel.
			recommendedListContainer.add(createEmptyStatePanel("Flip Finder",
				"<html><table width='160'><tr><td align='center'>"
					+ "There are currently no suggestions matching your criteria.</td></tr></table></html>", 80));
		}

		recommendedListContainer.revalidate();
		recommendedListContainer.repaint();
	}

	/** Flip between the algorithm recommendations and the in-tab favorites view. */
	private void toggleFavoritesView()
	{
		favoritesViewActive = !favoritesViewActive;
		applyFavoritesViewState();
		if (favoritesViewActive)
		{
			updateFavoritesStatusLabel();
			refreshFavoritesTab();
		}
	}

	/** Show/hide the favorites list, controls, pagination and update the toggle button for the current view. */
	private void applyFavoritesViewState()
	{
		favoritesControlsPanel.setVisible(favoritesViewActive);
		if (favoritesViewActive)
		{
			favoritesToggleButton.setText("Algorithm");
			favoritesToggleButton.setForeground(Color.LIGHT_GRAY);
			recommendedCardLayout.show(recommendedCardPanel, CARD_FAVORITES);
		}
		else
		{
			favoritesToggleButton.setText("★ Favorites");
			favoritesToggleButton.setForeground(STAR_ON_COLOR);
			recommendedCardLayout.show(recommendedCardPanel, CARD_ALGORITHM);
			favoritesPaginationRow.setVisible(false);
		}
		recommendedCardPanel.revalidate();
		recommendedCardPanel.repaint();
	}

	/** Fetch the enriched favorites list and repaint the Favorites tab. */
	void refreshFavoritesTab()
	{
		apiClient.getFavoritesAsync().thenAccept(resp -> SwingUtilities.invokeLater(() ->
		{
			currentFavorites.clear();
			if (resp != null && resp.getItems() != null)
			{
				currentFavorites.addAll(resp.getItems());
			}
			populateFavorites();
		})).exceptionally(ex ->
		{
			log.warn("Failed to load favorites", ex);
			SwingUtilities.invokeLater(() ->
				showErrorInContainer(favoritesListContainer, "Favorites", "Failed to load favorites. Please try again."));
			return null;
		});
	}

	private void populateFavorites()
	{
		favoritesListContainer.removeAll();
		List<FavoriteItem> sorted = new ArrayList<>(currentFavorites);
		sorted.sort(favoritesSort.comparator());
		int pages = Paginator.pageCount(sorted.size(), FAVORITES_PAGE_SIZE);
		if (favoritesPage > pages - 1)
		{
			favoritesPage = pages - 1;
		}
		if (favoritesPage < 0)
		{
			favoritesPage = 0;
		}
		List<FavoriteItem> visible = Paginator.page(sorted, favoritesPage, FAVORITES_PAGE_SIZE);
		if (visible.isEmpty())
		{
			favoritesListContainer.add(createEmptyStatePanel(
				"No favorites yet",
				"<html><center>Tap the star on any item<br>to add it here</center></html>",
				60));
		}
		else
		{
			for (FavoriteItem item : visible)
			{
				favoritesListContainer.add(createFavoriteCard(item));
				favoritesListContainer.add(Box.createRigidArea(new Dimension(0, 4)));
			}
		}
		updateFavoritesPaginationControls(pages);
		favoritesListContainer.revalidate();
		favoritesListContainer.repaint();
		updateFavoritesStatusLabel();
	}

	/** Update the footer status label with the current favorites count. */
	private void updateFavoritesStatusLabel()
	{
		int count = currentFavorites.size();
		statusLabel.setText(count == 1 ? "1 favorite" : count + " favorites");
	}

	/**
	 * Build the sort row shown above the Favorites list, mirroring the Completed-tab
	 * sort idiom: tab-style labels, one per {@link FavoritesSort}.
	 */
	private JPanel buildFavoritesSortRow()
	{
		return buildSortRow(FavoritesSort.values(), FavoritesSort::getLabel,
			this::selectFavoritesSort, favoritesSortTabs, this::applyFavoritesSortTabStyles);
	}

	/** Persist the chosen sort, reset to page 0, restyle the tabs, and re-render the list. */
	private void selectFavoritesSort(FavoritesSort sort)
	{
		if (sort == favoritesSort)
		{
			return;
		}
		favoritesSort = sort;
		favoritesPage = 0;
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_FAVORITES_SORT, sort.name());
		applyFavoritesSortTabStyles();
		populateFavorites();
	}

	/** Highlight the active sort tab (orange) and dim the others. */
	private void applyFavoritesSortTabStyles()
	{
		for (Map.Entry<FavoritesSort, JLabel> entry : favoritesSortTabs.entrySet())
		{
			boolean selected = entry.getKey() == favoritesSort;
			JLabel tab = entry.getValue();
			tab.setForeground(selected ? Color.WHITE : COLOR_TEXT_DIM_GRAY);
			tab.setBackground(selected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARK_GRAY_COLOR);
		}
	}

	/** Read the persisted Favorites-tab sort, defaulting to Profit. */
	private FavoritesSort loadFavoritesSort()
	{
		String saved = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_FAVORITES_SORT);
		if (saved != null)
		{
			try
			{
				return FavoritesSort.valueOf(saved);
			}
			catch (IllegalArgumentException ignored)
			{
				// Unknown/legacy value — fall through to the default.
			}
		}
		return FavoritesSort.PROFIT;
	}

	/**
	 * Whether the recommendation feed should be restricted to favorited items. Free tier
	 * never sends {@code favorites_only=true} to the API regardless of the local toggle state.
	 */
	public static boolean resolveFavoritesOnly(boolean toggleOn, boolean premium)
	{
		return toggleOn && premium;
	}

	static String favoriteMenuLabel(boolean isFavorited)
	{
		return isFavorited ? "Remove from favorites" : "Add to favorites";
	}

	static List<String> itemContextMenuLabels(boolean isFavorited, boolean includeDismiss)
	{
		List<String> labels = new ArrayList<>(List.of(
			favoriteMenuLabel(isFavorited), "Block this item", "View Item Graph"));
		if (includeDismiss)
		{
			labels.add("Dismiss from Active Flips");
		}
		return labels;
	}

	/** Read the persisted "flip only from favorites" toggle, defaulting to off. */
	private boolean loadFlipOnlyFavorites()
	{
		return Boolean.parseBoolean(configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_FLIP_ONLY_FAVORITES));
	}

	/** Build the row holding the premium-gated "Flip only from favorites" checkbox. */
	private JPanel buildFlipOnlyFavoritesRow()
	{
		JPanel row = CardWidgets.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(0, 4, 4, 0));
		row.add(buildFlipOnlyFavoritesCheck());
		return row;
	}

	/** Build the "Flip only from favorites" checkbox, enforcing the premium gate (AC11). */
	private JCheckBox buildFlipOnlyFavoritesCheck()
	{
		flipOnlyFavoritesCheck = new JCheckBox("Flip only from favorites");
		flipOnlyFavoritesCheck.setSelected(flipOnlyFavorites);
		flipOnlyFavoritesCheck.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		flipOnlyFavoritesCheck.setForeground(Color.LIGHT_GRAY);
		flipOnlyFavoritesCheck.addActionListener(e ->
		{
			if (flipOnlyFavoritesCheck.isSelected() && !plugin.isPremium())
			{
				flipOnlyFavoritesCheck.setSelected(false);
				showPremiumRequiredForFavoritesOnly();
				return;
			}
			flipOnlyFavorites = flipOnlyFavoritesCheck.isSelected();
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_FLIP_ONLY_FAVORITES, flipOnlyFavorites);
			refresh(false);
		});
		return flipOnlyFavoritesCheck;
	}

	/** Short transient premium nudge for a blocked flip-only-favorites toggle attempt. */
	private void showPremiumRequiredForFavoritesOnly()
	{
		subscribeLabel.setText("Flip only from favorites is a Premium feature.");
		subscribeLabel.setVisible(true);
	}

	/** Build the prev/page-label/next pagination row shown below the Favorites list. */
	private JPanel buildFavoritesPaginationRow()
	{
		favoritesPaginationRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
		favoritesPaginationRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		Font navFont = new Font(FONT_ARIAL, Font.BOLD, 12);
		Cursor handCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);

		favoritesPrevPageLabel = new JLabel("<");
		favoritesPrevPageLabel.setFont(navFont);
		favoritesPrevPageLabel.setForeground(Color.WHITE);
		favoritesPrevPageLabel.setCursor(handCursor);
		favoritesPrevPageLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (favoritesPage > 0)
				{
					favoritesPage--;
					populateFavorites();
				}
			}
		});

		favoritesPageLabel = new JLabel("Page 1/1");
		favoritesPageLabel.setFont(new Font(FONT_ARIAL, Font.PLAIN, 11));
		favoritesPageLabel.setForeground(COLOR_TEXT_DIM_GRAY);

		favoritesNextPageLabel = new JLabel(">");
		favoritesNextPageLabel.setFont(navFont);
		favoritesNextPageLabel.setForeground(Color.WHITE);
		favoritesNextPageLabel.setCursor(handCursor);
		favoritesNextPageLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				int pages = Paginator.pageCount(currentFavorites.size(), FAVORITES_PAGE_SIZE);
				if (favoritesPage < pages - 1)
				{
					favoritesPage++;
					populateFavorites();
				}
			}
		});

		favoritesPaginationRow.add(favoritesPrevPageLabel);
		favoritesPaginationRow.add(favoritesPageLabel);
		favoritesPaginationRow.add(favoritesNextPageLabel);
		favoritesPaginationRow.setVisible(false);
		return favoritesPaginationRow;
	}

	/** Update the "Page x/y" text, enable/disable prev/next, and hide the row for a single page. */
	private void updateFavoritesPaginationControls(int pages)
	{
		favoritesPageLabel.setText("Page " + (favoritesPage + 1) + "/" + pages);
		favoritesPrevPageLabel.setForeground(favoritesPage > 0 ? Color.WHITE : COLOR_TEXT_DIM_GRAY);
		favoritesNextPageLabel.setForeground(favoritesPage < pages - 1 ? Color.WHITE : COLOR_TEXT_DIM_GRAY);
		favoritesPaginationRow.setVisible(pages > 1);
	}

	/**
	 * Render a favorite with the same card as a recommendation — a favorite is the same kind
	 * of item, so it gets the identical layout, colours, and click-to-focus behaviour. The
	 * favorite's server-sent buy limit is the quantity its profit is sized to (one full cycle).
	 */
	private JPanel createFavoriteCard(FavoriteItem item)
	{
		return createRecommendationPanel(favoriteAsRecommendation(item));
	}

	/** Map a favorite onto a {@link FlipRecommendation} so the recommendation renderer can draw it. */
	private static FlipRecommendation favoriteAsRecommendation(FavoriteItem item)
	{
		int buy = item.getBuyPrice() != null ? item.getBuyPrice() : 0;
		int quantity = item.getBuyLimit() != null && item.getBuyLimit() > 0 ? item.getBuyLimit() : 1;
		FlipRecommendation rec = new FlipRecommendation();
		rec.setItemId(item.getItemId());
		rec.setItemName(item.getItemName());
		rec.setRecommendedBuyPrice(buy);
		rec.setRecommendedSellPrice(item.getSellPrice() != null ? item.getSellPrice() : 0);
		rec.setRecommendedQuantity(quantity);
		rec.setBuyLimit(quantity);
		rec.setDailyVolume(item.getVolume());
		rec.setRiskScore(item.getRiskScore());
		rec.setRiskRating(item.getRiskRating());
		return rec;
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
		addRecommendationPanelListeners(panel, rec, header.starLabel);

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
		long displayProfit = ((long) displayMargin - geTax) * rec.getRecommendedQuantity();
		long displayCost = (long) displayBuyPrice * rec.getRecommendedQuantity();
		double displayRoi = displayBuyPrice > 0 ? ((double)(displayMargin - geTax) / displayBuyPrice) * 100 : 0;

		// Recommended Buy/Sell prices — buy blue, sell orange, bold (matches the Active-tab live-price row).
		// White base foreground keeps the "Buy:"/"Sell:" label text (outside the coloured spans) legible.
		JLabel priceLabel = CardWidgets.label(PanelFormat.buySellHtml(displayBuyPrice, displaySellPrice), Color.WHITE, FONT_PLAIN_12);

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
		JLabel profitLabel = CardWidgets.label(PanelFormat.formatProfitCostText(displayProfit, displayCost), new Color(255, 215, 0), FONT_PLAIN_12);

		// Volume info
		JLabel volumeLabel = CardWidgets.label(PanelFormat.formatVolumeText(rec.getDailyVolume()), Color.CYAN, FONT_PLAIN_12);

		// Risk info
		JLabel riskLabel = CardWidgets.label(PanelFormat.formatRiskText(rec.getRiskScore(), rec.getRiskRating()), PanelFormat.getRiskColor(rec.getRiskScore()), FONT_PLAIN_12);

		CardWidgets.addLabelsWithSpacing(detailsPanel, priceLabel, quantityLabel, marginLabel,
			profitLabel, volumeLabel, riskLabel);

		return detailsPanel;
	}

	/**
	 * Add mouse listeners for recommendation panel (focus, expand, hover)
	 */
	private void addRecommendationPanelListeners(JPanel panel, FlipRecommendation rec, JLabel starLabel)
	{
		panel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() != MouseEvent.BUTTON1 || e.isPopupTrigger())
				{
					return;
				}
				// Clicking the focused card clears focus; clicking any other one takes it.
				// Matches the predicate the hover handlers below use, so the card that
				// looks focused is exactly the one a click will unfocus.
				if (currentFocus != null && currentFocus.getItemId() == rec.getItemId())
				{
					clearFocus();
				}
				else
				{
					// setFocus is a no-op while Exit Trades is active or a free user is
					// at the item cap, so a blocked click changes nothing.
					setFocus(rec, panel);
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShowMenu(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowMenu(e);
			}

			private void maybeShowMenu(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showItemContextMenu(rec.getItemId(), rec.getItemName(), starLabel, panel, e.getX(), e.getY());
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
		/** Pixels a wrapped name adds beyond one line; 0 when the name fits on one. */
		final int extraNameHeight;
		final JLabel starLabel;

		HeaderPanels(JPanel topPanel, JPanel namePanel, int extraNameHeight, JLabel starLabel)
		{
			this.topPanel = topPanel;
			this.namePanel = namePanel;
			this.extraNameHeight = extraNameHeight;
			this.starLabel = starLabel;
		}
	}

	/** Give the card back the pixels a wrapped name took, so the cap cannot clip the last row. */
	private static void allowForWrappedName(JPanel card, int extraNameHeight)
	{
		if (extraNameHeight <= 0)
		{
			return;
		}
		Dimension max = card.getMaximumSize();
		int raised = max.height > Integer.MAX_VALUE - extraNameHeight
			? Integer.MAX_VALUE
			: max.height + extraNameHeight;
		card.setMaximumSize(new Dimension(max.width, raised));
	}

	/**
	 * Create the item header panel with icon and name
	 */
	/** Right clearance for a card's corner content, so it sits past the always-visible scrollbar. */
	private static final int CARD_EAST_INSET = 10;

	private HeaderPanels createItemHeaderPanels(int itemId, String itemName, Color bgColor)
	{
		return createItemHeaderPanels(itemId, itemName, bgColor, null, null);
	}

	private HeaderPanels createItemHeaderPanels(int itemId, String itemName, Color bgColor, JLabel trailingIcon)
	{
		return createItemHeaderPanels(itemId, itemName, bgColor, trailingIcon, null);
	}

	private HeaderPanels createItemHeaderPanels(int itemId, String itemName, Color bgColor,
		JLabel trailingIcon, JLabel cornerSubtitle)
	{
		JPanel topPanel = CardWidgets.panel(new BorderLayout(4, 0), bgColor);

		JPanel namePanel = CardWidgets.panel(new BorderLayout(5, 0), bgColor);

		AsyncBufferedImage itemImage = itemManager.getImage(itemId);
		JLabel iconLabel = new JLabel();
		CardWidgets.setupIconLabel(iconLabel, itemImage);

		JLabel nameLabel = new JLabel();
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setVerticalAlignment(SwingConstants.TOP);
		// Narrow the name a little more when a third (refresh) icon shares the corner.
		// Trimmed by 4px alongside the icon inset below so a long title keeps clear of the
		// icons now that they sit further left.
		int nameWidth = trailingIcon != null ? 94 : 126;
		ItemNameFit.Fit nameFit = ItemNameFit.fit(itemName, nameWidth,
			(text, size) -> nameLabel.getFontMetrics(new Font(FONT_ARIAL, Font.BOLD, size)).stringWidth(text));
		Font nameFont = new Font(FONT_ARIAL, Font.BOLD, nameFit.getFontSize());
		nameLabel.setFont(nameFont);
		nameLabel.setText(nameFit.getHtml());
		// Size the height from the line count we chose. Measuring the label before the
		// width constraint applies reports a single line, which is what clipped names.
		int nameLineHeight = nameLabel.getFontMetrics(nameFont).getHeight();
		int nameHeight = nameFit.getLineCount() * nameLineHeight;
		nameLabel.setPreferredSize(new Dimension(nameWidth, nameHeight));
		nameLabel.setMaximumSize(new Dimension(nameWidth, nameHeight));
		int extraNameHeight = (nameFit.getLineCount() - 1) * nameLineHeight;

		namePanel.add(iconLabel, BorderLayout.WEST);
		namePanel.add(nameLabel, BorderLayout.CENTER);

		JPanel iconsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0));
		iconsPanel.setOpaque(false);
		// Clearance from the always-visible scrollbar is applied once, below, to whichever
		// component actually goes into BorderLayout.EAST. Insetting iconsPanel here instead
		// would double up on cards that stack it above a corner subtitle, pushing the icons
		// left of the subtitle rather than keeping the two right edges aligned.

		JLabel starLabel = createStarIconLabel(itemId);
		iconsPanel.add(starLabel);
		iconsPanel.add(createBlockIconLabel(itemId, itemName));
		iconsPanel.add(createChartIconLabel(itemId));

		if (trailingIcon != null)
		{
			// Visible divider so the refresh action reads as distinct from the two existing icons
			JLabel divider = new JLabel("|");
			divider.setForeground(COLOR_TEXT_GRAY);
			divider.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
			iconsPanel.add(divider);
			iconsPanel.add(trailingIcon);
		}

		topPanel.add(namePanel, BorderLayout.CENTER);
		if (cornerSubtitle != null)
		{
			// Stack the corner subtitle (e.g. buy limit) beneath the icon row, right-aligned
			iconsPanel.setAlignmentX(Component.RIGHT_ALIGNMENT);
			cornerSubtitle.setAlignmentX(Component.RIGHT_ALIGNMENT);
			JPanel eastStack = new JPanel();
			eastStack.setLayout(new BoxLayout(eastStack, BoxLayout.Y_AXIS));
			eastStack.setOpaque(false);
			// One inset for the whole stack keeps the icon row and the subtitle beneath it
			// sharing a right edge, and clear of the scrollbar.
			eastStack.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, CARD_EAST_INSET));
			eastStack.add(iconsPanel);
			eastStack.add(cornerSubtitle);
			topPanel.add(eastStack, BorderLayout.EAST);
		}
		else
		{
			iconsPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, CARD_EAST_INSET));
			topPanel.add(iconsPanel, BorderLayout.EAST);
		}

		return new HeaderPanels(topPanel, namePanel, extraNameHeight, starLabel);
	}
	
	/**
	 * Create a clickable chart icon label that opens the item's page on the website.
	 * Uses a simple bar chart icon drawn with Java 2D graphics.
	 */
	/** Base for the small clickable card icons: image, tooltip, hand cursor, transparent. */
	private static JLabel iconLabel(BufferedImage icon, String tooltip)
	{
		JLabel label = new JLabel(new ImageIcon(icon));
		label.setToolTipText(tooltip);
		label.setCursor(new Cursor(Cursor.HAND_CURSOR));
		label.setOpaque(false);
		return label;
	}

	/** As {@link #iconLabel(BufferedImage, String)}, with {@code leftPad} px of left inset. */
	private static JLabel iconLabel(BufferedImage icon, String tooltip, int leftPad)
	{
		JLabel label = iconLabel(icon, tooltip);
		label.setBorder(BorderFactory.createEmptyBorder(0, leftPad, 0, 0));
		return label;
	}

	/** Show {@code hover} while the pointer is over an enabled {@code label}, else {@code base}. */
	private static void hoverSwap(JLabel label, BufferedImage base, Supplier<BufferedImage> hover)
	{
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (label.isEnabled())
				{
					label.setIcon(new ImageIcon(hover.get()));
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setIcon(new ImageIcon(base));
			}
		});
	}

	/** Click handler that stops the event reaching the card underneath. */
	private static void onIconClick(JLabel label, Runnable action)
	{
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				e.consume();
				action.run();
			}
		});
	}

	private JLabel createChartIconLabel(int itemId)
	{
		BufferedImage chartIcon = PanelFormat.icon("chart");
		JLabel chartLabel = iconLabel(chartIcon, "View price history on FlipSmart website", 1);
		hoverSwap(chartLabel, chartIcon,
			() -> PanelFormat.icon("chart_hover"));
		onIconClick(chartLabel, () -> LinkBrowser.browse(WEBSITE_ITEM_URL + itemId));
		return chartLabel;
	}

	private JLabel createRefreshIconLabel(Runnable onRefresh)
	{
		BufferedImage refreshIcon = PanelFormat.icon("refresh");
		JLabel refreshLabel = iconLabel(refreshIcon, "Refresh latest prices.", 1);
		hoverSwap(refreshLabel, refreshIcon, () -> PanelFormat.icon("refresh_hover"));
		onIconClick(refreshLabel, () ->
		{
			if (!refreshLabel.isEnabled())
			{
				return;
			}
			refreshLabel.setEnabled(false);
			refreshLabel.setCursor(Cursor.getDefaultCursor());
			onRefresh.run();
			restartActiveFlipsPriceTimer();
			// Re-enable shortly after; the async re-populate updates the rows independently
			Timer timer = new Timer(1200, ev ->
			{
				refreshLabel.setEnabled(true);
				refreshLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
			});
			timer.setRepeats(false);
			timer.start();
		});

		return refreshLabel;
	}

	/** Whether {@code itemId} is in the caller's favourites, null-safe on the set. */
	public static boolean isFavorite(Set<Integer> favorites, int itemId)
	{
		return favorites != null && favorites.contains(itemId);
	}

	private void applyFavoriteItemIds(List<Integer> ids)
	{
		SwingUtilities.invokeLater(() ->
		{
			favoriteItemIds.clear();
			if (ids != null)
			{
				favoriteItemIds.addAll(ids);
			}
		});
	}

	private JLabel createStarIconLabel(int itemId)
	{
		boolean filled = isFavorite(favoriteItemIds, itemId);
		JLabel star = iconLabel(PanelFormat.icon(filled ? "star_on" : "star_off"),
			filled ? "Remove from favorites" : "Add to favorites");
		onIconClick(star, () -> handleStarClick(itemId, star));
		return star;
	}

	private void handleStarClick(int itemId, JLabel starLabel)
	{
		boolean wasFavorite = isFavorite(favoriteItemIds, itemId);
		// Optimistic flip of local state + icon.
		if (wasFavorite)
		{
			favoriteItemIds.remove(itemId);
		}
		else
		{
			favoriteItemIds.add(itemId);
		}
		updateStarLabel(starLabel, itemId);

		java.util.concurrent.CompletableFuture<Boolean> call = wasFavorite
			? apiClient.removeFavoriteAsync(itemId)
			: apiClient.addFavoriteAsync(itemId);

		call.thenAccept(success -> SwingUtilities.invokeLater(() ->
		{
			if (!Boolean.TRUE.equals(success))
			{
				rollbackFavoriteToggle(wasFavorite, itemId, starLabel);
			}
			else if (tabbedPane.getSelectedIndex() == TAB_RECOMMENDED && favoritesViewActive)
			{
				refreshFavoritesTab();
			}
		})).exceptionally(ex ->
		{
			log.warn("Favorite toggle failed for item {}", itemId, ex);
			SwingUtilities.invokeLater(() -> rollbackFavoriteToggle(wasFavorite, itemId, starLabel));
			return null;
		});
	}

	/** Undo the optimistic favorite-toggle flip after a failed add/remove call. */
	private void rollbackFavoriteToggle(boolean wasFavorite, int itemId, JLabel starLabel)
	{
		if (wasFavorite)
		{
			favoriteItemIds.add(itemId);
		}
		else
		{
			favoriteItemIds.remove(itemId);
		}
		updateStarLabel(starLabel, itemId);
	}

	private void updateStarLabel(JLabel starLabel, int itemId)
	{
		boolean filled = isFavorite(favoriteItemIds, itemId);
		starLabel.setIcon(new ImageIcon(
			PanelFormat.icon(filled ? "star_on" : "star_off")));
		starLabel.setToolTipText(filled ? "Remove from favorites" : "Add to favorites");
	}

	private JLabel createBlockIconLabel(int itemId, String itemName)
	{
		BufferedImage blockIcon = PanelFormat.icon("block");
		JLabel blockLabel = iconLabel(blockIcon, "Block this item from recommendations", 2);
		hoverSwap(blockLabel, blockIcon, () -> PanelFormat.icon("block_hover"));
		onIconClick(blockLabel, () -> handleBlockItemClick(itemId, itemName));

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
	 * Derives a fresh snapshot from the offer-store projection each call. Cross-thread
	 * callers (e.g. the GE slot-hover overlay, which runs on the render thread and reads
	 * via BuyPriceLookup.findAverageBuyPrice) always get a complete, internally-consistent
	 * list — the projection reads only thread-safe sources (the offer store and the
	 * volatile inventory snapshot), so there is no half-populated intermediate state.
	 */
	public List<ActiveFlip> getCurrentActiveFlips()
	{
		return projectedActiveFlips();
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
		// Check active flips (derived from the store projection)
		for (ActiveFlip flip : projectedActiveFlips())
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
	 * Set a recommendation as the current focus for Flip Assist
	 */
	private void setFocus(FlipRecommendation rec, JPanel panel)
	{
		// Exit Trades (any mode) is sell-only — never focus a new buy while it's active.
		if (plugin.getExitTradesController() != null && plugin.getExitTradesController().isActive())
		{
			return;
		}

		// Block new buy-side flips when a free user has hit their Flip Finder item cap
		PlayerSession session = plugin.getSession();
		if (session != null && plugin.isFlipFinderLimitReached())
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
	/**
	 * Sell quantity to focus on for an active flip, clamped to inventory so the
	 * panel path agrees with the GE-tracker and auto-recommend paths. Without
	 * this, a relist surfaced the raw backend quantity while the other writers
	 * clamped to what's held, and the overlay flickered between the two.
	 */
	private int sellQuantityFor(ActiveFlip flip)
	{
		return GrandExchangeTracker.resolveSellQuantity(
			flip.getTotalQuantity(), plugin.getInventoryCountSnapshot(flip.getItemId()));
	}

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
				sellQuantityFor(flip),
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
				if (smartSellPrice == null || smartSellPrice <= 0)
				{
					return;
				}
				int sellPrice = smartSellPrice;
				
				// Cache this price for future use
				displayedSellPrices.put(flip.getItemId(), sellPrice);
				
				// Create focused flip for selling with offset applied
				FocusedFlip newFocus = FocusedFlip.forSell(
					flip.getItemId(),
					flip.getItemName(),
					sellPrice,
					sellQuantityFor(flip),
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
	 * Get the displayed sell price for an item from the Active Flips panel.
	 * This is the "smart" price that considers time thresholds and market conditions.
	 * @param itemId The item ID
	 * @return The displayed sell price, or null if not cached
	 */
	public Integer getDisplayedSellPrice(int itemId)
	{
		return displayedSellPrices.get(itemId);
	}

	public void setDisplayedSellPrice(int itemId, int sellPrice)
	{
		if (sellPrice > 0)
		{
			displayedSellPrices.put(itemId, sellPrice);
		}
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
	/** The hairline rule separating a card's live-market block from its position block. */
	private static void addCardDivider(JPanel detailsPanel)
	{
		detailsPanel.add(Box.createRigidArea(new Dimension(0, 3)));
		JSeparator rowDivider = new JSeparator();
		rowDivider.setForeground(new Color(70, 70, 70));
		rowDivider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		detailsPanel.add(rowDivider);
		detailsPanel.add(Box.createRigidArea(new Dimension(0, 3)));
	}

	private JPanel createActiveFlipPanel(ActiveFlip flip)
	{
		JPanel panel = CardWidgets.createBaseItemPanel(ColorScheme.DARKER_GRAY_COLOR, 210, true);

		// Details section using BoxLayout for vertical rows
		JPanel detailsPanel = CardWidgets.createDetailsPanel(ColorScheme.DARKER_GRAY_COLOR);

		// --- Top "live" block: instant market info (HTML rows carry their own colours) ---
		CardRows rows = new CardRows(CardRow.values());
		JLabel buyLimitLabel = rows.get(CardRow.BUY_LIMIT);

		rows.addTo(detailsPanel, CardRow.ACTION, CardRow.BUY_SELL, CardRow.LIVE_MARGIN, CardRow.TAX);
		// Divider separates the "live" block from the position-reference block
		addCardDivider(detailsPanel);
		rows.addTo(detailsPanel, CardRow.CURRENT_PROFIT, CardRow.MAX_POTENTIAL, CardRow.QTY,
			CardRow.LIQUIDITY, CardRow.RISK);
		rows.set(CardRow.ACTION, PanelFormat.actionSellingHtml());

		// Array indirection: the refresh closure needs the header panels before createItemHeaderPanels produces them.
		HeaderPanels[] headerHolder = new HeaderPanels[1];
		Runnable refreshCard = () ->
		{
			apiClient.invalidateCache(flip.getItemId());
			populateActiveFlipCard(flip,
				new ActiveFlipCardPanels(panel, headerHolder[0].topPanel, headerHolder[0].namePanel, detailsPanel),
				rows);
		};
		JLabel refreshIcon = createRefreshIconLabel(refreshCard);
		HeaderPanels header = createItemHeaderPanels(flip.getItemId(), flip.getItemName(),
			ColorScheme.DARKER_GRAY_COLOR, refreshIcon, buyLimitLabel);
		headerHolder[0] = header;
		activeFlipCardRefreshers.add(refreshCard);
		allowForWrappedName(panel, header.extraNameHeight);
		JPanel topPanel = header.topPanel;
		JPanel namePanel = header.namePanel;

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		populateActiveFlipCard(flip,
			new ActiveFlipCardPanels(panel, topPanel, namePanel, detailsPanel), rows);

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
					showItemContextMenu(flip.getItemId(), flip.getItemName(), header.starLabel,
						e.getComponent(), e.getX(), e.getY(), true, () -> dismissActiveFlip(flip));
				}
			}
		});

		return panel;
	}

	/**
	 * Panels touched when (re)populating an active-flip card.
	 */
	private static class ActiveFlipCardPanels
	{
		final JPanel panel;
		final JPanel topPanel;
		final JPanel namePanel;
		final JPanel detailsPanel;

		ActiveFlipCardPanels(JPanel panel, JPanel topPanel, JPanel namePanel, JPanel detailsPanel)
		{
			this.panel = panel;
			this.topPanel = topPanel;
			this.namePanel = namePanel;
			this.detailsPanel = detailsPanel;
		}
	}

	/**
	 * Detail-row labels populated on an active-flip card.
	 */
	/**
	 * The rows a position card can show. Each carries its own placeholder and styling, so a
	 * card is described by WHICH rows it has rather than by code that builds each one.
	 */
	private enum CardRow
	{
		ACTION("Action: ...", Color.WHITE, false),
		BUY_SELL("Live Price: ...", Color.WHITE, false),
		LIVE_MARGIN("Live Margin: ...", Color.WHITE, false),
		TAX("Tax: ...", COLOR_TEXT_GRAY, true),
		CURRENT_PROFIT("Current Profit: ...", Color.WHITE, false),
		MAX_POTENTIAL("Max Potential Profit: ...", Color.WHITE, true),
		QTY("Qty: ...", COLOR_TEXT_GRAY, true),
		LIQUIDITY("Liquidity: ...", Color.CYAN, false),
		RISK("Risk: ...", COLOR_YELLOW, false),
		BUY_LIMIT("Buy limit: ...", COLOR_TEXT_GRAY, true);

		private final String placeholder;
		private final Color colour;
		private final boolean small;

		CardRow(String placeholder, Color colour, boolean small)
		{
			this.placeholder = placeholder;
			this.colour = colour;
			this.small = small;
		}

		private JLabel create()
		{
			JLabel label = CardWidgets.createStyledLabel(placeholder, colour);
			if (small)
			{
				label.setFont(FONT_PLAIN_11);
			}
			return label;
		}
	}

	/**
	 * A card's rows, keyed rather than bound to named locals. Keying is what makes one
	 * builder serve several card types: population addresses a row by name instead of the
	 * caller having to hold nine separate references.
	 */
	private static final class CardRows
	{
		private final EnumMap<CardRow, JLabel> labels = new EnumMap<>(CardRow.class);

		private CardRows(CardRow... rows)
		{
			for (CardRow row : rows)
			{
				labels.put(row, row.create());
			}
		}

		private JLabel get(CardRow row)
		{
			return labels.get(row);
		}

		/** Silently ignores rows this card does not carry, so specs can differ safely. */
		private void set(CardRow row, String text)
		{
			JLabel label = labels.get(row);
			if (label != null)
			{
				label.setText(text);
			}
		}

		private void addTo(JPanel panel, CardRow... rows)
		{
			JLabel[] present = java.util.Arrays.stream(rows)
				.map(labels::get)
				.filter(java.util.Objects::nonNull)
				.toArray(JLabel[]::new);
			CardWidgets.addLabelsWithSpacing(panel, present);
		}
	}


	/**
	 * Fetch fresh market analysis for an active-flip card and (re)populate its rows.
	 * Reused by the initial card build and the manual refresh button.
	 */
	private void populateActiveFlipCard(ActiveFlip flip, ActiveFlipCardPanels panels, CardRows rows)
	{
		apiClient.getItemAnalysisAsync(flip.getItemId()).thenAccept(analysis ->
			SwingUtilities.invokeLater(() ->
			{
				Integer high = null;
				Integer low = null;
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
						high = analysis.getCurrentPrices().getHigh();
						low = analysis.getCurrentPrices().getLow();
					}
				}

				// Realized fills (sold-so-far) drive both Qty and Current Profit; compute once.
				RealizedFlipProfit.Result realized = RealizedFlipProfit.compute(
					plugin.getOfferStore().forItem(flip.getItemId()), flip.getItemId(), flip.getAverageBuyPrice(),
					flip.getFirstBuyTime() != null ? TimeUtils.parseIsoToMillis(flip.getFirstBuyTime()) : 0L);
				long fullQty = (long) realized.soldQuantity + flip.getTotalQuantity();
				rows.set(CardRow.QTY, PanelFormat.qtyHtml(realized.soldQuantity, fullQty));

				// The flip's own sell price (session-recommended, else computed) drives both the
				// side effects and the Max Potential Profit figure.
				Integer flipSellPrice = resolveSmartSellPrice(flip, high, plugin.getSession());
				applySmartSellSideEffects(flip, panels, flipSellPrice);

				rows.set(CardRow.BUY_LIMIT, 
					"Buy limit: " + (buyLimit != null ? PanelFormat.formatGP(buyLimit) : "?"));

				if (hasValidMarketPrices(high, low))
				{
					populateActiveFlipMarketRows(flip, panels, rows, low, high, flipSellPrice, realized, fullQty);
				}
				else
				{
					rows.set(CardRow.BUY_SELL, "Live Price: N/A");
					rows.set(CardRow.LIVE_MARGIN, "Live Margin: N/A");
					rows.set(CardRow.CURRENT_PROFIT, "Current Profit: N/A");
					rows.set(CardRow.MAX_POTENTIAL, "Max Potential Profit: N/A");
					rows.set(CardRow.TAX, "Tax: N/A");
					panels.panel.setToolTipText(null);
				}

				updateLiquidityLabel(rows.get(CardRow.LIQUIDITY), liquidity);
				updateRiskLabel(rows.get(CardRow.RISK), risk);
			}));
	}

	private static boolean hasValidMarketPrices(Integer high, Integer low)
	{
		return high != null && high > 0 && low != null && low > 0;
	}

	/**
	 * The flip's sell price drives the session write, Flip Assist focus update and
	 * price-indicator background, even though the price itself is not displayed.
	 */
	private void applySmartSellSideEffects(ActiveFlip flip, ActiveFlipCardPanels panels, Integer smartSellPrice)
	{
		if (smartSellPrice == null)
		{
			return;
		}

		displayedSellPrices.put(flip.getItemId(), smartSellPrice);
		applyPriceIndicatorIfNeeded(flip, panels, smartSellPrice);
		updateFocusIfSelected(flip, smartSellPrice);
	}

	/**
	 * Prefer the session's already-recommended price; otherwise compute one and persist
	 * it to the session so later cards/refreshes stay pinned to the same price.
	 */
	private static Integer resolveSmartSellPrice(ActiveFlip flip, Integer high, PlayerSession session)
	{
		Integer sessionPrice = session != null ? session.getRecommendedPrice(flip.getItemId()) : null;
		if (isValidPrice(sessionPrice))
		{
			return sessionPrice;
		}

		Integer computedPrice = SmartSellPricer.calculateSmartSellPrice(flip, high);
		// With no basis the price is a live-market reading, not a target. Persisting it would
		// pin this instant's market for the session and outrank the real breakeven once it returns.
		if (isValidPrice(computedPrice) && session != null && flip.getAverageBuyPrice() > 0)
		{
			session.setRecommendedPrice(flip.getItemId(), computedPrice);
		}
		return isValidPrice(computedPrice) ? computedPrice : null;
	}

	private static boolean isValidPrice(Integer price)
	{
		return price != null && price > 0;
	}

	private void applyPriceIndicatorIfNeeded(ActiveFlip flip, ActiveFlipCardPanels panels, int smartSellPrice)
	{
		Integer recommendedPrice = flip.getRecommendedSellPrice();
		boolean focusedOnThisFlip = currentFocus != null && currentFocus.getItemId() == flip.getItemId();
		if (recommendedPrice == null || recommendedPrice <= 0 || smartSellPrice == recommendedPrice || focusedOnThisFlip)
		{
			return;
		}

		Color priceIndicatorBg = smartSellPrice > recommendedPrice
			? COLOR_PRICE_HIGHER_BG
			: COLOR_PRICE_LOWER_BG;
		CardWidgets.applyPriceIndicatorBackground(panels.panel, panels.topPanel, panels.namePanel, panels.detailsPanel, priceIndicatorBg);
	}

	private void updateFocusIfSelected(ActiveFlip flip, int smartSellPrice)
	{
		if (currentFocus == null || currentFocus.getItemId() != flip.getItemId() || !currentFocus.isSelling())
		{
			return;
		}

		FocusedFlip updatedFocus = FocusedFlip.forSell(
			flip.getItemId(), flip.getItemName(), smartSellPrice,
			sellQuantityFor(flip), config.priceOffset());
		// Re-pushing an unchanged focus clears the overlay's active prompt and re-writes
		// the GE search varp, so a periodic refresh must not do it.
		if (updatedFocus.equals(currentFocus))
		{
			return;
		}
		currentFocus = updatedFocus;
		if (onFocusChanged != null)
		{
			onFocusChanged.accept(updatedFocus);
		}
	}

	/**
	 * Render the market-based detail rows (live price, live margin, tax) plus the
	 * position rows (current profit, max potential profit) once prices are available.
	 */
	private void populateActiveFlipMarketRows(ActiveFlip flip, ActiveFlipCardPanels panels,
		CardRows rows, int low, int high, Integer flipSellPrice,
		RealizedFlipProfit.Result realized, long fullQty)
	{
		ActiveFlipCardMetrics.Result metrics = ActiveFlipCardMetrics.compute(
			low, high, flip.getItemId(), flip.getAverageBuyPrice(), realized.soldQuantity, flip.getTotalQuantity());

		if (metrics.positionNetPerUnit < 0)
		{
			panels.panel.setToolTipText("Selling at the current price would be a loss - below your buy price plus tax.");
		}
		else
		{
			panels.panel.setToolTipText(null);
		}

		// Max Potential Profit uses the flip's OWN buy/sell prices (not the wiki spread):
		// (sell - buy - tax) x full flip quantity.
		long maxPotentialProfit = 0L;
		if (flipSellPrice != null && flipSellPrice > 0)
		{
			int sellTax = GeTax.taxFor(flip.getItemId(), flipSellPrice);
			maxPotentialProfit = ((long) flipSellPrice - flip.getAverageBuyPrice() - sellTax) * fullQty;
		}

		// Colours are baked into the HTML by PanelFormat, so no setForeground needed here.
		rows.set(CardRow.BUY_SELL, PanelFormat.livePriceHtml(low, high));
		rows.set(CardRow.LIVE_MARGIN, PanelFormat.liveMarginHtml(metrics.margin, metrics.roi));
		rows.set(CardRow.TAX, PanelFormat.taxHtml(metrics.totalTax));
		rows.set(CardRow.CURRENT_PROFIT, PanelFormat.currentProfitHtml(realized.netProfit));
		rows.set(CardRow.MAX_POTENTIAL, PanelFormat.maxPotentialProfitHtml(maxPotentialProfit));
	}

	/**
	 * Create a panel for a pending order (not yet filled)
	 * Uses same detailed layout as active flips with Liquidity/Risk data
	 */
	private JPanel createPendingOrderPanel(PendingOrder pending)
	{
		Color bgColor = new Color(55, 55, 65); // Slightly different color for pending
		JPanel panel = CardWidgets.createBaseItemPanel(bgColor, 210, false);

		JPanel detailsPanel = CardWidgets.createDetailsPanel(bgColor);

		// --- Top "live" block ---
		// No Current Profit row — nothing has sold yet on a pending buy.
		CardRows rows = new CardRows(CardRow.ACTION, CardRow.BUY_SELL, CardRow.LIVE_MARGIN, CardRow.TAX,
			CardRow.MAX_POTENTIAL, CardRow.QTY, CardRow.LIQUIDITY, CardRow.RISK, CardRow.BUY_LIMIT);
		JLabel buyLimitLabel = rows.get(CardRow.BUY_LIMIT);

		rows.addTo(detailsPanel, CardRow.ACTION, CardRow.BUY_SELL, CardRow.LIVE_MARGIN, CardRow.TAX);
		addCardDivider(detailsPanel);
		rows.addTo(detailsPanel, CardRow.MAX_POTENTIAL, CardRow.QTY, CardRow.LIQUIDITY, CardRow.RISK);
		rows.set(CardRow.ACTION, PanelFormat.actionBuyingHtml());

		// Header with refresh + corner buy-limit, matching the active-flip cards
		Runnable refreshCard = () ->
		{
			apiClient.invalidateCache(pending.itemId);
			populatePendingCardRows(pending, rows);
		};
		activeFlipCardRefreshers.add(refreshCard);
		JLabel refreshIcon = createRefreshIconLabel(refreshCard);
		HeaderPanels header = createItemHeaderPanels(pending.itemId, pending.itemName, bgColor, refreshIcon, buyLimitLabel);
		allowForWrappedName(panel, header.extraNameHeight);
		JPanel topPanel = header.topPanel;
		JPanel namePanel = header.namePanel;

		panel.add(topPanel, BorderLayout.NORTH);
		panel.add(detailsPanel, BorderLayout.CENTER);

		populatePendingCardRows(pending, rows);

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
	 * Fetch market analysis and (re)populate a pending buy card's rows. Reused by the
	 * initial build and the refresh button. Mirrors the active-flip card layout minus
	 * Current Profit (nothing is sold on a pending buy).
	 */
	private void populatePendingCardRows(PendingOrder pending, CardRows rows)
	{
		rows.set(CardRow.QTY, PanelFormat.qtyHtml(pending.quantityFilled, pending.quantity));

		apiClient.getItemAnalysisAsync(pending.itemId).thenAccept(analysis ->
			SwingUtilities.invokeLater(() ->
			{
				Integer high = null;
				Integer low = null;
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
						high = analysis.getCurrentPrices().getHigh();
						low = analysis.getCurrentPrices().getLow();
					}
				}

				rows.set(CardRow.BUY_LIMIT, "Buy limit: " + (buyLimit != null ? PanelFormat.formatGP(buyLimit) : "?"));

				if (high != null && high > 0 && low != null && low > 0)
				{
					int margin = high - low;
					double roi = (margin * 100.0) / low;
					long totalTax = (long) GeTax.taxFor(pending.itemId, high) * pending.quantity;
					rows.set(CardRow.BUY_SELL, PanelFormat.livePriceHtml(low, high));
					rows.set(CardRow.LIVE_MARGIN, PanelFormat.liveMarginHtml(margin, roi));
					rows.set(CardRow.TAX, PanelFormat.taxHtml(totalTax));
				}
				else
				{
					rows.set(CardRow.BUY_SELL, "Live Price: N/A");
					rows.set(CardRow.LIVE_MARGIN, "Live Margin: N/A");
					rows.set(CardRow.TAX, "Tax: N/A");
				}

				// Max Potential Profit for the pending buy: (sell - buy - tax) x ordered qty,
				// where sell is the flip's recommended sell price (else the current market high).
				Integer sellPrice = pending.recommendedSellPrice != null && pending.recommendedSellPrice > 0
					? pending.recommendedSellPrice : high;
				long maxPotentialProfit = 0L;
				if (sellPrice != null && sellPrice > 0)
				{
					int sellTax = GeTax.taxFor(pending.itemId, sellPrice);
					maxPotentialProfit = ((long) sellPrice - pending.pricePerItem - sellTax) * pending.quantity;
				}
				rows.set(CardRow.MAX_POTENTIAL, PanelFormat.maxPotentialProfitHtml(maxPotentialProfit));

				updateLiquidityLabel(rows.get(CardRow.LIQUIDITY), liquidity);
				updateRiskLabel(rows.get(CardRow.RISK), risk);
			}));
	}
	
	/**
	 * Build a small, non-alarming confidence badge for a completed flip, or
	 * {@code null} when the flip is fully confident (nothing to show).
	 */
	private JLabel buildConfidenceLabel(CompletedFlip flip)
	{
		if (!flip.isPriceIsEstimated() && !flip.isQuantityAnomaly())
		{
			return null;
		}

		StringBuilder text = new StringBuilder(240).append("<html>");
		StringBuilder tip = new StringBuilder(260);
		if (flip.isQuantityAnomaly())
		{
			text.append("<span style='color:").append(COLOR_ANOMALY_HEX).append(";'>&#9888; Anomaly: Quantity Flagged (Edit)</span>");
			tip.append("Recorded quantity looks higher than the item's 4h GE buy limit allows, so it may be over-counted. ");
		}
		if (flip.isPriceIsEstimated())
		{
			if (flip.isQuantityAnomaly())
			{
				text.append("<br>");
			}
			text.append("<span style='color:").append(COLOR_ANOMALY_HEX).append(";'>&#9888; Anomaly: Profit Flagged (Edit)</span>");
			tip.append("Price was recovered from offline trade history as a blended average, so the profit is an estimate. ");
		}
		text.append("</html>");

		JLabel label = new JLabel(text.toString());
		label.setFont(FONT_PLAIN_12);
		label.setToolTipText(tip.append("Click to edit this trade on the website.").toString());
		label.setCursor(new Cursor(Cursor.HAND_CURSOR));
		final int flipId = flip.getId();
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(FLIP_EDIT_URL + flipId);
			}
		});
		return label;
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
		allowForWrappedName(panel, header.extraNameHeight);
		JPanel topPanel = header.topPanel;
		final JLabel completedStarLabel = header.starLabel;

		// Details section with profit/loss info - use GridBagLayout for tighter column spacing
		JPanel detailsPanel = CardWidgets.panel(new GridBagLayout(), backgroundColor);
		detailsPanel.setBorder(new EmptyBorder(3, 0, 0, 0));
		
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(1, 0, 1, 15); // 15px gap between columns

		// Row 1: Quantity and Buy Price
		JLabel qtyLabel = CardWidgets.label(String.format(FORMAT_QTY, flip.getQuantity()), COLOR_TEXT_GRAY, FONT_PLAIN_12);

		JLabel buyPriceLabel = CardWidgets.label(String.format("Buy: %s", PanelFormat.formatGPExact(flip.getBuyPricePerItem())), COLOR_BUY_RED, FONT_PLAIN_12);

		// Row 2: Invested and Sell Price
		JLabel investedLabel = CardWidgets.label(String.format("Cost: %s", PanelFormat.formatGP(flip.getBuyTotal())), COLOR_TEXT_GRAY, FONT_PLAIN_12);

		JLabel sellPriceLabel = CardWidgets.label(String.format(FORMAT_SELL, PanelFormat.formatGPExact(flip.getSellPricePerItem())), COLOR_SELL_GREEN, FONT_PLAIN_12);

		// Row 3: Net Profit and ROI
		JLabel profitLabel = new JLabel(String.format("Profit: %s", PanelFormat.formatGP(flip.getNetProfit())));
		Color profitColor = flip.isSuccessful() ? 
			COLOR_PROFIT_GREEN : // Bright green
			COLOR_LOSS_RED;  // Bright red
		profitLabel.setForeground(profitColor);
		profitLabel.setFont(FONT_BOLD_12);

		JLabel roiLabel = CardWidgets.label(String.format(FORMAT_ROI, flip.getRoiPercent()), profitColor, FONT_BOLD_12);

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

		// Confidence signal: badge flips whose price is an offline-history
		// estimate or whose quantity looks over the GE buy limit, mirroring the
		// web dashboard so a blended offline price isn't shown as exact.
		JLabel confidenceLabel = buildConfidenceLabel(flip);
		if (confidenceLabel != null)
		{
			gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 0;
			gbc.fill = GridBagConstraints.NONE; gbc.insets = new Insets(3, 0, 0, 0);
			detailsPanel.add(confidenceLabel, gbc);
			gbc.gridwidth = 1;
			if (flip.isPriceIsEstimated() && flip.isQuantityAnomaly())
			{
				allowForWrappedName(panel, BADGE_SECOND_LINE_HEIGHT);
			}
		}

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
				// Right-click opens the context menu (handled in mousePressed/mouseReleased); left-click expands.
				if (e.getButton() != MouseEvent.BUTTON1 || e.isPopupTrigger())
				{
					return;
				}
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

					JLabel durationLabel = CardWidgets.label(String.format("Duration: %s", duration), COLOR_TEXT_DIM_GRAY, FONT_PLAIN_12);

					// GE Tax
					JLabel taxLabel = CardWidgets.label(String.format("GE Tax: %s", PanelFormat.formatGP(flip.getGeTax())), COLOR_TEXT_DIM_GRAY, FONT_PLAIN_12);

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

			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShowCompletedMenu(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowCompletedMenu(e);
			}

			private void maybeShowCompletedMenu(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showItemContextMenu(flip.getItemId(), flip.getItemName(), completedStarLabel,
						e.getComponent(), e.getX(), e.getY());
				}
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
			service.setOnQueueAdvanced(this::repopulateRecommendationsGated);

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
			tabbedPane.setSelectedIndex(TAB_RECOMMENDED);

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
			repopulateRecommendationsGated();

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

