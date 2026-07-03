package com.flipsmart.api.endpoints;

import com.flipsmart.FlipSmartApiClient;
import com.flipsmart.api.ApiHttpTransport;
import com.flipsmart.api.dto.OfferAdviceBatchResponse;
import com.flipsmart.api.dto.OfferAdviceRequest;
import com.flipsmart.api.dto.SellPriceCheckRequest;
import com.flipsmart.api.dto.SellPriceCheckResponse;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.flipsmart.api.ApiHttpTransport.JSON;

/**
 * Batch offer-action and sell-price-check advice endpoints.
 *
 * The JSON body builders live as static helpers on {@link FlipSmartApiClient}
 * to preserve their existing package-visible test surface; this group delegates
 * to them so the wire format stays identical.
 */
public class OfferActionEndpoints
{
	private final ApiHttpTransport transport;

	public OfferActionEndpoints(ApiHttpTransport transport)
	{
		this.transport = transport;
	}

	public CompletableFuture<OfferAdviceBatchResponse> postOfferActionsBatchAsync(List<OfferAdviceRequest> reqs)
	{
		String url = String.format("%s/flip-finder/active/offer-actions", transport.getApiUrl());
		RequestBody body = RequestBody.create(JSON, FlipSmartApiClient.buildOfferActionsBody(reqs).toString());
		Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			transport.parse(jsonData, OfferAdviceBatchResponse.class));
	}

	public CompletableFuture<SellPriceCheckResponse> postSellPriceCheckAsync(SellPriceCheckRequest req)
	{
		String url = String.format("%s/flip-finder/active/sell-price-check", transport.getApiUrl());
		RequestBody body = RequestBody.create(JSON, FlipSmartApiClient.buildSellPriceCheckBody(req).toString());
		Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
		return transport.executeAuthenticatedAsync(requestBuilder, jsonData ->
			transport.parse(jsonData, SellPriceCheckResponse.class));
	}
}
