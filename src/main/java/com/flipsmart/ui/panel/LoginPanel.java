package com.flipsmart.ui.panel;

import com.flipsmart.FlipSmartApiClient;
import com.flipsmart.FlipSmartConfig;
import com.flipsmart.api.dto.AuthResult;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Login / authentication sub-panel. Owns the login UI, the email/password
 * credential flow, the Discord device-auth flow (and its scheduler/timers),
 * and credential persistence. The API client is the source of truth for
 * authentication state.
 */
@Slf4j
public class LoginPanel
{
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String CONFIG_KEY_EMAIL = "email";
	private static final String CONFIG_KEY_PASSWORD = "password";  // Deprecated: kept for migration
	private static final String CONFIG_KEY_REFRESH_TOKEN = "refreshToken";

	private static final String FONT_ARIAL = "Arial";
	private static final Font FONT_PLAIN_12 = new Font(FONT_ARIAL, Font.PLAIN, 12);
	private static final Color COLOR_PROFIT_GREEN = new Color(100, 255, 100);
	private static final Color COLOR_LOSS_RED = new Color(255, 100, 100);

	private final transient FlipSmartConfig config;
	private final transient FlipSmartApiClient apiClient;
	private final transient ConfigManager configManager;

	// Callback to run the plugin's post-login work (e.g. RSN sync).
	private final transient Runnable onAuthSuccess;
	// Callback to transition the host panel from login view to main view.
	private final transient Runnable onShowMainPanel;

	private final JPanel loginPanel;
	private JTextField emailField;
	private JPasswordField passwordField;
	private JLabel loginStatusLabel;
	private JButton loginButton;
	private JButton signupButton;
	private JButton discordButton;

	// Discord device auth polling
	private ScheduledExecutorService deviceAuthScheduler;
	private ScheduledFuture<?> deviceAuthPollTask;
	private volatile String currentDeviceCode;

	// Auth transition timer (tracked for cleanup)
	private Timer authTransitionTimer;

	public LoginPanel(FlipSmartApiClient apiClient, FlipSmartConfig config, ConfigManager configManager,
		Runnable onAuthSuccess, Runnable onShowMainPanel)
	{
		this.apiClient = apiClient;
		this.config = config;
		this.configManager = configManager;
		this.onAuthSuccess = onAuthSuccess;
		this.onShowMainPanel = onShowMainPanel;

		this.loginPanel = buildLoginPanel();

		// Persist refresh token whenever it changes (rotation, new login, etc.)
		// This prevents token loss from mid-session rotations via the 401 retry path
		apiClient.setOnRefreshTokenChanged(this::saveRefreshToken);
	}

	/**
	 * The built login panel component.
	 */
	public JPanel getComponent()
	{
		return loginPanel;
	}

	/**
	 * Pre-fill the email field from a known address (e.g. on session expiry).
	 */
	public void setEmailField(String email)
	{
		if (email != null && !email.isEmpty())
		{
			emailField.setText(email);
		}
	}

	/**
	 * Set the login status text and colour directly (used by host on session
	 * expiry / logout messaging).
	 */
	public void setLoginStatusText(String message, Color color)
	{
		loginStatusLabel.setText(message);
		loginStatusLabel.setForeground(color);
	}

	/**
	 * Clear the password field (e.g. on logout).
	 */
	public void clearPasswordField()
	{
		passwordField.setText("");
	}

	/**
	 * Reset the login view: stop device-auth polling and re-enable buttons.
	 * Called by the host when switching back to the login panel.
	 */
	public void resetLoginView()
	{
		stopDeviceAuthPolling();
		setLoginButtonsEnabled(true);
	}

	/**
	 * Build the login/signup panel
	 */
	private JPanel buildLoginPanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Center content panel
		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setBorder(BorderFactory.createEmptyBorder(40, 20, 40, 20));

		// Title
		JLabel titleLabel = new JLabel("FlipSmart");
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
		panel.add(contentPanel, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * Check if already authenticated and show appropriate panel.
	 * Tries refresh token first (secure), falls back to legacy password if needed.
	 */
	public void checkAuthenticationAndShow()
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

			apiClient.refreshAccessTokenAsync().thenAccept(success ->
				SwingUtilities.invokeLater(() -> {
					if (Boolean.TRUE.equals(success))
					{
						saveRefreshToken(apiClient.getRefreshToken());
						onAuthenticationSuccess(null, false);
					}
					else
					{
						// Only clear persisted token if explicitly rejected (401)
						if (apiClient.getRefreshToken() == null)
						{
							clearRefreshToken();
						}
						else
						{
							log.debug("Refresh token auth failed (transient) - keeping token for next attempt");
						}
						tryLegacyPasswordAuth();
					}
				})
			).exceptionally(e -> {
				log.debug("Refresh token auth failed: {}", e.getMessage());
				SwingUtilities.invokeLater(this::tryLegacyPasswordAuth);
				return null;
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
			CompletableFuture.runAsync(() -> {
				AuthResult result = apiClient.login(email, password);

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

		CompletableFuture.runAsync(() -> {
			AuthResult result = apiClient.login(email, password);

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

		CompletableFuture.runAsync(() -> {
			AuthResult result = apiClient.signup(email, password);

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
	public void clearRefreshToken()
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
			Timer timer = new Timer(500, e ->
			{
				onShowMainPanel.run();
				authTransitionTimer = null;
			});
			timer.setRepeats(false);
			authTransitionTimer = timer;
			timer.start();
		}
		else
		{
			onShowMainPanel.run();
		}
	}

	/**
	 * Clean up login-owned resources (device-auth scheduler/timers).
	 */
	public void shutdown()
	{
		stopDeviceAuthPolling();
		if (deviceAuthScheduler != null)
		{
			deviceAuthScheduler.shutdownNow();
			deviceAuthScheduler = null;
		}
		if (authTransitionTimer != null)
		{
			authTransitionTimer.stop();
			authTransitionTimer = null;
		}
	}
}
