package com.sixthdegree;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

final class SixthDegreeApiClient
{
	static final String API_BASE = "https://amnesty-bootlace-poach.ngrok-free.dev/runelite/v1";

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private final Gson gson = new Gson();

	CompletableFuture<AuthStart> startDiscordAuth(String rsn)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);
		HttpRequest request = baseRequest(API_BASE + "/auth/session")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
			.build();
		return sendJson(request, AuthStart.class);
	}

	CompletableFuture<AuthStatus> getDiscordAuthStatus(String requestId)
	{
		HttpRequest request = baseRequest(API_BASE + "/auth/status/" + requestId)
			.GET()
			.build();
		return sendJson(request, AuthStatus.class);
	}

	CompletableFuture<MemberStatus> getMemberStatus(String sessionToken)
	{
		HttpRequest request = baseRequest(API_BASE + "/me")
			.header("Authorization", "Bearer " + sessionToken)
			.GET()
			.build();
		return sendJson(request, MemberStatus.class);
	}

	private HttpRequest.Builder baseRequest(String url)
	{
		return HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(15))
			.header("Accept", "application/json")
			.header("User-Agent", "Sixth-Degree-RuneLite/0.1")
			.header("ngrok-skip-browser-warning", "sixth-degree-runelite");
	}

	private <T> CompletableFuture<T> sendJson(HttpRequest request, Class<T> type)
	{
		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(response ->
			{
				int status = response.statusCode();
				if (status < 200 || status >= 300)
				{
					String reason = "Request failed";
					try
					{
						JsonObject error = gson.fromJson(response.body(), JsonObject.class);
						if (error != null)
						{
							if (error.has("reason"))
							{
								reason = error.get("reason").getAsString();
							}
							else if (error.has("error"))
							{
								reason = error.get("error").getAsString();
							}
						}
					}
					catch (Exception ignored)
					{
						// Preserve the generic error without logging response bodies.
					}
					throw new ApiException(status, reason);
				}
				return gson.fromJson(response.body(), type);
			});
	}

	static final class ApiException extends RuntimeException
	{
		private final int statusCode;

		ApiException(int statusCode, String message)
		{
			super(message);
			this.statusCode = statusCode;
		}

		int getStatusCode()
		{
			return statusCode;
		}
	}

	static final class AuthStart
	{
		String request_id;
		String authorize_url;
		int expires_in;
	}

	static final class AuthStatus
	{
		String status;
		String session_token;
		String discord_user_id;
		String rsn;
		String reason;
	}

	static final class MemberStatus
	{
		boolean ok;
		String access;
		String rsn;
		String discord_user_id;
		boolean clan_member_role;
		int role_recheck_seconds;
	}
}
