package com.sixthdegree;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SixthDegreeMobileBotwCatchupTest
{
	private static void set(Object object, String name, Object value) throws Exception
	{
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(object, value);
	}

	private static Object botwContext(SixthDegreeCompetitionTracker tracker) throws Exception
	{
		Field field = tracker.getClass().getDeclaredField("botw");
		field.setAccessible(true);
		return field.get(tracker);
	}

	private static void applyState(
		SixthDegreeCompetitionTracker tracker,
		Object context,
		SixthDegreeApiClient.CompetitionResponse response) throws Exception
	{
		Method method = tracker.getClass().getDeclaredMethod(
			"applyState",
			context.getClass(),
			String.class,
			SixthDegreeApiClient.CompetitionResponse.class,
			Throwable.class);
		method.setAccessible(true);
		method.invoke(tracker, context, "test-token", response, null);
	}

	private static SixthDegreeApiClient.CompetitionResponse botwState(
		long serverCurrent,
		boolean paused,
		boolean reconcileAllowed)
	{
		SixthDegreeApiClient.Standing you = new SixthDegreeApiClient.Standing();
		you.rsn = "Mobile Tester";
		you.current_value = serverCurrent;
		you.score = 1L;

		SixthDegreeApiClient.Competition competition = new SixthDegreeApiClient.Competition();
		competition.event_id = 7;
		competition.metric = "Vorkath";
		competition.start_time = 0L;
		competition.end_time = Long.MAX_VALUE;
		competition.status = "ACTIVE";
		competition.paused = paused;
		competition.live = !paused;
		competition.reconcile_allowed = reconcileAllowed;
		competition.you = you;
		competition.standings = new SixthDegreeApiClient.Standing[]{you};

		SixthDegreeApiClient.CompetitionResponse response = new SixthDegreeApiClient.CompetitionResponse();
		response.ok = true;
		response.type = "BOTW";
		response.active = competition;
		return response;
	}

	@Test
	public void restoresServerKcAndBackfillsMobileKillsOnNextObservedKc() throws Exception
	{
		SixthDegreeApiClient api = mock(SixthDegreeApiClient.class);
		when(api.postCompetitionProgress(anyString(), anyString(), eq(7), anyLong(), anyLong(), anyLong(), anyString()))
			.thenReturn(new CompletableFuture<>());
		SixthDegreeCompetitionTracker tracker = new SixthDegreeCompetitionTracker(mock(Client.class), null, api);
		set(tracker, "active", true);
		set(tracker, "sessionToken", "test-token");
		Object context = botwContext(tracker);

		// Boss Lady last accepted absolute KC 1 before the player switched to mobile.
		applyState(tracker, context, botwState(1L, false, true));

		// Ten mobile kills plus the next RuneLite kill means the next observed count is 12.
		ChatMessage message = new ChatMessage();
		message.setMessage("Your Vorkath kill count is: 12.");
		tracker.onChatMessage(message);

		verify(api).postCompetitionProgress(
			eq("BOTW"), eq("test-token"), eq(7), eq(11L), eq(12L), anyLong(), anyString());
	}

	@Test
	public void freshSessionDoesNotBackfillAcrossHistoricalPause() throws Exception
	{
		SixthDegreeApiClient api = mock(SixthDegreeApiClient.class);
		when(api.postCompetitionProgress(anyString(), anyString(), eq(7), anyLong(), anyLong(), anyLong(), anyString()))
			.thenReturn(new CompletableFuture<>());
		SixthDegreeCompetitionTracker tracker = new SixthDegreeCompetitionTracker(mock(Client.class), null, api);
		set(tracker, "active", true);
		set(tracker, "sessionToken", "test-token");
		Object context = botwContext(tracker);

		// Boss Lady knows a pause happened after KC 1, so the old absolute value is
		// not safe to bridge from after a fresh RuneLite session.
		applyState(tracker, context, botwState(1L, false, false));

		ChatMessage message = new ChatMessage();
		message.setMessage("Your Vorkath kill count is: 12.");
		tracker.onChatMessage(message);

		verify(api).postCompetitionProgress(
			eq("BOTW"), eq("test-token"), eq(7), eq(1L), eq(12L), anyLong(), anyString());
	}

	@Test
	public void pauseResumeStillUsesFreshBaselineInsteadOfBackfillingPausedKills() throws Exception
	{
		SixthDegreeApiClient api = mock(SixthDegreeApiClient.class);
		when(api.postCompetitionProgress(anyString(), anyString(), eq(7), anyLong(), anyLong(), anyLong(), anyString()))
			.thenReturn(new CompletableFuture<>());
		SixthDegreeCompetitionTracker tracker = new SixthDegreeCompetitionTracker(mock(Client.class), null, api);
		set(tracker, "active", true);
		set(tracker, "sessionToken", "test-token");
		Object context = botwContext(tracker);

		// Simulate an already-known event that was paused with server KC 1.
		set(context, "eventId", 7);
		set(context, "metric", "Vorkath");
		set(context, "startTime", 0L);
		set(context, "endTime", Long.MAX_VALUE);
		set(context, "status", "ACTIVE");
		set(context, "paused", true);
		set(context, "ackedValue", 1L);
		set(context, "latestValue", 1L);
		set(context, "baselineSet", true);
		set(context, "needsFreshBossBaseline", true);

		applyState(tracker, context, botwState(1L, false, true));

		ChatMessage message = new ChatMessage();
		message.setMessage("Your Vorkath kill count is: 12.");
		tracker.onChatMessage(message);

		// The old pause safety remains: only the first post-resume kill is credited.
		verify(api).postCompetitionProgress(
			eq("BOTW"), eq("test-token"), eq(7), eq(1L), eq(12L), anyLong(), anyString());
	}
}