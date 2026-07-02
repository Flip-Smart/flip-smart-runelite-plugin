package com.flipsmart.api;

import com.flipsmart.FlipSmartConfig;
import com.flipsmart.api.dto.AuthResult;
import com.flipsmart.api.dto.DeviceAuthResponse;
import com.flipsmart.api.dto.EntitlementsResponse;
import com.flipsmart.api.dto.DeviceStatusResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import okhttp3.OkHttpClient;

/**
 * Transport + auth/session core for the FlipSmart API.
 *
 * Owns the OkHttp client, Gson, config, the shared {@link ApiBackoffGate} and ALL
 * mutable authentication/session state (JWT, refresh token, premium/blocked flags,
 * callbacks, auth coalescing and the 401 re-auth retry). Endpoint groups in
 * {@code com.flipsmart.api.endpoints} are stateless and route every HTTP call
 * through this class.
 */
@Slf4j
public class ApiHttpTransport
{
	public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final String PRODUCTION_API_URL = "https://api.flipsm.art";
	private static final String ACCESS_TOKEN_KEY = "access_token";
	private static final String REFRESH_TOKEN_KEY = "refresh_token";
	private static final String JSON_KEY_IS_PREMIUM = "is_premium";
	private static final String JSON_KEY_STATUS = "status";
	private static final String DEVICE_INFO = "RuneLite Plugin";
	private static final String HEADER_AUTHORIZATION = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";
	private static final int HTTP_SERVER_ERROR_THRESHOLD = 500;

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final FlipSmartConfig config;
	private final ApiBackoffGate backoffGate;

	// JWT token management — all access guarded by authLock
	private String jwtToken = null;
	private long tokenExpiry = 0;

	// Refresh token for persistent login (stored via config, but kept in memory for use)
	private String refreshToken = null;

	// Premium status (updated on login)
	private boolean isPremium = false;

	// RSN-level blocked status (updated on entitlements fetch)
	private boolean isRsnBlocked = false;

	// Lock for authentication to prevent concurrent auth attempts
	private final Object authLock = new Object();

	// Callback when authentication permanently fails (both refresh token and password fail)
	private volatile Runnable onAuthFailure = null;

	// Callback when refresh token changes (for persistence to config)
	private volatile Consumer<String> onRefreshTokenChanged = null;

	// Coalesce concurrent authenticateAsync() calls into a single attempt
	private volatile CompletableFuture<Boolean> pendingAuthFuture = null;

	public ApiHttpTransport(OkHttpClient httpClient, Gson gson, FlipSmartConfig config)
	{
		this(httpClient, gson, config, new ApiBackoffGate());
	}

	ApiHttpTransport(OkHttpClient httpClient, Gson gson, FlipSmartConfig config, ApiBackoffGate backoffGate)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
		this.backoffGate = backoffGate;
	}

	public Gson getGson()
	{
		return gson;
	}

	/**
	 * Deserialize a response body into a DTO using the transport's Gson. Centralizes
	 * the {@code gson.fromJson(body, T.class)} boilerplate that each endpoint group
	 * previously hand-rolled; behavior is identical to a direct Gson call (returns
	 * {@code null} for an empty/null body).
	 */
	public <T> T parse(String body, Class<T> type)
	{
		return gson.fromJson(body, type);
	}

	public OkHttpClient getHttpClient()
	{
		return httpClient;
	}

	/**
	 * Get the API URL to use. Returns the configured override URL if set,
	 * otherwise returns the production URL.
	 */
	public String getApiUrl()
	{
		String configuredUrl = config.apiUrl();
		if (configuredUrl == null || configuredUrl.isEmpty())
		{
			return PRODUCTION_API_URL;
		}
		return configuredUrl;
	}

	/**
	 * URL-encode a value for use in query parameters.
	 * Handles RSNs with spaces and special characters.
	 */
	public static String urlEncode(String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/**
	 * Thread-safe read of the JWT token.
	 */
	private String getJwtToken()
	{
		synchronized (authLock)
		{
			return jwtToken;
		}
	}

	/**
	 * Thread-safe compound check: token exists and is not expired.
	 */
	private boolean isTokenValid()
	{
		synchronized (authLock)
		{
			return jwtToken != null && System.currentTimeMillis() < tokenExpiry;
		}
	}

	/**
	 * Add Authorization header with Bearer token to a request builder.
	 */
	private Request.Builder withAuthHeader(Request.Builder builder)
	{
		return builder.header(HEADER_AUTHORIZATION, BEARER_PREFIX + getJwtToken());
	}

	/**
	 * Build an authenticated GET request for a fully-formed URL. Used by endpoint
	 * groups that pre-build their URL via {@link okhttp3.HttpUrl} and need the
	 * Bearer header applied without the executeAuthenticatedAsync wrapper (e.g.
	 * fire-and-forget callback-style endpoints).
	 */
	public Request withAuthGet(okhttp3.HttpUrl url)
	{
		return withAuthHeader(new Request.Builder().url(url))
			.get()
			.build();
	}

	/**
	 * Execute an HTTP request asynchronously with automatic retry on 401
	 * This is the core method that handles all HTTP requests off the main threads
	 *
	 * @param request The request to execute
	 * @param responseHandler Function to process successful response body and return result
	 * @param errorHandler Consumer to handle errors
	 * @param retryOnAuth Whether to retry with re-authentication on 401
	 * @param <T> The return type
	 * @return CompletableFuture with the result
	 */
	public <T> CompletableFuture<T> executeAsync(Request request, Function<String, T> responseHandler,
												 Consumer<String> errorHandler, boolean retryOnAuth)
	{
		if (!backoffGate.allowRequest())
		{
			if (errorHandler != null)
			{
				errorHandler.accept("API backing off after repeated failures");
			}
			return CompletableFuture.completedFuture(null);
		}

		CompletableFuture<T> future = new CompletableFuture<>();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				backoffGate.recordFailure();
				log.debug("Request failed: {}", e.getMessage());
				if (errorHandler != null)
				{
					errorHandler.accept("Connection error: " + e.getMessage());
				}
				future.complete(null);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (response.code() == 401 && retryOnAuth)
					{
						// Token might have expired, try to re-authenticate
						log.debug("Received 401, attempting to re-authenticate");
						synchronized (authLock)
						{
							jwtToken = null;
						}

						authenticateAsync().thenAccept(authSuccess ->
						{
							if (authSuccess)
							{
								// Rebuild request with new token
								Request retryRequest = withAuthHeader(request.newBuilder())
									.build();

								// Retry without auth retry to prevent infinite loop
								executeAsync(retryRequest, responseHandler, errorHandler, false)
									.thenAccept(future::complete);
							}
							else
							{
								// Auth permanently failed - notify so UI can show login screen
								notifyAuthFailure();
								future.complete(null);
							}
						});
						return;
					}

					if (!response.isSuccessful())
					{
						if (response.code() >= HTTP_SERVER_ERROR_THRESHOLD)
						{
							backoffGate.recordFailure();
						}
						log.debug("Request returned error: {}", response.code());
						if (errorHandler != null)
						{
							errorHandler.accept("Error " + response.code());
						}
						future.complete(null);
						return;
					}

					backoffGate.recordSuccess();
					okhttp3.ResponseBody responseBody = response.body();
					String jsonData = responseBody != null ? responseBody.string() : "";
					T result = responseHandler.apply(jsonData);
					future.complete(result);
				}
				catch (Exception e)
				{
					log.debug("Error processing response: {}", e.getMessage());
					future.complete(null);
				}
			}
		});

		return future;
	}

	/**
	 * Execute an authenticated request asynchronously
	 */
	public <T> CompletableFuture<T> executeAuthenticatedAsync(Request.Builder requestBuilder,
															  Function<String, T> responseHandler)
	{
		return ensureAuthenticatedAsync().thenCompose(authenticated ->
		{
			if (!authenticated)
			{
				log.debug("Failed to authenticate");
				return CompletableFuture.completedFuture(null);
			}

			Request request = withAuthHeader(requestBuilder)
				.build();

			return executeAsync(request, responseHandler, null, true);
		});
	}

	/**
	 * Check if we have a valid JWT token, and refresh if needed (async)
	 */
	public CompletableFuture<Boolean> ensureAuthenticatedAsync()
	{
		// Check if we have a token and it's not expired
		if (isTokenValid())
		{
			return CompletableFuture.completedFuture(true);
		}

		// Token is missing or expired, authenticate
		return authenticateAsync();
	}

	/**
	 * Authenticate with the API and obtain a JWT token.
	 * Tries refresh token first (if available), falls back to email/password.
	 * Coalesces concurrent calls — if auth is already in progress, returns the pending future.
	 */
	private CompletableFuture<Boolean> authenticateAsync()
	{
		synchronized (authLock)
		{
			// Coalesce concurrent auth attempts — return pending future if auth is in progress
			if (pendingAuthFuture != null && !pendingAuthFuture.isDone())
			{
				return pendingAuthFuture;
			}

			CompletableFuture<Boolean> future = doAuthenticateAsync();
			pendingAuthFuture = future;
			// Clear reference when done to allow future auth attempts
			future.whenComplete((result, ex) -> {
				synchronized (authLock)
				{
					if (pendingAuthFuture == future)
					{
						pendingAuthFuture = null;
					}
				}
			});
			return future;
		}
	}

	/**
	 * Internal authentication logic. Called by authenticateAsync() which handles coalescing.
	 */
	private CompletableFuture<Boolean> doAuthenticateAsync()
	{
		// Try refresh token first if we have one
		String currentRefreshToken = refreshToken;

		if (currentRefreshToken != null && !currentRefreshToken.isEmpty())
		{
			return refreshAccessTokenAsync()
				.thenCompose(success -> {
					if (success)
					{
						return CompletableFuture.completedFuture(true);
					}
					// refreshAccessTokenAsync already clears refreshToken on 401.
					// Fall back to password auth.
					return loginWithPasswordAsync();
				});
		}

		return loginWithPasswordAsync();
	}

	/**
	 * Authenticate using stored email/password (legacy fallback)
	 */
	private CompletableFuture<Boolean> loginWithPasswordAsync()
	{
		return loginAsync(config.email(), config.password())
			.thenApply(result -> result.success);
	}

	/**
	 * Login with email and password (async)
	 * @return CompletableFuture with AuthResult containing success status and message
	 */
	public CompletableFuture<AuthResult> loginAsync(String email, String password)
	{
		CompletableFuture<AuthResult> future = new CompletableFuture<>();

		AuthResult validationError = validateLoginCredentials(email, password);
		if (validationError != null)
		{
			future.complete(validationError);
			return future;
		}

		Request request = buildLoginRequest(email, password);

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Failed to authenticate with API: {}", e.getMessage());
				future.complete(new AuthResult(false, "Connection error: " + e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				future.complete(handleLoginResponse(response));
			}
		});

		return future;
	}

	/**
	 * Validate login credentials and return error if invalid
	 */
	private AuthResult validateLoginCredentials(String email, String password)
	{
		if (email == null || email.isEmpty())
		{
			return new AuthResult(false, "Please enter your email address");
		}
		if (password == null || password.isEmpty())
		{
			return new AuthResult(false, "Please enter your password");
		}
		return null;
	}

	/**
	 * Build the login HTTP request (includes refresh token request for persistent login)
	 */
	private Request buildLoginRequest(String email, String password)
	{
		// Request refresh token for persistent plugin login
		String url = String.format("%s/auth/login?include_refresh=true&device_info=%s",
			getApiUrl(), DEVICE_INFO);

		JsonObject jsonBody = new JsonObject();
		jsonBody.addProperty("email", email);
		jsonBody.addProperty("password", password);
		RequestBody body = RequestBody.create(JSON, jsonBody.toString());

		return new Request.Builder()
			.url(url)
			.post(body)
			.build();
	}

	/**
	 * Handle login response and return appropriate AuthResult
	 */
	private AuthResult handleLoginResponse(Response response)
	{
		try (response)
		{
			if (!response.isSuccessful())
			{
				return handleLoginError(response.code());
			}

			processSuccessfulLogin(response);
			log.debug("Successfully authenticated with API (premium: {})", isPremium());
			return new AuthResult(true, "Login successful!");
		}
		catch (Exception e)
		{
			log.error("Error processing login response: {}", e.getMessage());
			return new AuthResult(false, "Error processing response");
		}
	}

	/**
	 * Map login error codes to user-friendly messages
	 */
	private AuthResult handleLoginError(int code)
	{
		switch (code)
		{
			case 401:
				return new AuthResult(false, "Incorrect email or password");
			case 404:
				return new AuthResult(false, "Account not found. Please sign up first.");
			default:
				return new AuthResult(false, "Login failed (error " + code + ")");
		}
	}

	/**
	 * Process successful login response and store tokens
	 */
	private void processSuccessfulLogin(Response response) throws IOException
	{
		okhttp3.ResponseBody responseBody = response.body();
		String jsonData = responseBody != null ? responseBody.string() : "";
		JsonObject tokenResponse = gson.fromJson(jsonData, JsonObject.class);
		processTokenResponse(tokenResponse);
	}

	/**
	 * Process token response and store access token, refresh token, and premium status.
	 * Used by both login and refresh token flows to avoid code duplication.
	 *
	 * @param tokenResponse The JSON response containing access_token, optional refresh_token, and is_premium
	 */
	private void processTokenResponse(JsonObject tokenResponse)
	{
		String newRefreshToken = null;
		synchronized (authLock)
		{
			jwtToken = tokenResponse.get(ACCESS_TOKEN_KEY).getAsString();
			tokenExpiry = System.currentTimeMillis() + (6 * 24 * 60 * 60 * 1000L);

			// Store refresh token if provided (for persistent login / token rotation)
			if (tokenResponse.has(REFRESH_TOKEN_KEY) && !tokenResponse.get(REFRESH_TOKEN_KEY).isJsonNull())
			{
				newRefreshToken = tokenResponse.get(REFRESH_TOKEN_KEY).getAsString();
				refreshToken = newRefreshToken;
			}

			if (tokenResponse.has(JSON_KEY_IS_PREMIUM))
			{
				setPremium(tokenResponse.get(JSON_KEY_IS_PREMIUM).getAsBoolean());
			}
		}

		// Notify outside of lock to avoid potential deadlocks
		if (newRefreshToken != null && onRefreshTokenChanged != null)
		{
			onRefreshTokenChanged.accept(newRefreshToken);
		}
	}

	/**
	 * Synchronous login wrapper for backward compatibility
	 * Note: This should only be called from background threads
	 */
	public AuthResult login(String email, String password)
	{
		try
		{
			return loginAsync(email, password).get();
		}
		catch (Exception e)
		{
			log.error("Login failed: {}", e.getMessage());
			return new AuthResult(false, "Login failed: " + e.getMessage());
		}
	}

	/**
	 * Exchange a refresh token for new access and refresh tokens.
	 * This is used for persistent login without storing passwords.
	 *
	 * @return CompletableFuture with true if refresh succeeded, false otherwise
	 */
	public CompletableFuture<Boolean> refreshAccessTokenAsync()
	{
		CompletableFuture<Boolean> future = new CompletableFuture<>();

		String currentRefreshToken;
		synchronized (authLock)
		{
			currentRefreshToken = refreshToken;
		}

		if (currentRefreshToken == null || currentRefreshToken.isEmpty())
		{
			future.complete(false);
			return future;
		}

		String url = String.format("%s/auth/refresh?device_info=%s", getApiUrl(), DEVICE_INFO);

		JsonObject jsonBody = new JsonObject();
		jsonBody.addProperty(REFRESH_TOKEN_KEY, currentRefreshToken);
		RequestBody body = RequestBody.create(JSON, jsonBody.toString());

		Request request = new Request.Builder()
			.url(url)
			.post(body)
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Refresh token exchange failed: {}", e.getMessage());
				future.complete(false);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						log.debug("Refresh token rejected (status {})", response.code());
						// Only clear the refresh token on 401 (explicitly rejected).
						// Server errors (5xx) are transient — the token may still be valid.
						if (response.code() == 401)
						{
							synchronized (authLock)
							{
								refreshToken = null;
							}
						}
						future.complete(false);
						return;
					}

					okhttp3.ResponseBody responseBody = response.body();
					String jsonData = responseBody != null ? responseBody.string() : "";
					JsonObject tokenResponse = gson.fromJson(jsonData, JsonObject.class);

					processTokenResponse(tokenResponse);

					log.debug("Successfully refreshed access token (premium: {})", isPremium());
					future.complete(true);
				}
				catch (Exception e)
				{
					log.error("Error processing refresh response: {}", e.getMessage());
					future.complete(false);
				}
			}
		});

		return future;
	}

	/**
	 * Set the refresh token directly (used when loading from config).
	 * @param token The refresh token
	 */
	public void setRefreshToken(String token)
	{
		synchronized (authLock)
		{
			this.refreshToken = token;
		}
	}

	/**
	 * Get the current refresh token (for saving to config).
	 * @return The refresh token, or null if not set
	 */
	public String getRefreshToken()
	{
		synchronized (authLock)
		{
			return refreshToken;
		}
	}

	/**
	 * Sign up a new account with email and password (async)
	 * @return CompletableFuture with AuthResult containing success status and message
	 */
	public CompletableFuture<AuthResult> signupAsync(String email, String password)
	{
		CompletableFuture<AuthResult> future = new CompletableFuture<>();

		String apiUrl = getApiUrl();

		if (email == null || email.isEmpty())
		{
			future.complete(new AuthResult(false, "Please enter your email address"));
			return future;
		}

		if (password == null || password.isEmpty())
		{
			future.complete(new AuthResult(false, "Please enter your password"));
			return future;
		}

		if (password.length() < 6)
		{
			future.complete(new AuthResult(false, "Password must be at least 6 characters"));
			return future;
		}

		String url = String.format("%s/auth/signup", apiUrl);

		// Create JSON body with email and password
		JsonObject jsonBody = new JsonObject();
		jsonBody.addProperty("email", email);
		jsonBody.addProperty("password", password);
		RequestBody body = RequestBody.create(JSON, jsonBody.toString());

		Request request = new Request.Builder()
			.url(url)
			.post(body)
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Failed to sign up with API: {}", e.getMessage());
				future.complete(new AuthResult(false, "Connection error: " + e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						if (response.code() == 400)
						{
							future.complete(new AuthResult(false, "Email already registered. Please login instead."));
						}
						else
						{
							future.complete(new AuthResult(false, "Sign up failed (error " + response.code() + ")"));
						}
						return;
					}

					okhttp3.ResponseBody responseBody = response.body();
					String jsonData = responseBody != null ? responseBody.string() : "";
					JsonObject tokenResponse = gson.fromJson(jsonData, JsonObject.class);

					synchronized (authLock)
					{
						jwtToken = tokenResponse.get(ACCESS_TOKEN_KEY).getAsString();
						tokenExpiry = System.currentTimeMillis() + (6 * 24 * 60 * 60 * 1000L);
					}

					log.debug("Successfully signed up and authenticated with API");
					future.complete(new AuthResult(true, "Account created successfully!"));
				}
				catch (Exception e)
				{
					log.error("Error processing signup response: {}", e.getMessage());
					future.complete(new AuthResult(false, "Error processing response"));
				}
			}
		});

		return future;
	}

	/**
	 * Synchronous signup wrapper for backward compatibility
	 * Note: This should only be called from background threads
	 */
	public AuthResult signup(String email, String password)
	{
		try
		{
			return signupAsync(email, password).get();
		}
		catch (Exception e)
		{
			log.error("Signup failed: {}", e.getMessage());
			return new AuthResult(false, "Signup failed: " + e.getMessage());
		}
	}

	/**
	 * Check if currently authenticated
	 */
	public boolean isAuthenticated()
	{
		return isTokenValid();
	}

	/**
	 * Check if the current user has premium status
	 */
	public boolean isPremium()
	{
		synchronized (authLock)
		{
			return isPremium;
		}
	}

	/**
	 * Set the premium status (called when flip-finder response is received)
	 */
	public void setPremium(boolean premium)
	{
		synchronized (authLock)
		{
			this.isPremium = premium;
		}
	}

	/**
	 * Check if the current RSN is blocked (3rd+ account without premium)
	 */
	public boolean isRsnBlocked()
	{
		synchronized (authLock)
		{
			return isRsnBlocked;
		}
	}

	/**
	 * Clear the current authentication tokens (access and refresh)
	 */
	public void clearAuth()
	{
		synchronized (authLock)
		{
			jwtToken = null;
			tokenExpiry = 0;
			refreshToken = null;
			// Default to true during logout to avoid briefly blocking
			// recommendations before the next entitlement check resolves
			isPremium = true;
			isRsnBlocked = false;
		}
	}

	/**
	 * Set callback for when authentication permanently fails.
	 * This is called when both refresh token and password auth fail,
	 * indicating the user needs to re-login manually.
	 */
	public void setOnAuthFailure(Runnable callback)
	{
		this.onAuthFailure = callback;
	}

	/**
	 * Set callback for when the refresh token changes (rotation or new login).
	 * This allows the caller to persist the new token to config immediately,
	 * preventing token loss if the plugin is closed before the panel saves it.
	 */
	public void setOnRefreshTokenChanged(Consumer<String> callback)
	{
		this.onRefreshTokenChanged = callback;
	}

	/**
	 * Notify that authentication has permanently failed
	 */
	private void notifyAuthFailure()
	{
		if (onAuthFailure != null)
		{
			onAuthFailure.run();
		}
	}

	/**
	 * Fetch user entitlements from the API to check premium status and RSN-level access.
	 * Call this when the player logs into the game.
	 *
	 * @param rsn The player's RuneScape Name (optional, for RSN-level entitlement checks)
	 */
	public CompletableFuture<Boolean> fetchEntitlementsAsync(String rsn)
	{
		if (!isTokenValid())
		{
			return CompletableFuture.completedFuture(false);
		}

		String url = String.format("%s/auth/entitlements", getApiUrl());
		if (rsn != null && !rsn.isEmpty())
		{
			url += "?rsn=" + urlEncode(rsn);
		}

		Request request = withAuthHeader(new Request.Builder()
			.url(url))
			.get()
			.build();

		return executeAsync(request, responseBody -> {
			try
			{
				EntitlementsResponse entitlements = EntitlementsResponse.fromJson(gson, responseBody);
				boolean premium = entitlements.isPremium();
				boolean rsnBlocked = entitlements.isRsnBlocked();

				synchronized (authLock)
				{
					isPremium = premium;
					isRsnBlocked = rsnBlocked;
				}

				log.debug("Fetched entitlements - premium: {}, rsnBlocked: {}", premium, rsnBlocked);
				return premium;
			}
			catch (Exception e)
			{
				log.error("Error parsing entitlements response: {}", e.getMessage());
				return false;
			}
		}, error -> log.warn("Failed to fetch entitlements: {}", error), true);
	}

	// ============================================================================
	// Device Authorization Flow (Discord Login for Desktop Plugin)
	// ============================================================================

	/**
	 * Start the device authorization flow for Discord login.
	 * Returns a device code and verification URL that the user should open in their browser.
	 *
	 * @return CompletableFuture with DeviceAuthResponse containing device_code and verification_url
	 */
	public CompletableFuture<DeviceAuthResponse> startDeviceAuthAsync()
	{
		CompletableFuture<DeviceAuthResponse> future = new CompletableFuture<>();

		String apiUrl = getApiUrl();
		String url = String.format("%s/auth/device/start", apiUrl);

		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, ""))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Failed to start device auth: {}", e.getMessage());
				future.complete(null);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						log.error("Device auth start failed: {}", response.code());
						future.complete(null);
						return;
					}

					okhttp3.ResponseBody responseBody = response.body();
					String jsonData = responseBody != null ? responseBody.string() : "";
					JsonObject json = gson.fromJson(jsonData, JsonObject.class);

					DeviceAuthResponse authResponse = new DeviceAuthResponse();
					authResponse.setDeviceCode(json.get("device_code").getAsString());
					authResponse.setUserCode(json.get("user_code").getAsString());
					authResponse.setVerificationUrl(json.get("verification_url").getAsString());
					authResponse.setExpiresIn(json.get("expires_in").getAsInt());
					authResponse.setPollInterval(json.get("poll_interval").getAsInt());

					log.debug("Device auth started, verification URL: {}", authResponse.getVerificationUrl());
					future.complete(authResponse);
				}
				catch (Exception e)
				{
					log.error("Error parsing device auth response: {}", e.getMessage());
					future.complete(null);
				}
			}
		});

		return future;
	}

	/**
	 * Poll the device authorization status to check if the user has completed Discord OAuth.
	 *
	 * @param deviceCode The device code from startDeviceAuthAsync
	 * @return CompletableFuture with DeviceStatusResponse
	 */
	public CompletableFuture<DeviceStatusResponse> pollDeviceStatusAsync(String deviceCode)
	{
		CompletableFuture<DeviceStatusResponse> future = new CompletableFuture<>();

		String apiUrl = getApiUrl();
		String url = String.format("%s/auth/device/status?code=%s", apiUrl, deviceCode);

		Request request = new Request.Builder()
			.url(url)
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Device status poll failed: {}", e.getMessage());
				future.complete(null);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (response.code() == 404)
					{
						// Device code not found - treat as expired
						DeviceStatusResponse statusResponse = new DeviceStatusResponse();
						statusResponse.setStatus("expired");
						future.complete(statusResponse);
						return;
					}

					if (!response.isSuccessful())
					{
						log.debug("Device status poll error: {}", response.code());
						future.complete(null);
						return;
					}

					okhttp3.ResponseBody responseBody = response.body();
					String jsonData = responseBody != null ? responseBody.string() : "";
					JsonObject json = gson.fromJson(jsonData, JsonObject.class);

					DeviceStatusResponse statusResponse = new DeviceStatusResponse();
					statusResponse.setStatus(json.get(JSON_KEY_STATUS).getAsString());

					if ("authorized".equals(statusResponse.getStatus()) && json.has(ACCESS_TOKEN_KEY))
					{
						statusResponse.setAccessToken(json.get(ACCESS_TOKEN_KEY).getAsString());
						statusResponse.setTokenType(json.has("token_type")
							? json.get("token_type").getAsString() : "bearer");
						if (json.has("refresh_token"))
						{
							statusResponse.setRefreshToken(json.get("refresh_token").getAsString());
						}
					}

					future.complete(statusResponse);
				}
				catch (Exception e)
				{
					log.error("Error parsing device status response: {}", e.getMessage());
					future.complete(null);
				}
			}
		});

		return future;
	}

	/**
	 * Set the JWT token directly (used when receiving token from device auth flow)
	 * @param token The JWT access token
	 */
	public void setAuthToken(String token)
	{
		synchronized (authLock)
		{
			this.jwtToken = token;
			// JWT tokens from this API expire in 7 days, but we'll check earlier
			this.tokenExpiry = System.currentTimeMillis() + (6 * 24 * 60 * 60 * 1000L);
		}
		log.debug("Successfully authenticated via Discord");
	}

	/**
	 * Update the user's RuneScape Name on the server (async)
	 */
	public void updateRSN(String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return;
		}

		String apiUrl = getApiUrl();
		String url = String.format("%s/auth/rsn?rsn=%s", apiUrl, urlEncode(rsn));

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.put(RequestBody.create(JSON, ""));

		executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			log.debug("Successfully updated RSN to: {}", rsn);
			return true;
		}).exceptionally(e ->
		{
			log.debug("Failed to update RSN: {}", e.getMessage());
			return false;
		});
	}
}
