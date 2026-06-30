package com.flipsmart.api;

import com.flipsmart.FlipSmartConfig;
import com.flipsmart.api.dto.OfferAdviceResponse;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Pins the shared {@link ApiHttpTransport#parse(String, Class)} helper that
 * replaces the per-endpoint {@code gson.fromJson(body, T.class)} boilerplate.
 * It is a thin delegate to the transport's Gson, so it must behave exactly as a
 * direct {@code gson.fromJson} call — including returning {@code null} for an
 * empty/null body.
 */
public class ApiHttpTransportParseTest
{
	private ApiHttpTransport transport;

	@Before
	public void setUp()
	{
		transport = new ApiHttpTransport(
			mock(OkHttpClient.class), new Gson(), mock(FlipSmartConfig.class));
	}

	@Test
	public void parseDeserializesDtoBody()
	{
		OfferAdviceResponse r = transport.parse(
			"{\"action\":\"wait\",\"reason\":\"hold\"}", OfferAdviceResponse.class);
		assertEquals("wait", r.getAction());
		assertEquals("hold", r.getReason());
	}

	@Test
	public void parseReturnsNullForEmptyBody()
	{
		assertNull(transport.parse("", OfferAdviceResponse.class));
	}

	@Test
	public void parseReturnsNullForNullBody()
	{
		assertNull(transport.parse(null, OfferAdviceResponse.class));
	}
}
