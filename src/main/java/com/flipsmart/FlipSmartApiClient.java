package com.flipsmart;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@Singleton
public class FlipSmartApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final String PRODUCTION_API_URL = "https://api.flipsm.art";
	private static final String ACCESS_TOKEN_KEY = "access_token";
	private static final String REFRESH_TOKEN_KEY = "refresh_token";
	private static final String JSON_KEY_ITEM_ID = "item_id";
	private static final String JSON_KEY_IS_PREMIUM = "is_premium";
	private static final String JSON_KEY_RSN_ENTITLEMENT = "rsn_entitlement";
	private static final String JSON_KEY_STATUS = "status";
	private static final String DEVICE_INFO = "RuneLite Plugin";
	private static final String HEADER_AUTHORIZATION = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";
	
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final FlipSmartConfig config;
	
	// Cache to avoid spamming the API
	private final Map<Integer, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();
	private static final long CACHE_DURATION_MS = 180_000; // 3 minute cache
	
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

	@Inject
	public FlipSmartApiClient(FlipSmartConfig config, Gson gson, OkHttpClient okHttpClient)
	{
		this.config = config;
		// Use the injected Gson's builder to create a customized instance
		this.gson = gson.newBuilder().create();
		// Use the injected OkHttpClient directly as required by RuneLite
		this.httpClient = okHttpClient;
	}

	/**
	 * Get the API URL to use. Returns the configured override URL if set,
	 * otherwise returns the production URL.
	 */
	private String getApiUrl()
	{
		String configuredUrl = config.apiUrl();
		if (configuredUrl == null || configuredUrl.isEmpty())
		{
			return PRODUCTION_API_URL;
		}
		return configuredUrl;
	}

	/**
	 * Authentication result with status and message
	 */
	public static class AuthResult
	{
		public final boolean success;
		public final String message;
		
		public AuthResult(boolean success, String message)
		{
			this.success = success;
			this.message = message;
		}
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
	private <T> CompletableFuture<T> executeAsync(Request request, Function<String, T> responseHandler, 
												   Consumer<String> errorHandler, boolean retryOnAuth)
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
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
						log.debug("Request returned error: {}", response.code());
						if (errorHandler != null)
						{
							errorHandler.accept("Error " + response.code());
						}
						future.complete(null);
						return;
					}

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
	private <T> CompletableFuture<T> executeAuthenticatedAsync(Request.Builder requestBuilder,
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
	 * Authenticate with the API and obtain a JWT token.
	 * Tries refresh token first (if available), falls back to email/password.
	 */
	private CompletableFuture<Boolean> authenticateAsync()
	{
		// Try refresh token first if we have one
		String currentRefreshToken;
		synchronized (authLock)
		{
			currentRefreshToken = refreshToken;
		}

		if (currentRefreshToken != null && !currentRefreshToken.isEmpty())
		{
			return refreshAccessTokenAsync()
				.thenCompose(success -> {
					if (success)
					{
						return CompletableFuture.completedFuture(true);
					}
					// Refresh failed, clear it and fall back to password auth
					synchronized (authLock)
					{
						refreshToken = null;
					}
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
			log.info("Successfully authenticated with API (premium: {})", isPremium());
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
		synchronized (authLock)
		{
			jwtToken = tokenResponse.get(ACCESS_TOKEN_KEY).getAsString();
			tokenExpiry = System.currentTimeMillis() + (6 * 24 * 60 * 60 * 1000L);

			// Store refresh token if provided (for persistent login / token rotation)
			if (tokenResponse.has(REFRESH_TOKEN_KEY) && !tokenResponse.get(REFRESH_TOKEN_KEY).isJsonNull())
			{
				refreshToken = tokenResponse.get(REFRESH_TOKEN_KEY).getAsString();
			}

			if (tokenResponse.has(JSON_KEY_IS_PREMIUM))
			{
				setPremium(tokenResponse.get(JSON_KEY_IS_PREMIUM).getAsBoolean());
			}
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
						// Clear invalid refresh token
						synchronized (authLock)
						{
							refreshToken = null;
						}
						future.complete(false);
						return;
					}

					okhttp3.ResponseBody responseBody = response.body();
					String jsonData = responseBody != null ? responseBody.string() : "";
					JsonObject tokenResponse = gson.fromJson(jsonData, JsonObject.class);

					processTokenResponse(tokenResponse);

					log.info("Successfully refreshed access token (premium: {})", isPremium());
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
					
					log.info("Successfully signed up and authenticated with API");
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
			url += "?rsn=" + rsn;
		}

		Request request = withAuthHeader(new Request.Builder()
			.url(url))
			.get()
			.build();

		return executeAsync(request, responseBody -> {
			try
			{
				JsonObject json = gson.fromJson(responseBody, JsonObject.class);

				boolean premium;
				if (json.has(JSON_KEY_IS_PREMIUM) && json.get(JSON_KEY_IS_PREMIUM).isJsonPrimitive())
				{
					premium = json.get(JSON_KEY_IS_PREMIUM).getAsBoolean();
				}
				else
				{
					premium = false;
				}

				boolean rsnBlocked;
				if (json.has(JSON_KEY_RSN_ENTITLEMENT) && !json.get(JSON_KEY_RSN_ENTITLEMENT).isJsonNull())
				{
					JsonObject rsnEntitlement = json.getAsJsonObject(JSON_KEY_RSN_ENTITLEMENT);
					if (rsnEntitlement.has(JSON_KEY_STATUS))
					{
						String status = rsnEntitlement.get(JSON_KEY_STATUS).getAsString();
						rsnBlocked = "blocked".equals(status);
					}
					else
					{
						rsnBlocked = false;
					}
				}
				else
				{
					rsnBlocked = false;
				}

				synchronized (authLock)
				{
					isPremium = premium;
					isRsnBlocked = rsnBlocked;
				}

				log.info("Fetched entitlements - premium: {}, rsnBlocked: {}", premium, rsnBlocked);
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
	 * Response from starting device authorization
	 */
	public static class DeviceAuthResponse
	{
		private String deviceCode;
		private String userCode;
		private String verificationUrl;
		private int expiresIn;
		private int pollInterval;
		
		/** Default constructor required for Gson deserialization */
		public DeviceAuthResponse() { }
		
		public String getDeviceCode() { return deviceCode; }
		public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
		
		public String getUserCode() { return userCode; }
		public void setUserCode(String userCode) { this.userCode = userCode; }
		
		public String getVerificationUrl() { return verificationUrl; }
		public void setVerificationUrl(String verificationUrl) { this.verificationUrl = verificationUrl; }
		
		public int getExpiresIn() { return expiresIn; }
		public void setExpiresIn(int expiresIn) { this.expiresIn = expiresIn; }
		
		public int getPollInterval() { return pollInterval; }
		public void setPollInterval(int pollInterval) { this.pollInterval = pollInterval; }
	}
	
	/**
	 * Response from polling device authorization status
	 */
	public static class DeviceStatusResponse
	{
		private String status;  // pending, authorized, expired
		private String accessToken;
		private String tokenType;
		private String refreshToken;  // For session persistence across client restarts

		/** Default constructor required for Gson deserialization */
		public DeviceStatusResponse() { }

		public String getStatus() { return status; }
		public void setStatus(String status) { this.status = status; }

		public String getAccessToken() { return accessToken; }
		public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

		public String getTokenType() { return tokenType; }
		public void setTokenType(String tokenType) { this.tokenType = tokenType; }

		public String getRefreshToken() { return refreshToken; }
		public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
	}
	
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
					authResponse.deviceCode = json.get("device_code").getAsString();
					authResponse.userCode = json.get("user_code").getAsString();
					authResponse.verificationUrl = json.get("verification_url").getAsString();
					authResponse.expiresIn = json.get("expires_in").getAsInt();
					authResponse.pollInterval = json.get("poll_interval").getAsInt();
					
					log.info("Device auth started, verification URL: {}", authResponse.verificationUrl);
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
						statusResponse.status = "expired";
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
		log.info("Successfully authenticated via Discord");
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
		String url = String.format("%s/auth/rsn?rsn=%s", apiUrl, rsn);
		
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.put(RequestBody.create(JSON, ""));
		
		executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			log.info("Successfully updated RSN to: {}", rsn);
			return true;
		}).exceptionally(e ->
		{
			log.debug("Failed to update RSN: {}", e.getMessage());
			return false;
		});
	}
	
	/**
	 * Check if we have a valid JWT token, and refresh if needed (async)
	 */
	private CompletableFuture<Boolean> ensureAuthenticatedAsync()
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
	 * Fetch item analysis from the API asynchronously
	 */
	public CompletableFuture<FlipAnalysis> getItemAnalysisAsync(int itemId)
	{
		// Check cache first
		CachedAnalysis cached = analysisCache.get(itemId);
		if (cached != null && !cached.isExpired())
		{
			return CompletableFuture.completedFuture(cached.getAnalysis());
		}

		String apiUrl = getApiUrl();
		String url = String.format("%s/analysis/%d?timeframe=1h", apiUrl, itemId);
		
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();
		
		return executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			FlipAnalysis analysis = gson.fromJson(jsonData, FlipAnalysis.class);
			removedExpiredCacheEntries();
			analysisCache.put(itemId, new CachedAnalysis(analysis));
			return analysis;
		});
	}

	/**
	 * Fetch flip recommendations from the API asynchronously.
	 *
	 * @param cashStack Player's available cash in GP (optional)
	 * @param flipStyle Flip style (conservative, balanced, aggressive)
	 * @param limit Number of recommendations to return
	 * @param randomSeed Random seed for variety in suggestions (optional)
	 * @param timeframe Target flip timeframe (30m, 2h, 4h, 12h) or null for Active mode
	 * @param rsn Player's RuneScape Name (optional, for RSN-level access enforcement)
	 * @return CompletableFuture with flip recommendations
	 */
	public CompletableFuture<FlipFinderResponse> getFlipRecommendationsAsync(
		Integer cashStack, String flipStyle, int limit, Integer randomSeed, String timeframe, String rsn)
	{
		String apiUrl = getApiUrl();

		// Build URL with query parameters
		StringBuilder urlBuilder = new StringBuilder();
		urlBuilder.append(String.format("%s/flip-finder?limit=%d&flip_style=%s", apiUrl, limit, flipStyle));

		if (cashStack != null)
		{
			urlBuilder.append(String.format("&cash_stack=%d", cashStack));
		}

		if (randomSeed != null)
		{
			urlBuilder.append(String.format("&random_seed=%d", randomSeed));
		}

		if (timeframe != null)
		{
			urlBuilder.append(String.format("&timeframe=%s", timeframe));
		}

		if (rsn != null && !rsn.isEmpty())
		{
			urlBuilder.append(String.format("&rsn=%s", rsn));
		}

		String url = urlBuilder.toString();
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, FlipFinderResponse.class));
	}

	/**
	 * @deprecated Use {@link #getFlipRecommendationsAsync(Integer, String, int, Integer, String, String)} instead.
	 * This method uses the deprecated /flip-finder/timeframe endpoint.
	 *
	 * Fetch timeframe-based flip recommendations from the API asynchronously.
	 * Uses the /flip-finder/timeframe endpoint with multi-factor scoring.
	 *
	 * @param timeframe The target flip timeframe (30m, 2h, 4h, 12h)
	 * @param cashStack Optional cash stack for budget-aware recommendations
	 * @param limit Number of recommendations to return
	 * @param priceOffset Optional price offset for buy/sell recommendations
	 * @return CompletableFuture with the timeframe-based recommendations
	 * @deprecated Since 1.5.0. Use {@link #getProfileFlipRecommendationsAsync} with the timeframe parameter instead.
	 *             This method will be removed in version 2.0.0.
	 */
	@Deprecated(since = "1.5.0", forRemoval = true)
	public CompletableFuture<TimeframeFlipFinderResponse> getTimeframeFlipRecommendationsAsync(
		String timeframe, Integer cashStack, int limit, Integer priceOffset)
	{
		String apiUrl = getApiUrl();

		// Build URL with query parameters
		StringBuilder urlBuilder = new StringBuilder();
		urlBuilder.append(String.format("%s/flip-finder/timeframe?timeframe=%s&limit=%d", apiUrl, timeframe, limit));

		if (cashStack != null)
		{
			urlBuilder.append(String.format("&cash_stack=%d", cashStack));
		}

		if (priceOffset != null && priceOffset > 0)
		{
			urlBuilder.append(String.format("&price_offset=%d", priceOffset));
		}

		String url = urlBuilder.toString();
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, TimeframeFlipFinderResponse.class));
	}

	/**
	 * Data class for transaction request parameters (use Builder to construct)
	 */
	public static class TransactionRequest
	{
		public final int itemId;
		public final String itemName;
		public final boolean isBuy;
		public final int quantity;
		public final int pricePerItem;
		public final Integer geSlot;
		public final Integer recommendedSellPrice;
		public final String rsn;
		public final Integer totalQuantity;

		private TransactionRequest(Builder builder)
		{
			this.itemId = builder.itemId;
			this.itemName = builder.itemName;
			this.isBuy = builder.isBuy;
			this.quantity = builder.quantity;
			this.pricePerItem = builder.pricePerItem;
			this.geSlot = builder.geSlot;
			this.recommendedSellPrice = builder.recommendedSellPrice;
			this.rsn = builder.rsn;
			this.totalQuantity = builder.totalQuantity;
		}

		public static Builder builder(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem)
		{
			return new Builder(itemId, itemName, isBuy, quantity, pricePerItem);
		}

		public static class Builder
		{
			private final int itemId;
			private final String itemName;
			private final boolean isBuy;
			private final int quantity;
			private final int pricePerItem;
			private Integer geSlot;
			private Integer recommendedSellPrice;
			private String rsn;
			private Integer totalQuantity;

			private Builder(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem)
			{
				this.itemId = itemId;
				this.itemName = itemName;
				this.isBuy = isBuy;
				this.quantity = quantity;
				this.pricePerItem = pricePerItem;
			}

			public Builder geSlot(Integer geSlot) { this.geSlot = geSlot; return this; }
			public Builder recommendedSellPrice(Integer price) { this.recommendedSellPrice = price; return this; }
			public Builder rsn(String rsn) { this.rsn = rsn; return this; }
			public Builder totalQuantity(Integer qty) { this.totalQuantity = qty; return this; }

			public TransactionRequest build() { return new TransactionRequest(this); }
		}
	}

	/**
	 * Record a Grand Exchange transaction asynchronously
	 */
	public CompletableFuture<Void> recordTransactionAsync(TransactionRequest request)
	{
		String apiUrl = getApiUrl();
		String url = String.format("%s/transactions", apiUrl);
		
		// Create JSON body
		JsonObject jsonBody = new JsonObject();
		jsonBody.addProperty(JSON_KEY_ITEM_ID, request.itemId);
		jsonBody.addProperty("item_name", request.itemName);
		jsonBody.addProperty("is_buy", request.isBuy);
		jsonBody.addProperty("quantity", request.quantity);
		jsonBody.addProperty("price_per_item", request.pricePerItem);
		if (request.geSlot != null)
		{
			jsonBody.addProperty("ge_slot", request.geSlot);
		}
		if (request.recommendedSellPrice != null)
		{
			jsonBody.addProperty("recommended_sell_price", request.recommendedSellPrice);
		}
		if (request.rsn != null && !request.rsn.isEmpty())
		{
			jsonBody.addProperty("rsn", request.rsn);
		}
		if (request.totalQuantity != null && request.totalQuantity > 0)
		{
			jsonBody.addProperty("total_quantity", request.totalQuantity);
		}
		
		RequestBody body = RequestBody.create(JSON, jsonBody.toString());
		
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);
		
		return executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
			log.info("Transaction recorded for {}: {}", request.rsn, responseObj.get("message").getAsString());
			return null;
		}).thenApply(v -> null);
	}

	/**
	 * Record a Grand Exchange transaction asynchronously (simplified overload)
	 * Used for recording offline transactions detected on login.
	 * 
	 * @param itemId Item ID
	 * @param itemName Item name
	 * @param transactionType "BUY" or "SELL"
	 * @param quantity Quantity traded
	 * @param pricePerItem Price per item
	 * @param rsn RuneScape Name
	 */
	public CompletableFuture<Void> recordTransactionAsync(int itemId, String itemName, String transactionType,
			int quantity, int pricePerItem, String rsn)
	{
		boolean isBuy = "BUY".equalsIgnoreCase(transactionType);
		TransactionRequest request = TransactionRequest
			.builder(itemId, itemName, isBuy, quantity, pricePerItem)
			.rsn(rsn)
			.build();
		
		return recordTransactionAsync(request);
	}

	/**
	 * Fetch active flips from the API asynchronously
	 * @param rsn Optional RSN to filter by (for multi-account support)
	 */
	public CompletableFuture<ActiveFlipsResponse> getActiveFlipsAsync(String rsn)
	{
		String apiUrl = getApiUrl();
		String url;
		if (rsn != null && !rsn.isEmpty())
		{
			url = String.format("%s/transactions/active-flips?rsn=%s", apiUrl, rsn);
		}
		else
		{
			url = String.format("%s/transactions/active-flips", apiUrl);
		}
		
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();
		
		return executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, ActiveFlipsResponse.class));
	}
	
	/**
	 * Fetch active flips from the API asynchronously (all RSNs)
	 */
	public CompletableFuture<ActiveFlipsResponse> getActiveFlipsAsync()
	{
		return getActiveFlipsAsync(null);
	}

	/**
	 * Dismiss an active flip asynchronously
	 */
	public CompletableFuture<Boolean> dismissActiveFlipAsync(int itemId)
	{
		return dismissActiveFlipAsync(itemId, null);
	}
	
	/**
	 * Dismiss an active flip asynchronously with RSN support
	 * @param itemId The item ID to dismiss
	 * @param rsn Optional RSN to filter by (for multi-account support)
	 */
	public CompletableFuture<Boolean> dismissActiveFlipAsync(int itemId, String rsn)
	{
		String apiUrl = getApiUrl();
		String url;
		if (rsn != null && !rsn.isEmpty())
		{
			url = String.format("%s/transactions/active-flips/%d?rsn=%s", apiUrl, itemId, rsn);
		}
		else
		{
			url = String.format("%s/transactions/active-flips/%d", apiUrl, itemId);
		}
		
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.delete();
		
		return executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			log.info("Successfully dismissed active flip for item {}", itemId);
			return true;
		}).exceptionally(e ->
		{
			log.warn("Failed to dismiss active flip: {}", e.getMessage());
			return false;
		});
	}

	/**
	 * Clean up stale active flips that are no longer being tracked.
	 * Sends the list of item IDs that the plugin considers "truly active"
	 * (items in GE slots or inventory). The API will mark all other active
	 * flips as manually closed.
	 * 
	 * @param activeItemIds Set of item IDs that are truly active
	 * @param rsn Optional RSN to filter by (for multi-account support)
	 * @return CompletableFuture with cleanup result
	 */
	public CompletableFuture<Boolean> cleanupStaleFlipsAsync(java.util.Set<Integer> activeItemIds, String rsn)
	{
		String apiUrl = getApiUrl();
		String url;
		if (rsn != null && !rsn.isEmpty())
		{
			url = String.format("%s/transactions/active-flips/cleanup?rsn=%s", apiUrl, rsn);
		}
		else
		{
			url = String.format("%s/transactions/active-flips/cleanup", apiUrl);
		}
		
		// Build the request body
		JsonObject requestBody = new JsonObject();
		com.google.gson.JsonArray itemIdsArray = new com.google.gson.JsonArray();
		for (Integer itemId : activeItemIds)
		{
			itemIdsArray.add(itemId);
		}
		requestBody.add("active_item_ids", itemIdsArray);
		
		RequestBody body = RequestBody.create(JSON, requestBody.toString());
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);
		
		return executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
			int itemsCleaned = responseObj.has("items_cleaned") ? responseObj.get("items_cleaned").getAsInt() : 0;
			if (itemsCleaned > 0)
			{
				log.info("Cleaned up {} stale active flips", itemsCleaned);
			}
			else
			{
				log.debug("No stale flips to clean up");
			}
			return true;
		}).exceptionally(e ->
		{
			log.warn("Failed to cleanup stale flips: {}", e.getMessage());
			return false;
		});
	}

	/**
	 * Sync the filled quantity for an active flip when the plugin detects a mismatch.
	 * This is used when orders complete while offline and the plugin couldn't track
	 * incremental fills.
	 * 
	 * @param itemId Item ID to sync
	 * @param itemName Item name
	 * @param filledQuantity Actual filled quantity from GE/inventory
	 * @param orderQuantity Total order size
	 * @param pricePerItem Price per item
	 * @param rsn RuneScape Name
	 * @return CompletableFuture with success status
	 */
	public CompletableFuture<Boolean> syncActiveFlipAsync(int itemId, String itemName, int filledQuantity, 
			int orderQuantity, int pricePerItem, String rsn)
	{
		String apiUrl = getApiUrl();
		String url = String.format("%s/transactions/active-flips/sync", apiUrl);
		
		JsonObject requestBody = new JsonObject();
		requestBody.addProperty(JSON_KEY_ITEM_ID, itemId);
		requestBody.addProperty("filled_quantity", filledQuantity);
		requestBody.addProperty("order_quantity", orderQuantity);
		requestBody.addProperty("price_per_item", pricePerItem);
		requestBody.addProperty("rsn", rsn);
		
		RequestBody body = RequestBody.create(JSON, requestBody.toString());
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);
		
		return executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
			int previousQty = responseObj.has("previous_quantity") ? responseObj.get("previous_quantity").getAsInt() : 0;
			int newQty = responseObj.has("new_quantity") ? responseObj.get("new_quantity").getAsInt() : 0;
			if (previousQty != newQty)
			{
				log.info("Synced active flip for {} ({}): {} -> {} items", 
					itemName, itemId, previousQty, newQty);
			}
			return true;
		}).exceptionally(e ->
		{
			log.warn("Failed to sync active flip for {}: {}", itemId, e.getMessage());
			return false;
		});
	}

	/**
	 * Mark an active flip as in the 'sell' phase.
	 * Called when a sell order is placed for an item.
	 *
	 * @param itemId Item ID
	 * @param rsn RuneScape Name
	 * @return CompletableFuture with success status
	 */
	public CompletableFuture<Boolean> markActiveFlipSellingAsync(int itemId, String rsn)
	{
		String apiUrl = getApiUrl();
		String url = String.format("%s/transactions/active-flips/%d/mark-selling?rsn=%s", apiUrl, itemId, rsn);

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, ""));

		return executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			log.info("Marked active flip for item {} as selling", itemId);
			return true;
		}).exceptionally(e ->
		{
			log.debug("Failed to mark active flip as selling: {}", e.getMessage());
			return false;
		});
	}

	/**
	 * Fetch completed flips from the API asynchronously
	 * @param limit Maximum number of flips to return
	 * @param rsn Optional RSN to filter by (for multi-account support)
	 */
	public CompletableFuture<CompletedFlipsResponse> getCompletedFlipsAsync(int limit, String rsn)
	{
		String apiUrl = getApiUrl();
		String url;
		if (rsn != null && !rsn.isEmpty())
		{
			url = String.format("%s/flips/completed?limit=%d&rsn=%s", apiUrl, limit, rsn);
		}
		else
		{
			url = String.format("%s/flips/completed?limit=%d", apiUrl, limit);
		}
		
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();
		
		return executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, CompletedFlipsResponse.class));
	}
	
	/**
	 * Fetch completed flips from the API asynchronously (all RSNs)
	 */
	public CompletableFuture<CompletedFlipsResponse> getCompletedFlipsAsync(int limit)
	{
		return getCompletedFlipsAsync(limit, null);
	}

	/**
	 * Response wrapper for dumps API
	 */
	public static class DumpsResponse
	{
		public DumpEvent[] dumps;
		public int count;
		public String sort_by;
	}

	/**
	 * Fetch market dumps from the API asynchronously
	 *
	 * @param sortBy Sort order: "recency" or "profit"
	 * @param minProfit Minimum profit threshold (0 for all)
	 * @param limit Maximum number of dumps to return
	 * @param onSuccess Callback with dumps array
	 * @param onError Callback for error messages
	 */
	public void getDumpsAsync(String sortBy, int minProfit, int limit,
	                          Consumer<DumpEvent[]> onSuccess,
	                          Consumer<String> onError)
	{
		ensureAuthenticatedAsync().thenAccept(authSuccess ->
		{
			if (!authSuccess)
			{
				if (onError != null)
				{
					onError.accept("Authentication required");
				}
				return;
			}

			// Build query parameters
			HttpUrl.Builder urlBuilder = HttpUrl.parse(getApiUrl() + "/dumps").newBuilder();
			if (sortBy != null && !sortBy.isEmpty())
			{
				urlBuilder.addQueryParameter("sort_by", sortBy);
			}
			if (minProfit > 0)
			{
				urlBuilder.addQueryParameter("min_profit", String.valueOf(minProfit));
			}
			if (limit > 0)
			{
				urlBuilder.addQueryParameter("limit", String.valueOf(limit));
			}

			Request request = withAuthHeader(new Request.Builder()
				.url(urlBuilder.build()))
				.get()
				.build();

			executeAsync(request,
				body ->
				{
					DumpsResponse response = gson.fromJson(body, DumpsResponse.class);
					if (onSuccess != null)
					{
						onSuccess.accept(response != null ? response.dumps : new DumpEvent[0]);
					}
					return null;
				},
				onError,
				true // Retry on 401
			);
		});
	}

	/**
	 * Fetch market dumps with default parameters (recency sort, no min profit, limit 50)
	 */
	public void getDumpsAsync(Consumer<DumpEvent[]> onSuccess, Consumer<String> onError)
	{
		getDumpsAsync("recency", 0, 50, onSuccess, onError);
	}

	/**
	 * Clear the analysis cache
	 */
	public void clearCache()
	{
		analysisCache.clear();
	}

	/**
	 * Remove a specific item from the cache
	 */
	public void invalidateCache(int itemId)
	{
		analysisCache.remove(itemId);
	}

	/**
	 * Removes expired entries from the cache
	 */
	private void removedExpiredCacheEntries()
	{
		analysisCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
	}

	/**
	 * Inner class to store cached analysis with timestamp
	 */
	private static class CachedAnalysis
	{
		private final FlipAnalysis analysis;
		private final long timestamp;

		public CachedAnalysis(FlipAnalysis analysis)
		{
			this.analysis = analysis;
			this.timestamp = System.currentTimeMillis();
		}

		public FlipAnalysis getAnalysis()
		{
			return analysis;
		}

		public boolean isExpired()
		{
			return System.currentTimeMillis() - timestamp > CACHE_DURATION_MS;
		}
	}

	// ============================================================================
	// Bank Snapshot API Methods
	// ============================================================================

	/**
	 * Data class for bank snapshot item
	 */
	public static class BankItem
	{
		public final int itemId;
		public final int quantity;
		public final int valuePerItem;

		public BankItem(int itemId, int quantity, int valuePerItem)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.valuePerItem = valuePerItem;
		}
	}

	/**
	 * Check if a bank snapshot can be taken (rate limit check)
	 *
	 * @param rsn RuneScape Name to check
	 * @return CompletableFuture with BankSnapshotStatusResponse
	 */
	public CompletableFuture<BankSnapshotStatusResponse> checkBankSnapshotStatusAsync(String rsn)
	{
		String apiUrl = getApiUrl();
		String url = String.format("%s/bank/snapshot/status?rsn=%s", apiUrl, rsn);

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, BankSnapshotStatusResponse.class));
	}

	/**
	 * Create a bank snapshot with all items and total wealth components
	 *
	 * @param rsn RuneScape Name
	 * @param items List of bank items with quantities and values
	 * @param inventoryValue Total value of tradeable inventory items (excluding coins)
	 * @param geOffersValue Total value locked in GE offers
	 * @return CompletableFuture with BankSnapshotResponse
	 */
	public CompletableFuture<BankSnapshotResponse> createBankSnapshotAsync(
		String rsn,
		java.util.List<BankItem> items,
		long inventoryValue,
		long geOffersValue)
	{
		String apiUrl = getApiUrl();
		String url = String.format("%s/bank/snapshot", apiUrl);

		// Build the request body
		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("rsn", rsn);
		requestBody.addProperty("inventory_value", inventoryValue);
		requestBody.addProperty("ge_offers_value", geOffersValue);

		com.google.gson.JsonArray itemsArray = new com.google.gson.JsonArray();
		for (BankItem item : items)
		{
			JsonObject itemObj = new JsonObject();
			itemObj.addProperty(JSON_KEY_ITEM_ID, item.itemId);
			itemObj.addProperty("quantity", item.quantity);
			itemObj.addProperty("value_per_item", item.valuePerItem);
			itemsArray.add(itemObj);
		}
		requestBody.add("items", itemsArray);

		RequestBody body = RequestBody.create(JSON, requestBody.toString());
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);

		return executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, BankSnapshotResponse.class));
	}

	// ============================================================================
	// Wiki Real-Time Price API Methods
	// ============================================================================

	private static final String WIKI_PRICES_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";
	private static final long WIKI_PRICE_CACHE_DURATION_MS = 60_000; // 1 minute cache

	// Cache for wiki prices: itemId -> WikiPrice
	private final Map<Integer, WikiPrice> wikiPriceCache = new ConcurrentHashMap<>();
	private final AtomicLong lastWikiPriceFetch = new AtomicLong(0);
	private final AtomicBoolean wikiPriceFetchInProgress = new AtomicBoolean(false);

	/**
	 * Real-time price data from the wiki API
	 */
	public static class WikiPrice
	{
		public final int instaBuy;   // High price - what buyers pay to instant-buy
		public final int instaSell;  // Low price - what sellers receive when instant-selling
		public final long fetchedAt;

		public WikiPrice(int instaBuy, int instaSell)
		{
			this.instaBuy = instaBuy;
			this.instaSell = instaSell;
			this.fetchedAt = System.currentTimeMillis();
		}

		public boolean isExpired()
		{
			return System.currentTimeMillis() - fetchedAt > WIKI_PRICE_CACHE_DURATION_MS;
		}
	}

	/**
	 * Get cached wiki price for an item. Returns null if not cached or expired.
	 * Call fetchWikiPrices() to populate the cache.
	 */
	public WikiPrice getWikiPrice(int itemId)
	{
		WikiPrice price = wikiPriceCache.get(itemId);
		if (price != null && !price.isExpired())
		{
			return price;
		}
		return null;
	}

	/**
	 * Fetch all wiki prices from the API and update the cache.
	 * This is rate-limited to once per minute.
	 */
	public void fetchWikiPrices()
	{
		long now = System.currentTimeMillis();
		if (now - lastWikiPriceFetch.get() < WIKI_PRICE_CACHE_DURATION_MS)
		{
			return;
		}

		if (!wikiPriceFetchInProgress.compareAndSet(false, true))
		{
			return;
		}

		Request request = new Request.Builder()
			.url(WIKI_PRICES_URL)
			.header("User-Agent", "FlipSmart RuneLite Plugin - github.com/flipsmart")
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to fetch wiki prices: {}", e.getMessage());
				wikiPriceFetchInProgress.set(false);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (ResponseBody responseBody = response.body())
				{
					if (!response.isSuccessful() || responseBody == null)
					{
						log.warn("Wiki price API returned error: {}", response.code());
						return;
					}

					String json = responseBody.string();
					parseWikiPriceResponse(json);
					lastWikiPriceFetch.set(System.currentTimeMillis());
				}
				finally
				{
					wikiPriceFetchInProgress.set(false);
				}
			}
		});
	}

	/**
	 * Parse wiki price API response and update cache
	 */
	private void parseWikiPriceResponse(String json)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		JsonObject data = root.getAsJsonObject("data");

		if (data == null)
		{
			return;
		}

		// Clear expired entries before adding new ones to prevent unbounded growth
		removeExpiredWikiPriceEntries();

		for (String key : data.keySet())
		{
			parseAndCacheItemPrice(key, data.getAsJsonObject(key));
		}
		log.debug("Updated wiki price cache with {} items", wikiPriceCache.size());
	}

	/**
	 * Removes expired entries from the wiki price cache to prevent memory leaks
	 */
	private void removeExpiredWikiPriceEntries()
	{
		wikiPriceCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
	}

	/**
	 * Parse and cache a single item's price data
	 */
	private void parseAndCacheItemPrice(String itemKey, JsonObject priceData)
	{
		try
		{
			int itemId = Integer.parseInt(itemKey);
			int high = getJsonIntOrZero(priceData, "high");
			int low = getJsonIntOrZero(priceData, "low");

			if (high > 0 || low > 0)
			{
				wikiPriceCache.put(itemId, new WikiPrice(high, low));
			}
		}
		catch (NumberFormatException ignored)
		{
			// Skip non-numeric keys
		}
	}

	/**
	 * Safely get an int value from JSON, returning 0 if null or missing
	 */
	private int getJsonIntOrZero(JsonObject obj, String key)
	{
		if (obj.has(key) && !obj.get(key).isJsonNull())
		{
			return obj.get(key).getAsInt();
		}
		return 0;
	}

	/**
	 * Check if wiki prices need to be refreshed
	 */
	public boolean needsWikiPriceRefresh()
	{
		return System.currentTimeMillis() - lastWikiPriceFetch.get() > WIKI_PRICE_CACHE_DURATION_MS;
	}

	// =========================================================================
	// Blocklist API Methods
	// =========================================================================

	/**
	 * Fetch user's blocklists from the API asynchronously.
	 * Blocklists are used to hide specific items from flip recommendations.
	 *
	 * @return CompletableFuture with the user's blocklists
	 */
	public CompletableFuture<BlocklistsResponse> getBlocklistsAsync()
	{
		String apiUrl = getApiUrl();
		String url = String.format("%s/blocklists", apiUrl);

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();

		return executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, BlocklistsResponse.class));
	}

	/**
	 * Add an item to a blocklist asynchronously.
	 * Blocked items will be excluded from flip recommendations.
	 *
	 * @param blocklistId The ID of the blocklist to add the item to
	 * @param itemId The OSRS item ID to block
	 * @param reason Optional reason for blocking (can be null)
	 * @return CompletableFuture that completes with true on success, false on failure
	 */
	public CompletableFuture<Boolean> addItemToBlocklistAsync(int blocklistId, int itemId, String reason)
	{
		String apiUrl = getApiUrl();
		String url = String.format("%s/blocklists/%d/items", apiUrl, blocklistId);

		JsonObject jsonBody = new JsonObject();
		jsonBody.addProperty(JSON_KEY_ITEM_ID, itemId);
		if (reason != null && !reason.isEmpty())
		{
			jsonBody.addProperty("reason", reason);
		}

		RequestBody body = RequestBody.create(JSON, jsonBody.toString());

		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);

		return executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			// If we got here, the request succeeded
			log.info("Added item {} to blocklist {}", itemId, blocklistId);
			return true;
		}).exceptionally(e ->
		{
			log.debug("Failed to add item to blocklist: {}", e.getMessage());
			return false;
		});
	}

	/**
	 * Add an item to a blocklist asynchronously (without reason).
	 *
	 * @param blocklistId The ID of the blocklist to add the item to
	 * @param itemId The OSRS item ID to block
	 * @return CompletableFuture that completes with true on success, false on failure
	 */
	public CompletableFuture<Boolean> addItemToBlocklistAsync(int blocklistId, int itemId)
	{
		return addItemToBlocklistAsync(blocklistId, itemId, null);
	}
}
