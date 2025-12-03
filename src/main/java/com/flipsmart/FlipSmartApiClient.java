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
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class FlipSmartApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final String PRODUCTION_API_URL = "https://flipsm.art";
	
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final FlipSmartConfig config;
	
	// Cache to avoid spamming the API
	private final Map<Integer, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();
	private static final long CACHE_DURATION_MS = 60_000; // 1 minute cache
	
	// JWT token management
	private String jwtToken = null;
	private long tokenExpiry = 0;

	@Inject
	public FlipSmartApiClient(FlipSmartConfig config, Gson gson, OkHttpClient okHttpClient)
	{
		this.config = config;
		// Use the injected Gson's builder to create a customized instance
		// This ensures we follow RuneLite's requirements while maintaining compatibility
		this.gson = gson.newBuilder().create();
		// Customize the injected OkHttpClient with our timeout requirements
		this.httpClient = okHttpClient.newBuilder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.build();
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
	 * Authenticate with the API and obtain a JWT token via login
	 */
	private boolean authenticate()
	{
		AuthResult result = login(config.email(), config.password());
		return result.success;
	}
	
	/**
	 * Login with email and password
	 * @return AuthResult with success status and message
	 */
	public AuthResult login(String email, String password)
	{
		String apiUrl = getApiUrl();
		
		if (email == null || email.isEmpty())
		{
			return new AuthResult(false, "Please enter your email address");
		}
		
		if (password == null || password.isEmpty())
		{
			return new AuthResult(false, "Please enter your password");
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
		
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				if (response.code() == 401)
				{
					return new AuthResult(false, "Incorrect email or password");
				}
				else if (response.code() == 404)
				{
					return new AuthResult(false, "Account not found. Please sign up first.");
				}
				return new AuthResult(false, "Login failed (error " + response.code() + ")");
			}
			
			String jsonData = response.body().string();
			JsonObject tokenResponse = gson.fromJson(jsonData, JsonObject.class);
			jwtToken = tokenResponse.get("access_token").getAsString();
			
			// JWT tokens from this API expire in 7 days, but we'll check earlier
			// Set expiry to 6 days to refresh before actual expiry
			tokenExpiry = System.currentTimeMillis() + (6 * 24 * 60 * 60 * 1000L);
			
			log.info("Successfully authenticated with API");
			return new AuthResult(true, "Login successful!");
		}
		catch (IOException e)
		{
			log.error("Failed to authenticate with API: {}", e.getMessage());
			return new AuthResult(false, "Connection error: " + e.getMessage());
		}
	}
	
	/**
	 * Sign up a new account with email and password
	 * @return AuthResult with success status and message
	 */
	public AuthResult signup(String email, String password)
	{
		String apiUrl = getApiUrl();
		
		if (email == null || email.isEmpty())
		{
			return new AuthResult(false, "Please enter your email address");
		}
		
		if (password == null || password.isEmpty())
		{
			return new AuthResult(false, "Please enter your password");
		}
		
		if (password.length() < 6)
		{
			return new AuthResult(false, "Password must be at least 6 characters");
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
		
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				if (response.code() == 400)
				{
					return new AuthResult(false, "Email already registered. Please login instead.");
				}
				return new AuthResult(false, "Sign up failed (error " + response.code() + ")");
			}
			
			String jsonData = response.body().string();
			JsonObject tokenResponse = gson.fromJson(jsonData, JsonObject.class);
			jwtToken = tokenResponse.get("access_token").getAsString();
			
			// JWT tokens from this API expire in 7 days, but we'll check earlier
			// Set expiry to 6 days to refresh before actual expiry
			tokenExpiry = System.currentTimeMillis() + (6 * 24 * 60 * 60 * 1000L);
			
			log.info("Successfully signed up and authenticated with API");
			return new AuthResult(true, "Account created successfully!");
		}
		catch (IOException e)
		{
			log.error("Failed to sign up with API: {}", e.getMessage());
			return new AuthResult(false, "Connection error: " + e.getMessage());
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
		jwtToken = null;
		tokenExpiry = 0;
	}
	
	/**
	 * Update the user's RuneScape Name on the server
	 */
	public void updateRSN(String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return;
		}
		
		String apiUrl = getApiUrl();
		
		// Ensure we have a valid token
		if (!ensureAuthenticated())
		{
			log.error("Cannot update RSN - authentication failed");
			return;
		}
		
		String url = String.format("%s/auth/rsn?rsn=%s", apiUrl, rsn);
		Request request = new Request.Builder()
			.url(url)
			.put(RequestBody.create(JSON, ""))
			.header("Authorization", "Bearer " + jwtToken)
			.build();
		
		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.isSuccessful())
			{
				log.info("Successfully updated RSN to: {}", rsn);
			}
			else
			{
				log.debug("Failed to update RSN: {}", response.code());
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to update RSN: {}", e.getMessage());
		}
	}
	
	/**
	 * Check if we have a valid JWT token, and refresh if needed
	 */
	private boolean ensureAuthenticated()
	{
		// Check if we have a token and it's not expired
		if (jwtToken != null && System.currentTimeMillis() < tokenExpiry)
		{
			return true;
		}
		
		// Token is missing or expired, authenticate
		return authenticate();
	}

	/**
	 * Fetch item analysis from the API asynchronously
	 */
	public CompletableFuture<FlipAnalysis> getItemAnalysisAsync(int itemId)
	{
		return CompletableFuture.supplyAsync(() -> getItemAnalysis(itemId));
	}

	/**
	 * Fetch item analysis from the API (synchronous)
	 */
	public FlipAnalysis getItemAnalysis(int itemId)
	{
		// Check cache first
		CachedAnalysis cached = analysisCache.get(itemId);
		if (cached != null && !cached.isExpired())
		{
			return cached.getAnalysis();
		}

		String apiUrl = getApiUrl();
		
		// Ensure we have a valid token
		if (!ensureAuthenticated())
		{
			log.error("Failed to authenticate with API");
			return null;
		}

		String url = String.format("%s/analysis/%d?timeframe=1h", apiUrl, itemId);
		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + jwtToken)
			.get()
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.code() == 401)
			{
				// Token might have expired, try to re-authenticate once
				log.debug("Received 401, attempting to re-authenticate");
				jwtToken = null; // Clear the token
				if (ensureAuthenticated())
				{
					// Retry the request with new token
					Request retryRequest = new Request.Builder()
						.url(url)
						.header("Authorization", "Bearer " + jwtToken)
						.get()
						.build();
					try (Response retryResponse = httpClient.newCall(retryRequest).execute())
					{
						if (!retryResponse.isSuccessful())
						{
							log.debug("API returned error for item {} after re-auth: {}", itemId, retryResponse.code());
							return null;
						}
						String jsonData = retryResponse.body().string();
						FlipAnalysis analysis = gson.fromJson(jsonData, FlipAnalysis.class);
						analysisCache.put(itemId, new CachedAnalysis(analysis));
						return analysis;
					}
				}
				return null;
			}
			
			if (!response.isSuccessful())
			{
				log.debug("API returned error for item {}: {}", itemId, response.code());
				return null;
			}

			String jsonData = response.body().string();
			FlipAnalysis analysis = gson.fromJson(jsonData, FlipAnalysis.class);

			// Cache the result
			analysisCache.put(itemId, new CachedAnalysis(analysis));

			return analysis;
		}
		catch (IOException e)
		{
			log.debug("Failed to fetch analysis for item {}: {}", itemId, e.getMessage());
			return null;
		}
	}

	/**
	 * Fetch flip recommendations from the API
	 */
	public FlipFinderResponse getFlipRecommendations(Integer cashStack, String flipStyle, int limit)
	{
		String apiUrl = getApiUrl();
		
		// Ensure we have a valid token
		if (!ensureAuthenticated())
		{
			log.error("Failed to authenticate with API");
			return null;
		}

		// Build URL with query parameters
		StringBuilder urlBuilder = new StringBuilder();
		urlBuilder.append(String.format("%s/flip-finder?limit=%d&flip_style=%s", apiUrl, limit, flipStyle));
		
		if (cashStack != null)
		{
			urlBuilder.append(String.format("&cash_stack=%d", cashStack));
		}
		
		String url = urlBuilder.toString();
		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + jwtToken)
			.get()
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.code() == 401)
			{
				// Token might have expired, try to re-authenticate once
				log.debug("Received 401, attempting to re-authenticate");
				jwtToken = null; // Clear the token
				if (ensureAuthenticated())
				{
					// Retry the request with new token
					Request retryRequest = new Request.Builder()
						.url(url)
						.header("Authorization", "Bearer " + jwtToken)
						.get()
						.build();
					try (Response retryResponse = httpClient.newCall(retryRequest).execute())
					{
						if (!retryResponse.isSuccessful())
						{
							log.warn("API returned error for flip finder after re-auth: {}", retryResponse.code());
							return null;
						}
						String jsonData = retryResponse.body().string();
						return gson.fromJson(jsonData, FlipFinderResponse.class);
					}
				}
				return null;
			}
			
			if (!response.isSuccessful())
			{
				log.warn("API returned error for flip finder: {}", response.code());
				return null;
			}

			String jsonData = response.body().string();
			return gson.fromJson(jsonData, FlipFinderResponse.class);
		}
		catch (IOException e)
		{
			log.warn("Failed to fetch flip recommendations: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Fetch flip recommendations asynchronously
	 */
	public CompletableFuture<FlipFinderResponse> getFlipRecommendationsAsync(Integer cashStack, String flipStyle, int limit)
	{
		return CompletableFuture.supplyAsync(() -> getFlipRecommendations(cashStack, flipStyle, limit));
	}

	/**
	 * Record a Grand Exchange transaction
	 */
	public void recordTransaction(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem, Integer geSlot, Integer recommendedSellPrice)
	{
		String apiUrl = getApiUrl();
		
		// Ensure we have a valid token
		if (!ensureAuthenticated())
		{
			log.error("Failed to authenticate with API");
			return;
		}

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
		
		RequestBody body = RequestBody.create(JSON, jsonBody.toString());
		
		Request request = new Request.Builder()
			.url(url)
			.post(body)
			.header("Authorization", "Bearer " + jwtToken)
			.build();
		
		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.code() == 401)
			{
				// Token might have expired, try to re-authenticate once
				jwtToken = null;
				if (ensureAuthenticated())
				{
					// Retry the request with new token
					Request retryRequest = new Request.Builder()
						.url(url)
						.post(body)
						.header("Authorization", "Bearer " + jwtToken)
						.build();
					try (Response retryResponse = httpClient.newCall(retryRequest).execute())
					{
						if (retryResponse.isSuccessful())
						{
							String jsonData = retryResponse.body().string();
							JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
							log.info("Transaction recorded: {}", responseObj.get("message").getAsString());
						}
						else
						{
							log.warn("Failed to record transaction after re-auth: {}", retryResponse.code());
						}
					}
				}
				return;
			}
			
			if (response.isSuccessful())
			{
				String jsonData = response.body().string();
				JsonObject responseObj = gson.fromJson(jsonData, JsonObject.class);
				log.info("Transaction recorded: {}", responseObj.get("message").getAsString());
			}
			else
			{
				log.warn("Failed to record transaction: {}", response.code());
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to record transaction: {}", e.getMessage());
		}
	}

	/**
	 * Record a transaction asynchronously
	 */
	public CompletableFuture<Void> recordTransactionAsync(int itemId, String itemName, boolean isBuy, int quantity, int pricePerItem, Integer geSlot, Integer recommendedSellPrice)
	{
		return CompletableFuture.runAsync(() -> recordTransaction(itemId, itemName, isBuy, quantity, pricePerItem, geSlot, recommendedSellPrice));
	}

	/**
	 * Fetch active flips from the API
	 */
	public ActiveFlipsResponse getActiveFlips()
	{
		String apiUrl = getApiUrl();
		
		// Ensure we have a valid token
		if (!ensureAuthenticated())
		{
			log.error("Failed to authenticate with API");
			return null;
		}

		String url = String.format("%s/transactions/active-flips", apiUrl);
		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + jwtToken)
			.get()
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.code() == 401)
			{
				// Token might have expired, try to re-authenticate once
				jwtToken = null;
				if (ensureAuthenticated())
				{
					// Retry the request with new token
					Request retryRequest = new Request.Builder()
						.url(url)
						.header("Authorization", "Bearer " + jwtToken)
						.get()
						.build();
					try (Response retryResponse = httpClient.newCall(retryRequest).execute())
					{
						if (!retryResponse.isSuccessful())
						{
							log.warn("API returned error for active flips after re-auth: {}", retryResponse.code());
							return null;
						}
						String jsonData = retryResponse.body().string();
						return gson.fromJson(jsonData, ActiveFlipsResponse.class);
					}
				}
				return null;
			}
			
			if (!response.isSuccessful())
			{
				log.warn("API returned error for active flips: {}", response.code());
				return null;
			}

			String jsonData = response.body().string();
			return gson.fromJson(jsonData, ActiveFlipsResponse.class);
		}
		catch (IOException e)
		{
			log.warn("Failed to fetch active flips: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Fetch active flips asynchronously
	 */
	public CompletableFuture<ActiveFlipsResponse> getActiveFlipsAsync()
	{
		return CompletableFuture.supplyAsync(() -> getActiveFlips());
	}

	/**
	 * Dismiss an active flip (remove from tracking)
	 */
	public boolean dismissActiveFlip(int itemId)
	{
		String apiUrl = getApiUrl();
		
		// Ensure we have a valid token
		if (!ensureAuthenticated())
		{
			log.error("Failed to authenticate with API");
			return false;
		}

		String url = String.format("%s/transactions/active-flips/%d", apiUrl, itemId);
		Request request = new Request.Builder()
			.url(url)
			.delete()
			.header("Authorization", "Bearer " + jwtToken)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.code() == 401)
			{
				// Token might have expired, try to re-authenticate once
				jwtToken = null;
				if (ensureAuthenticated())
				{
					// Retry the request with new token
					Request retryRequest = new Request.Builder()
						.url(url)
						.delete()
						.header("Authorization", "Bearer " + jwtToken)
						.build();
					try (Response retryResponse = httpClient.newCall(retryRequest).execute())
					{
						if (retryResponse.isSuccessful())
						{
							log.info("Successfully dismissed active flip for item {}", itemId);
							return true;
						}
						else
						{
							log.warn("Failed to dismiss active flip after re-auth: {}", retryResponse.code());
							return false;
						}
					}
				}
				return false;
			}
			
			if (response.isSuccessful())
			{
				log.info("Successfully dismissed active flip for item {}", itemId);
				return true;
			}
			else
			{
				log.warn("Failed to dismiss active flip: {}", response.code());
				return false;
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to dismiss active flip: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Dismiss an active flip asynchronously
	 */
	public CompletableFuture<Boolean> dismissActiveFlipAsync(int itemId)
	{
		return CompletableFuture.supplyAsync(() -> dismissActiveFlip(itemId));
	}

	/**
	 * Fetch completed flips from the API
	 */
	public CompletedFlipsResponse getCompletedFlips(int limit)
	{
		String apiUrl = getApiUrl();
		
		// Ensure we have a valid token
		if (!ensureAuthenticated())
		{
			log.error("Failed to authenticate with API");
			return null;
		}

		String url = String.format("%s/flips/completed?limit=%d", apiUrl, limit);
		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + jwtToken)
			.get()
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.code() == 401)
			{
				// Token might have expired, try to re-authenticate once
				jwtToken = null;
				if (ensureAuthenticated())
				{
					// Retry the request with new token
					Request retryRequest = new Request.Builder()
						.url(url)
						.header("Authorization", "Bearer " + jwtToken)
						.get()
						.build();
					try (Response retryResponse = httpClient.newCall(retryRequest).execute())
					{
						if (!retryResponse.isSuccessful())
						{
							log.warn("API returned error for completed flips after re-auth: {}", retryResponse.code());
							return null;
						}
						String jsonData = retryResponse.body().string();
						return gson.fromJson(jsonData, CompletedFlipsResponse.class);
					}
				}
				return null;
			}
			
			if (!response.isSuccessful())
			{
				log.warn("API returned error for completed flips: {}", response.code());
				return null;
			}

			String jsonData = response.body().string();
			return gson.fromJson(jsonData, CompletedFlipsResponse.class);
		}
		catch (IOException e)
		{
			log.warn("Failed to fetch completed flips: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Fetch completed flips asynchronously
	 */
	public CompletableFuture<CompletedFlipsResponse> getCompletedFlipsAsync(int limit)
	{
		return CompletableFuture.supplyAsync(() -> getCompletedFlips(limit));
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

