package com.sixthdegree;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SixthDegreeMobileSotwCatchupTest
{
	private static void set(Object object, String name, Object value) throws Exception
	{
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(object, value);
	}

	private static Object sotwContext(SixthDegreeCompetitionTracker tracker) throws Exception
	{
		Field field = tracker.getClass().getDeclaredField("sotw");
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

	private static SixthDegreeApiClient.CompetitionResponse sotwState(
		long serverCurrent,
		boolean reconcileAllowed,
		boolean includeStanding)
	{
		SixthDegreeApiClient.Standing you = null;
		if (includeStanding)
		{
			you = new SixthDegreeApiClient.Standing();
			you.rsn = "Mobile Tester";
			you.current_value = serverCurrent;
			you.score = 100L;
		}

		SixthDegreeApiClient.Competition competition = new SixthDegreeApiClient.Competition();
		competition.event_id = 8;
		competition.metric = "Mining";
		competition.start_time = 0L;
		competition.end_time = Long.MAX_VALUE;
		competition.status = "ACTIVE";
		competition.live = true;
		competition.reconcile_allowed = reconcileAllowed;
		competition.you = you;
		competition.standings = you == null
			? new SixthDegreeApiClient.Standing[0]
			: new SixthDegreeApiClient.Standing[]{you};

		SixthDegreeApiClient.CompetitionResponse response = new SixthDegreeApiClient.CompetitionResponse();
		response.ok = true;
		response.type = "SOTW";
		response.active = competition;
		return response;
	}

	private static void stubProgress(SixthDegreeApiClient api)
	{
		when(api.postCompetitionProgress(anyString(), anyString(), eq(8), anyLong(), anyLong(), anyLong(), anyString()))
			.thenReturn(new CompletableFuture<>());
	}

	@Test
	public void restoresServerXpAndImmediatelyBackfillsMobileXp() throws Exception
	{
		Client client = mock(Client.class);
		when(client.getSkillExperience(Skill.MINING)).thenReturn(1_010_100);
		SixthDegreeApiClient api = mock(SixthDegreeApiClient.class);
		stubProgress(api);
		SixthDegreeCompetitionTracker tracker = new SixthDegreeCompetitionTracker(client, null, api);
		set(tracker, "active", true);
		set(tracker, "sessionToken", "test-token");

		applyState(tracker, sotwContext(tracker), sotwState(1_000_100L, true, true));

		verify(api).postCompetitionProgress(
			eq("SOTW"), eq("test-token"), eq(8), eq(10_000L), eq(1_010_100L), anyLong(), anyString());
	}

	@Test
	public void firstObservationStoresBaselineWithoutAwardingLifetimeXp() throws Exception
	{
		Client client = mock(Client.class);
		when(client.getSkillExperience(Skill.MINING)).thenReturn(25_000_000);
		SixthDegreeApiClient api = mock(SixthDegreeApiClient.class);
		stubProgress(api);
		SixthDegreeCompetitionTracker tracker = new SixthDegreeCompetitionTracker(client, null, api);
		set(tracker, "active", true);
		set(tracker, "sessionToken", "test-token");

		applyState(tracker, sotwContext(tracker), sotwState(0L, false, false));

		verify(api).postCompetitionProgress(
			eq("SOTW"), eq("test-token"), eq(8), eq(0L), eq(25_000_000L), anyLong(), anyString());
	}

	@Test
	public void historicalPauseStartsFreshBaselineWithoutBackfillingPausedXp() throws Exception
	{
		Client client = mock(Client.class);
		when(client.getSkillExperience(Skill.MINING)).thenReturn(1_010_100);
		SixthDegreeApiClient api = mock(SixthDegreeApiClient.class);
		stubProgress(api);
		SixthDegreeCompetitionTracker tracker = new SixthDegreeCompetitionTracker(client, null, api);
		set(tracker, "active", true);
		set(tracker, "sessionToken", "test-token");

		applyState(tracker, sotwContext(tracker), sotwState(1_000_100L, false, true));

		verify(api).postCompetitionProgress(
			eq("SOTW"), eq("test-token"), eq(8), eq(0L), eq(1_010_100L), anyLong(), anyString());
	}
}
