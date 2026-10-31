package com.flipsmart.api;

import com.flipsmart.FlipSmartConfig;
import com.flipsmart.api.dto.Dtos.FlipFinderResponse;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;
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
}
