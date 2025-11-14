package com.flippingtool;

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
public class FlippingApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final FlippingToolConfig config;
	
	// Cache to avoid spamming the API
	private final Map<Integer, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();
	private static final long CACHE_DURATION_MS = 60_000; // 1 minute cache
	
	// JWT token management
	private String jwtToken = null;
	private long tokenExpiry = 0;

	@Inject
	public FlippingApiClient(FlippingToolConfig config)
	{
		this.config = config;
		this.gson = new Gson();
		this.httpClient = new OkHttpClient.Builder()
			.connectTimeout(5, TimeUnit.SECONDS)
			.readTimeout(10, TimeUnit.SECONDS)
			.build();
	}

	/**
	 * Authenticate with the API and obtain a JWT token via login
	 */
	private boolean authenticate()
	{
		String apiUrl = config.apiUrl();
		String email = config.email();
		String password = config.password();
		
		if (apiUrl == null || apiUrl.isEmpty())
		{
			log.warn("API URL not configured");
			return false;
		}
		
		if (email == null || email.isEmpty() || password == null || password.isEmpty())
		{
			log.warn("Email or password not configured. Please set your credentials in the plugin settings.");
			return false;
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
				log.error("Authentication failed with status code: {}", response.code());
				if (response.code() == 401)
				{
					log.error("Incorrect email or password. Please check your credentials in the plugin settings.");
				}
				return false;
			}
			
			String jsonData = response.body().string();
			JsonObject tokenResponse = gson.fromJson(jsonData, JsonObject.class);
			jwtToken = tokenResponse.get("access_token").getAsString();
			
			// JWT tokens from this API expire in 7 days, but we'll check earlier
			// Set expiry to 6 days to refresh before actual expiry
			tokenExpiry = System.currentTimeMillis() + (6 * 24 * 60 * 60 * 1000L);
			
			log.info("Successfully authenticated with API");
			return true;
		}
		catch (IOException e)
		{
			log.error("Failed to authenticate with API: {}", e.getMessage());
			return false;
		}
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
		
		String apiUrl = config.apiUrl();
		if (apiUrl == null || apiUrl.isEmpty())
		{
			return;
		}
		
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

		String apiUrl = config.apiUrl();
		if (apiUrl == null || apiUrl.isEmpty())
		{
			log.warn("API URL not configured");
			return null;
		}
		
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

