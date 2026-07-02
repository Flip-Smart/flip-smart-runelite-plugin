package com.flipsmart.api;

import com.flipsmart.FlipSmartConfig;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ApiHttpTransportBackoffTest
{
	private final long[] now = {0L};
	private OkHttpClient httpClient;
	private Call call;
	private ApiBackoffGate gate;
	private ApiHttpTransport transport;

	@Before
	public void setUp()
	{
		httpClient = mock(OkHttpClient.class);
		call = mock(Call.class);
		when(httpClient.newCall(any(Request.class))).thenReturn(call);
		gate = new ApiBackoffGate(new Random(1), () -> now[0]);
		transport = new ApiHttpTransport(httpClient, new Gson(), mock(FlipSmartConfig.class), gate);
	}

	private static Request request()
	{
		return new Request.Builder().url("https://api.flipsm.art/test").build();
	}

	private static Response response(Request req, int code)
	{
		return new Response.Builder()
			.request(req)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("test")
			.body(ResponseBody.create(ApiHttpTransport.JSON, "{}"))
			.build();
	}

	private Callback enqueuedCallback()
	{
		ArgumentCaptor<Callback> captor = ArgumentCaptor.forClass(Callback.class);
		verify(call).enqueue(captor.capture());
		return captor.getValue();
	}

	@Test
	public void shortCircuitsDuringCooldownWithoutSendingRequest() throws Exception
	{
		gate.recordFailure();
		AtomicReference<String> error = new AtomicReference<>();

		CompletableFuture<String> future =
			transport.executeAsync(request(), body -> body, error::set, false);

		assertNull(future.get());
		assertEquals("API backing off after repeated failures", error.get());
		verify(httpClient, never()).newCall(any(Request.class));
	}

	@Test
	public void serverErrorOpensTheGate() throws Exception
	{
		Request req = request();
		CompletableFuture<String> future =
			transport.executeAsync(req, body -> body, null, false);
		enqueuedCallback().onResponse(call, response(req, 500));
		assertNull(future.get());

		transport.executeAsync(request(), body -> body, null, false);
		verify(httpClient, times(1)).newCall(any(Request.class));
	}

	@Test
	public void ioFailureOpensTheGate() throws Exception
	{
		CompletableFuture<String> future =
			transport.executeAsync(request(), body -> body, null, false);
		enqueuedCallback().onFailure(call, new IOException("timeout"));
		assertNull(future.get());

		transport.executeAsync(request(), body -> body, null, false);
		verify(httpClient, times(1)).newCall(any(Request.class));
	}

	@Test
	public void clientErrorDoesNotOpenTheGate() throws Exception
	{
		Request req = request();
		CompletableFuture<String> future =
			transport.executeAsync(req, body -> body, null, false);
		enqueuedCallback().onResponse(call, response(req, 404));
		assertNull(future.get());

		transport.executeAsync(request(), body -> body, null, false);
		verify(httpClient, times(2)).newCall(any(Request.class));
	}

	@Test
	public void successClosesTheGate() throws Exception
	{
		Request req = request();
		transport.executeAsync(req, body -> body, null, false);
		enqueuedCallback().onFailure(call, new IOException("down"));

		now[0] += ApiBackoffGate.MAX_COOLDOWN_MS;
		Call recoveryCall = mock(Call.class);
		when(httpClient.newCall(any(Request.class))).thenReturn(recoveryCall);
		CompletableFuture<String> future =
			transport.executeAsync(req, body -> body, null, false);
		ArgumentCaptor<Callback> captor = ArgumentCaptor.forClass(Callback.class);
		verify(recoveryCall).enqueue(captor.capture());
		captor.getValue().onResponse(recoveryCall, response(req, 200));
		assertEquals("{}", future.get());

		transport.executeAsync(request(), body -> body, null, false);
		verify(httpClient, times(3)).newCall(any(Request.class));
	}
}
