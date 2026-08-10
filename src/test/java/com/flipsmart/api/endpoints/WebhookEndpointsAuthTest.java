package com.flipsmart.api.endpoints;

import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.endpoints.Endpoints.WebhookEndpoints;

import okhttp3.Request;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The webhook endpoints were the only group in {@link Endpoints} that executed through the
 * unauthenticated {@code executeAsync}, which never attaches an Authorization header — the
 * header is only applied inside the 401 retry. Every webhook call therefore 401'd by
 * construction and was retried, which both doubled the request count and discarded a valid
 * JWT on each login and world hop.
 */
public class WebhookEndpointsAuthTest
{
	private ApiHttpTransport transport;
	private WebhookEndpoints endpoints;

	@Before
	public void setUp()
	{
		transport = mock(ApiHttpTransport.class);
		when(transport.getApiUrl()).thenReturn("http://api");
		endpoints = new WebhookEndpoints(transport);
	}

	private Request singleExecutedRequest()
	{
		ArgumentCaptor<Request.Builder> captor = ArgumentCaptor.forClass(Request.Builder.class);
		verify(transport).executeAuthenticatedAsync(captor.capture(), any(), any());
		verify(transport, never()).executeAsync(any(), any(), any(), anyBoolean());
		return captor.getValue().build();
	}

	@Test
	public void fetchIsAuthenticatedOnTheFirstAttempt()
	{
		endpoints.fetchWebhookConfigAsync(c -> { }, () -> { }, e -> { });

		Request request = singleExecutedRequest();
		assertEquals("GET", request.method());
		assertEquals("http://api/profile/webhook/url", request.url().toString());
	}

	@Test
	public void updateIsAuthenticatedOnTheFirstAttempt()
	{
		endpoints.updateWebhookAsync("https://discord.com/api/webhooks/1/a", true, false, () -> { }, e -> { });

		Request request = singleExecutedRequest();
		assertEquals("PUT", request.method());
		assertEquals("http://api/profile/webhook", request.url().toString());
		assertNotNull(request.body());
	}

	@Test
	public void deleteIsAuthenticatedOnTheFirstAttempt()
	{
		endpoints.deleteWebhookAsync(() -> { }, e -> { });

		Request request = singleExecutedRequest();
		assertEquals("DELETE", request.method());
		assertEquals("http://api/profile/webhook", request.url().toString());
	}
}
