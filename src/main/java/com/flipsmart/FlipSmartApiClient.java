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
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@Singleton
public class FlipSmartApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final String PRODUCTION_API_URL = "https://api.flipsm.art";
	
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final FlipSmartConfig config;
	
	// Cache to avoid spamming the API
	private final Map<Integer, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();
	private static final long CACHE_DURATION_MS = 180_000; // 3 minute cache
	
	// JWT token management
	private volatile String jwtToken = null;
	private volatile long tokenExpiry = 0;
	
	// Lock for authentication to prevent concurrent auth attempts
	private final Object authLock = new Object();

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
						jwtToken = null;
						
						authenticateAsync().thenAccept(authSuccess ->
						{
							if (authSuccess)
							{
								// Rebuild request with new token
								Request retryRequest = request.newBuilder()
									.header("Authorization", "Bearer " + jwtToken)
									.build();
								
								// Retry without auth retry to prevent infinite loop
								executeAsync(retryRequest, responseHandler, errorHandler, false)
									.thenAccept(future::complete);
							}
							else
							{
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
			
			Request request = requestBuilder
				.header("Authorization", "Bearer " + jwtToken)
				.build();
			
			return executeAsync(request, responseHandler, null, true);
		});
	}
	
	/**
	 * Authenticate with the API and obtain a JWT token via login (async)
	 */
	private CompletableFuture<Boolean> authenticateAsync()
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
		
		String url = String.format("%s/auth/login", apiUrl);
		
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
				log.error("Failed to authenticate with API: {}", e.getMessage());
				future.complete(new AuthResult(false, "Connection error: " + e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						if (response.code() == 401)
						{
							future.complete(new AuthResult(false, "Incorrect email or password"));
						}
						else if (response.code() == 404)
						{
							future.complete(new AuthResult(false, "Account not found. Please sign up first."));
						}
						else
						{
							future.complete(new AuthResult(false, "Login failed (error " + response.code() + ")"));
						}
						return;
					}
					
					okhttp3.ResponseBody responseBody = response.body();
					String jsonData = responseBody != null ? responseBody.string() : "";
					JsonObject tokenResponse = gson.fromJson(jsonData, JsonObject.class);
					
					synchronized (authLock)
					{
						jwtToken = tokenResponse.get("access_token").getAsString();
						// JWT tokens from this API expire in 7 days, but we'll check earlier
						// Set expiry to 6 days to refresh before actual expiry
						tokenExpiry = System.currentTimeMillis() + (6 * 24 * 60 * 60 * 1000L);
					}
					
					log.info("Successfully authenticated with API");
					future.complete(new AuthResult(true, "Login successful!"));
				}
				catch (Exception e)
				{
					log.error("Error processing login response: {}", e.getMessage());
					future.complete(new AuthResult(false, "Error processing response"));
				}
			}
		});
		
		return future;
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
						jwtToken = tokenResponse.get("access_token").getAsString();
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
		return jwtToken != null && System.currentTimeMillis() < tokenExpiry;
	}
	
	/**
	 * Clear the current authentication token
	 */
	public void clearAuth()
	{
		synchronized (authLock)
		{
			jwtToken = null;
			tokenExpiry = 0;
		}
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
		if (jwtToken != null && System.currentTimeMillis() < tokenExpiry)
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
	 * Fetch flip recommendations from the API asynchronously
	 */
	public CompletableFuture<FlipFinderResponse> getFlipRecommendationsAsync(Integer cashStack, String flipStyle, int limit, Integer randomSeed)
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
		
		String url = urlBuilder.toString();
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.get();
		
		return executeAuthenticatedAsync(requestBuilder, jsonData ->
			gson.fromJson(jsonData, FlipFinderResponse.class));
	}

	/**
	 * Record a Grand Exchange transaction asynchronously
	 */
	public CompletableFuture<Void> recordTransactionAsync(int itemId, String itemName, boolean isBuy, 
														  int quantity, int pricePerItem, Integer geSlot, 
														  Integer recommendedSellPrice, String rsn)
	{
		String apiUrl = getApiUrl();
		String url = String.format("%s/transactions", apiUrl);
		
		// Create JSON body
		JsonObject jsonBody = new JsonObject();
		jsonBody.addProperty("item_id", itemId);
		jsonBody.addProperty("item_name", itemName);
		jsonBody.addProperty("is_buy", isBuy);
		jsonBody.addProperty("quantity", quantity);
		jsonBody.addProperty("price_per_item", pricePerItem);
		if (geSlot != null)
		{
			jsonBody.addProperty("ge_slot", geSlot);
		}
		if (recommendedSellPrice != null)
		{
			jsonBody.addProperty("recommended_sell_price", recommendedSellPrice);
		}
		if (rsn != null && !rsn.isEmpty())
		{
			jsonBody.addProperty("rsn", rsn);
		}
		
		RequestBody body = RequestBody.create(JSON, jsonBody.toString());
		
		Request.Builder requestBuilder = new Request.Builder()
			.url(url)
			.post(body);
		
		return executeAuthenticatedAsync(requestBuilder, jsonData ->
		{
			JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
			log.info("Transaction recorded for {}: {}", rsn, responseObj.get("message").getAsString());
			return null;
		}).thenApply(v -> null);
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
}
