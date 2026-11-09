package com.flipsmart.api;

import com.flipsmart.FlipSmartConfig;
import com.flipsmart.api.dto.Dtos.FlipFinderResponse;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import java.util.concurrent.CompletableFuture;
import okhttp3.Call;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

public class ApiHttpTransportEconomyHeaderTest
{
	private final ApiHttpTransport transport =
		new ApiHttpTransport(mock(OkHttpClient.class), new Gson(), mock(FlipSmartConfig.class));

	private String economyHeader()
	{
		Request.Builder builder = new Request.Builder().url("https://api.flipsm.art/flip-finder");
		return transport.withAuthHeader(builder).build().header(ApiHttpTransport.HEADER_ECONOMY);
	}

	@Test
	public void defaultsToMainEconomy()
	{
		assertEquals("main", economyHeader());
	}

	@Test
	public void hardcoreWorldSendsHardcoreEconomy()
	{
		transport.setHardcoreWorld(true);
		assertEquals("hardcore", economyHeader());
	}

	@Test
	public void hoppingBackToMainWorldRestoresMainEconomy()
	{
		transport.setHardcoreWorld(true);
		transport.setHardcoreWorld(false);
		assertEquals("main", economyHeader());
	}

	@Test
	public void flipFinderNoticeDeserializes()
	{
		FlipFinderResponse withNotice = new Gson().fromJson(
			"{\"recommendations\":[],\"notice\":\"Hardcore Worlds support is coming soon\"}", FlipFinderResponse.class);
		assertEquals("Hardcore Worlds support is coming soon", withNotice.getNotice());

		FlipFinderResponse legacy = new Gson().fromJson("{\"recommendations\":[]}", FlipFinderResponse.class);
		assertNull(legacy.getNotice());
	}

	@Test
	public void requestKeepsTheEconomyItWasMadeOnAcrossAnAuthWait()
	{
		OkHttpClient http = mock(OkHttpClient.class);
		when(http.newCall(org.mockito.ArgumentMatchers.any())).thenReturn(mock(Call.class));
		ApiHttpTransport t = spy(new ApiHttpTransport(http, new Gson(), mock(FlipSmartConfig.class)));
		CompletableFuture<Boolean> auth = new CompletableFuture<>();
		doReturn(auth).when(t).ensureAuthenticatedAsync();

		t.executeAuthenticatedAsync(new Request.Builder().url("https://api.flipsm.art/transactions"), body -> body);
		t.setHardcoreWorld(true);
		auth.complete(true);

		ArgumentCaptor<Request> sent = ArgumentCaptor.forClass(Request.class);
		verify(http).newCall(sent.capture());
		assertEquals("a trade made on main is filed as main even if the player hopped meanwhile",
			"main", sent.getValue().header(ApiHttpTransport.HEADER_ECONOMY));
	}
}
