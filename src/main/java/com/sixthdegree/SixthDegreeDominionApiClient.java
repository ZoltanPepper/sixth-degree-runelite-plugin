package com.sixthdegree;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
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
		boolean available;
		boolean scoring_active;
		String reason;
		String event_phase;
		long server_time;
		Season season;
		Day day;
		Team team;
		@SerializedName("public")
		PublicState publicState;
	}

	static final class Season
	{
		int id;
		String slug;
		String title;
		String status;
		long start_time;
		long end_time;
		String resolution_timezone;
		int resolution_hour;
		int resolution_minute;
	}

	static final class Day
	{
		int season_id;
		int day_number;
		long starts_at;
		long ends_at;
		String status;
		long resolved_at;
	}

	static final class Team
	{
		int id;
		String code;
		String name;
		AttackOrders attack_orders;
		WarReserve war_reserve;
	}

	static final class AttackOrders
	{
		boolean locked;
		AttackOrder[] orders;
	}

	static final class AttackOrder
	{
		int slot_no;
		String region_id;
		String status;
		long locked_at;
	}

	static final class WarReserve
	{
		long gross_milli;
		long deployed_milli;
		long available_milli;
		String gross_influence;
		String deployed_influence;
		String available_influence;
	}

	static final class PublicState
	{
		Territory[] territories;
		Standing[] dominion_standings;
		Battle[] battles;
	}

	static final class Territory
	{
		String region_id;
		Integer owner_team_id;
		String owner_team_code;
		String owner_team_name;
		int held_since_day;
		int consecutive_days_held;
		long updated_at;
	}

	static final class Standing
	{
		int team_id;
		String code;
		String name;
		int points;
	}

	static final class Battle
	{
		int id;
		int season_id;
		int day_number;
		String region_id;
		Integer previous_owner_team_id;
		Integer winner_team_id;
		String previous_owner_code;
		String status;
		long opened_at;
		long resolved_at;
		BattleTeam[] teams;
		BattleScore[] scores;
	}

	static final class BattleTeam
	{
		int team_id;
		String role;
		String code;
		String name;
	}

	static final class BattleScore
	{
		int team_id;
		String code;
		String name;
		long routine_milli;
		long nonroutine_milli;
		long war_deployed_milli;
		int contributor_count;
		int unity_bonus_bp;
		int catch_up_bonus_bp;
		long routine_adjusted_milli;
		long final_milli;
	}

	static final class TelemetryResponse
	{
		boolean ok;
		boolean accepted;
		boolean active;
		String reason;
	}
}
