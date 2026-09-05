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
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Singleton
final class SixthDegreeApiClient
{
	static final String API_BASE = "https://amnesty-bootlace-poach.ngrok-free.dev/runelite/v1";
	private static final MediaType PNG = MediaType.get("image/png");

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	SixthDegreeApiClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	CompletableFuture<AuthStart> startDiscordAuth(String rsn)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("rsn", rsn);
		Request request = baseRequest(API_BASE + "/auth/session")
			.post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(payload)))
			.build();
		return sendJson(request, AuthStart.class);
	}

	CompletableFuture<AuthStatus> getDiscordAuthStatus(String requestId)
	{
		Request request = baseRequest(API_BASE + "/auth/status/" + requestId).get().build();
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
		return getAuthed("/competitions/" + kind + "/state", sessionToken, CompetitionResponse.class);
	}

	CompletableFuture<CompetitionProgressResponse> postCompetitionProgress(
		String kind,
		String sessionToken,
		int eventId,
		long delta,
		long currentValue,
		long observedAt,
		String telemetryId)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("event_id", eventId);
		payload.addProperty("delta", delta);
		payload.addProperty("current_value", currentValue);
		payload.addProperty("observed_at", observedAt);
		payload.addProperty("telemetry_id", telemetryId);
		Request request = authedRequest(API_BASE + "/competitions/" + kind + "/progress", sessionToken)
			.post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(payload)))
			.build();
		return sendJson(request, CompetitionProgressResponse.class);
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
		Request request = authedRequest(API_BASE + "/lfg", sessionToken)
			.post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(payload)))
			.build();
		return sendJson(request, LfgPostResponse.class);
	}

	CompletableFuture<BasicResponse> deleteMyLfg(String sessionToken)
	{
		Request request = authedRequest(API_BASE + "/lfg/me", sessionToken).delete().build();
		return sendJson(request, BasicResponse.class);
	}

	CompletableFuture<NotificationResponse> getNotificationRules(String sessionToken)
	{
		return getAuthed("/notifications", sessionToken, NotificationResponse.class);
	}

	CompletableFuture<String> getNpcDropRarityData(String sessionToken)
	{
		Request request = authedRequest(API_BASE + "/rarity/npc-drops", sessionToken).get().build();
		return sendText(request);
	}

	CompletableFuture<NotificationPostResponse> postNotification(
		String sessionToken,
		SixthDegreeNotificationEvent event,
		byte[] screenshotPng)
	{
		MultipartBody.Builder body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("event_id", safe(event.eventId))
			.addFormDataPart("type", safe(event.type))
			.addFormDataPart("title", safe(event.title))
			.addFormDataPart("detail", safe(event.detail))
			.addFormDataPart("source", safe(event.source))
			.addFormDataPart("value_gp", Long.toString(event.valueGp))
			.addFormDataPart("occurred_at", Long.toString(event.occurredAt))
			.addFormDataPart("item_id", Integer.toString(event.itemId))
			.addFormDataPart("rarity_triggered", Boolean.toString(event.rarityTriggered));

		if (screenshotPng != null && screenshotPng.length > 0)
		{
			body.addFormDataPart(
				"image",
				"sixth-degree.png",
				RequestBody.create(PNG, screenshotPng));
		}

		Request request = authedRequest(API_BASE + "/notifications/submit", sessionToken)
			.post(body.build())
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
		Request request = authedRequest(API_BASE + "/telemetry/loot-batch", sessionToken)
			.post(RequestBody.create(RuneLiteAPI.JSON, gson.toJson(payload)))
			.build();
		return sendJson(request, LootBatchResponse.class);
	}

	CompletableFuture<LootLeaderboardResponse> getLootLeaderboard(String period, String sessionToken)
	{
		return getAuthed("/leaderboards/loot?period=" + period, sessionToken, LootLeaderboardResponse.class);
	}

	private <T> CompletableFuture<T> getAuthed(String path, String token, Class<T> type)
	{
		Request request = authedRequest(API_BASE + path, token).get().build();
		return sendJson(request, type);
	}

	private Request.Builder authedRequest(String url, String token)
	{
		return baseRequest(url).header("Authorization", "Bearer " + token);
	}

	private Request.Builder baseRequest(String url)
	{
		return new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.header("User-Agent", "Sixth-Degree-RuneLite/0.1.3")
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
					String body = responseText(response);
					ensureSuccess(response.code(), body);
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

	private CompletableFuture<String> sendText(Request request)
	{
		CompletableFuture<String> future = new CompletableFuture<>();
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
					String body = responseText(response);
					ensureSuccess(response.code(), body);
					future.complete(body);
				}
				catch (Exception e)
				{
					future.completeExceptionally(e);
				}
			}
		});
		return future;
	}

	private static String responseText(Response response) throws IOException
	{
		ResponseBody body = response.body();
		return body == null ? "" : body.string();
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

	private static String safe(String value)
	{
		return value == null ? "" : value;
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
		int event_id;
		String title;
		String description;
		String metric;
		long start_time;
		long end_time;
		String prize;
		String status;
		boolean paused;
		boolean live;
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

	static final class CompetitionProgressResponse
	{
		boolean ok;
		boolean accepted;
		boolean paused;
		Standing standing;
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
