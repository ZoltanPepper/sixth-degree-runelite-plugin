package com.sixthdegree;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
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
		HttpRequest request = baseRequest(API_BASE + "/auth/status/" + requestId).GET().build();
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
		HttpRequest request = authedRequest(API_BASE + "/lfg/me", sessionToken).DELETE().build();
		return sendJson(request, BasicResponse.class);
	}

	CompletableFuture<NotificationResponse> getNotificationRules(String sessionToken)
	{
		return getAuthed("/notifications", sessionToken, NotificationResponse.class);
	}

	CompletableFuture<String> getNpcDropRarityData(String sessionToken)
	{
		HttpRequest request = authedRequest(API_BASE + "/rarity/npc-drops", sessionToken).GET().build();
		return sendText(request);
	}

	CompletableFuture<NotificationPostResponse> postNotification(
		String sessionToken,
		SixthDegreeNotificationEvent event,
		byte[] screenshotPng)
	{
		String boundary = "----SixthDegree" + UUID.randomUUID().toString().replace("-", "");
		byte[] body;
		try
		{
			body = multipart(boundary, event, screenshotPng);
		}
		catch (IOException e)
		{
			CompletableFuture<NotificationPostResponse> failed = new CompletableFuture<>();
			failed.completeExceptionally(e);
			return failed;
		}

		HttpRequest request = authedRequest(API_BASE + "/notifications/submit", sessionToken)
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build();
		return sendJson(request, NotificationPostResponse.class);
	}

	CompletableFuture<LootBatchResponse> postLootBatch(
		String sessionToken,
		String batchId,
		long valueGp,
		int dropCount,
		long recordedAt)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("batch_id", batchId);
		payload.addProperty("value_gp", valueGp);
		payload.addProperty("drop_count", dropCount);
		payload.addProperty("recorded_at", recordedAt);
		HttpRequest request = authedRequest(API_BASE + "/telemetry/loot-batch", sessionToken)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
			.build();
		return sendJson(request, LootBatchResponse.class);
	}

	CompletableFuture<LootLeaderboardResponse> getLootLeaderboard(String period, String sessionToken)
	{
		return getAuthed("/leaderboards/loot?period=" + period, sessionToken, LootLeaderboardResponse.class);
	}

	private <T> CompletableFuture<T> getAuthed(String path, String token, Class<T> type)
	{
		HttpRequest request = authedRequest(API_BASE + path, token).GET().build();
		return sendJson(request, type);
	}

	private HttpRequest.Builder authedRequest(String url, String token)
	{
		return baseRequest(url).header("Authorization", "Bearer " + token);
	}

	private HttpRequest.Builder baseRequest(String url)
	{
		return HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(20))
			.header("Accept", "application/json")
			.header("User-Agent", "Sixth-Degree-RuneLite/0.5")
			.header("ngrok-skip-browser-warning", "sixth-degree-runelite");
	}

	private <T> CompletableFuture<T> sendJson(HttpRequest request, Class<T> type)
	{
		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(response ->
			{
				ensureSuccess(response.statusCode(), response.body());
				return gson.fromJson(response.body(), type);
			});
	}

	private CompletableFuture<String> sendText(HttpRequest request)
	{
		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(response ->
			{
				ensureSuccess(response.statusCode(), response.body());
				return response.body();
			});
	}

	private void ensureSuccess(int status, String body)
	{
		if (status >= 200 && status < 300)
		{
			return;
		}
		String reason = defaultReason(status);
		try
		{
			JsonObject error = gson.fromJson(body, JsonObject.class);
			if (error != null)
			{
				if (error.has("reason"))
				{
					reason = error.get("reason").getAsString();
				}
				else if (error.has("error"))
				{
					reason = error.get("error").getAsString() + " (HTTP " + status + ")";
				}
			}
		}
		catch (Exception ignored)
		{
			// Keep the status-based message for non-JSON proxy responses.
		}
		throw new ApiException(status, reason);
	}

	private static byte[] multipart(
		String boundary,
		SixthDegreeNotificationEvent event,
		byte[] screenshotPng) throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream(
			screenshotPng == null ? 4096 : screenshotPng.length + 4096);
		writeField(output, boundary, "event_id", event.eventId);
		writeField(output, boundary, "type", event.type);
		writeField(output, boundary, "title", event.title);
		writeField(output, boundary, "detail", event.detail);
		writeField(output, boundary, "source", event.source);
		writeField(output, boundary, "value_gp", Long.toString(event.valueGp));
		writeField(output, boundary, "occurred_at", Long.toString(event.occurredAt));
		writeField(output, boundary, "item_id", Integer.toString(event.itemId));
		if (screenshotPng != null && screenshotPng.length > 0)
		{
			writeAscii(output, "--" + boundary + "\r\n");
			writeAscii(output, "Content-Disposition: form-data; name=\"image\"; filename=\"sixth-degree.png\"\r\n");
			writeAscii(output, "Content-Type: image/png\r\n\r\n");
			output.write(screenshotPng);
			writeAscii(output, "\r\n");
		}
		writeAscii(output, "--" + boundary + "--\r\n");
		return output.toByteArray();
	}

	private static void writeField(
		ByteArrayOutputStream output,
		String boundary,
		String name,
		String value) throws IOException
	{
		writeAscii(output, "--" + boundary + "\r\n");
		writeAscii(output, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
		output.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
		writeAscii(output, "\r\n");
	}

	private static void writeAscii(ByteArrayOutputStream output, String text) throws IOException
	{
		output.write(text.getBytes(StandardCharsets.US_ASCII));
	}

	private static String defaultReason(int status)
	{
		if (status == 404)
		{
			return "Boss Lady is missing the latest RuneLite content API (HTTP 404). Run BotSync/update and restart the bot.";
		}
		if (status == 401 || status == 403)
		{
			return "RuneLite access needs to be revalidated (HTTP " + status + ").";
		}
		return "Sixth Degree API returned HTTP " + status + ".";
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

	static final class NotificationPostResponse
	{
		boolean ok;
		boolean posted;
		boolean duplicate;
	}

	static final class LootBatchResponse
	{
		boolean ok;
		boolean accepted;
	}

	static final class LootLeaderboardResponse
	{
		boolean ok;
		String period;
		long since;
		long server_time;
		long round_display_to_gp;
		LootLeaderboardEntry[] entries;
		LootLeaderboardEntry you;
	}

	static final class LootLeaderboardEntry
	{
		int rank;
		String rsn;
		long value_gp;
		long drop_count;
		long updated_at;
	}
}
