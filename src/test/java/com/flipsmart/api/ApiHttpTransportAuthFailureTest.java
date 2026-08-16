package com.flipsmart.api;

import com.flipsmart.FlipSmartConfig;
import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import okhttp3.Callback;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ApiHttpTransportAuthFailureTest
{
	private OkHttpClient httpClient;
	private Call call;
	private ApiHttpTransport transport;

	@Before
	public void setUp()
	{
		httpClient = mock(OkHttpClient.class);
		call = mock(Call.class);
		when(httpClient.newCall(any(Request.class))).thenReturn(call);
		// Mocked config returns null email/password, so password login always fails validation.
		transport = new ApiHttpTransport(httpClient, new Gson(), mock(FlipSmartConfig.class));
	}

	private static Request.Builder authRequest()
	{
		return new Request.Builder().url("https://api.flipsm.art/protected");
	}

	private static Response response(int code)
	{
		Request req = new Request.Builder().url("https://api.flipsm.art/auth/refresh").build();
		return new Response.Builder()
			.request(req)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("test")
			.body(ResponseBody.create(ApiHttpTransport.JSON, "{}"))
			.build();
	}

	@Test
	public void terminalAuthFailurePromptsReloginExactlyOnce() throws Exception
	{
		AtomicInteger prompts = new AtomicInteger();
		transport.setOnAuthFailure(prompts::incrementAndGet);

		// No refresh token and no credentials -> auth is terminally unrecoverable.
		assertNull(transport.executeAuthenticatedAsync(authRequest(), body -> body).get());
		assertNull(transport.executeAuthenticatedAsync(authRequest(), body -> body).get());

		// A burst of failures raises the prompt once, not once per request.
		assertEquals(1, prompts.get());
	}

	@Test
	public void logoutRearmsPromptForNextFailureEpisode() throws Exception
	{
		AtomicInteger prompts = new AtomicInteger();
		transport.setOnAuthFailure(prompts::incrementAndGet);

		assertNull(transport.executeAuthenticatedAsync(authRequest(), body -> body).get());
		assertEquals(1, prompts.get());

		transport.clearAuth();

		// A fresh failure episode after logout must prompt again, not stay
		// suppressed by the flag from the previous episode.
		assertNull(transport.executeAuthenticatedAsync(authRequest(), body -> body).get());
		assertEquals(2, prompts.get());
	}

	@Test
	public void transientRefreshFailureDoesNotPrompt() throws Exception
	{
		AtomicInteger prompts = new AtomicInteger();
		transport.setOnAuthFailure(prompts::incrementAndGet);
		transport.setRefreshToken("rt");

		CompletableFuture<String> future =
			transport.executeAuthenticatedAsync(authRequest(), body -> body);

		// The refresh exchange gets a 5xx: transient, refresh token is retained, so the
		// next attempt can still recover on its own -> no re-login prompt.
		ArgumentCaptor<Callback> captor = ArgumentCaptor.forClass(Callback.class);
		verify(call).enqueue(captor.capture());
		captor.getValue().onResponse(call, response(500));

		assertNull(future.get());
		assertEquals(0, prompts.get());
	}
}
