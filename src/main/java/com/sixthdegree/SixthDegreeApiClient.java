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
		return getAuthed("/me", sessionToken, MemberStatus.class);
	}

	CompletableFuture<Dashboard> getDashboard(String sessionToken)
	{
		return getAuthed("/dashboard", sessionToken, Dashboard.class);
	}

	CompletableFuture<CompetitionResponse> getCompetition(String kind, String sessionToken)
	{
		return getAuthed("/competitions/" + kind, sessionToken, CompetitionResponse.class);
	}

	CompletableFuture<LfgResponse> getLfg(String sessionToken)
	{
		return getAuthed("/lfg", sessionToken, LfgResponse.class);
	}

	CompletableFuture<LfgPostResponse> postLfg(String sessionToken, String note, int world)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("note", note);
		payload.addProperty("world", world);
		HttpRequest request = authedRequest(API_BASE + "/lfg", sessionToken)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
			.build();
		return sendJson(request, LfgPostResponse.class);
	}

	CompletableFuture<BasicResponse> deleteMyLfg(String sessionToken)
	{
		HttpRequest request = authedRequest(API_BASE + "/lfg/me", sessionToken)
			.DELETE()
			.build();
		return sendJson(request, BasicResponse.class);
	}

	CompletableFuture<NotificationResponse> getNotificationRules(String sessionToken)
	{
		return getAuthed("/notifications", sessionToken, NotificationResponse.class);
	}

	private <T> CompletableFuture<T> getAuthed(String path, String token, Class<T> type)
	{
		HttpRequest request = authedRequest(API_BASE + path, token)
			.GET()
			.build();
		return sendJson(request, type);
	}

	private HttpRequest.Builder authedRequest(String url, String token)
	{
		return baseRequest(url).header("Authorization", "Bearer " + token);
	}

	private HttpRequest.Builder baseRequest(String url)
	{
		return HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(15))
			.header("Accept", "application/json")
			.header("User-Agent", "Sixth-Degree-RuneLite/0.2")
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
						// Do not surface arbitrary response bodies to RuneLite logs/UI.
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

	static final class BasicResponse
	{
		boolean ok;
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

	static final class Dashboard
	{
		boolean ok;
		long server_time;
		String rsn;
		String announcement;
		EventItem[] events;
		CompetitionResponse botw;
		CompetitionResponse sotw;
		int lfg_count;
		int standings_refresh_seconds;
	}

	static final class EventItem
	{
		int id;
		String title;
		String description;
		long start_time;
		long end_time;
		int attending_count;
		String event_type;
		int world;
		String location;
		String requirements;
		boolean live;
	}

	static final class CompetitionResponse
	{
		boolean ok;
		String type;
		Competition active;
		RecentCompetition[] recent;
	}

	static final class Competition
	{
		int id;
		String title;
		String metric;
		long start_time;
		long end_time;
		String prize;
		Standing[] standings;
		Standing you;
	}

	static final class RecentCompetition
	{
		int id;
		String title;
		String metric;
		long start_time;
		long end_time;
		String prize;
		String status;
	}

	static final class Standing
	{
		int rank;
		String rsn;
		long baseline;
		long current_value;
		long score;
		long updated_at;
	}

	static final class LfgResponse
	{
		boolean ok;
		LfgEntry[] entries;
		int ttl_seconds;
	}

	static final class LfgPostResponse
	{
		boolean ok;
		LfgEntry entry;
	}

	static final class LfgEntry
	{
		String rsn;
		String note;
		int world;
		long created_at;
		long expires_at;
	}

	static final class NotificationResponse
	{
		boolean ok;
		JsonObject rules;
		String[] member_controls;
	}
}
