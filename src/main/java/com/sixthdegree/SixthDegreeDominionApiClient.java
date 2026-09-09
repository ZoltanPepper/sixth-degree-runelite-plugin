package com.sixthdegree;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Singleton
final class SixthDegreeDominionApiClient
{
	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	SixthDegreeDominionApiClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	CompletableFuture<StateResponse> getState(String sessionToken)
	{
		Request request = authedRequest(SixthDegreeApiClient.API_BASE + "/dominion/state", sessionToken)
			.get()
			.build();
		return sendJson(request, StateResponse.class);
	}

	CompletableFuture<TelemetryResponse> postXp(String sessionToken, JsonObject payload)
	{
		return postTelemetry("/dominion/telemetry/xp", sessionToken, payload);
	}

	CompletableFuture<TelemetryResponse> postRaw(String sessionToken, JsonObject payload)
	{
		return postTelemetry("/dominion/telemetry/raw", sessionToken, payload);
	}

	private CompletableFuture<TelemetryResponse> postTelemetry(String path, String token, JsonObject payload)
	{
		Request request = authedRequest(SixthDegreeApiClient.API_BASE + path, token)
			.post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(payload)))
			.build();
		return sendJson(request, TelemetryResponse.class);
	}

	private Request.Builder authedRequest(String url, String token)
	{
		return new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.header("Authorization", "Bearer " + token)
			.header("User-Agent", "Sixth-Degree-RuneLite/0.1.3-dominion-draft")
			.header("ngrok-skip-browser-warning", "sixth-degree-runelite");
	}

	private <T> CompletableFuture<T> sendJson(Request request, Class<T> type)
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response ignored = response)
				{
					ResponseBody responseBody = response.body();
					String body = responseBody == null ? "" : responseBody.string();
					if (response.code() < 200 || response.code() >= 300)
					{
						throw new SixthDegreeApiClient.ApiException(response.code(), reason(response.code(), body));
					}
					future.complete(gson.fromJson(body, type));
				}
				catch (Exception e)
				{
					future.completeExceptionally(e);
				}
			}
		});
		return future;
	}

	private String reason(int status, String body)
	{
		try
		{
			JsonObject json = gson.fromJson(body, JsonObject.class);
			if (json != null && json.has("reason"))
			{
				return json.get("reason").getAsString();
			}
			if (json != null && json.has("error"))
			{
				return json.get("error").getAsString();
			}
		}
		catch (Exception ignored)
		{
			// Fall back to HTTP status.
		}
		return "Dominion API returned HTTP " + status;
	}

	static final class StateResponse
	{
		boolean ok;
		boolean active;
		String reason;
	}

	static final class TelemetryResponse
	{
		boolean ok;
		boolean accepted;
		boolean active;
		String reason;
	}
}
