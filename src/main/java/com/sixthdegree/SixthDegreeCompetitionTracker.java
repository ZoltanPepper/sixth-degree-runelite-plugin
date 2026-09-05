package com.sixthdegree;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;

@Slf4j
@Singleton
final class SixthDegreeCompetitionTracker
{
	private static final long STATE_REFRESH_SECONDS = 30L;
	private static final long MAX_BOTW_DELTA = 10_000L;
	private static final long MAX_SOTW_DELTA = 500_000_000L;
	private static final Pattern BOSS_COUNT = Pattern.compile(
		"Your (.+?)\\s(?:kill|chest|completion|harvest|success|opened)\\s?count is: ?([\\d,]+)\\b",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern BOSS_COUNT_SECONDARY = Pattern.compile(
		"Your (?:completed|subdued) (.+?) count is: ([\\d,]+)\\b",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern RIFTS_CLOSED = Pattern.compile(
		"^Amount of rifts you have closed: ([0-9][0-9,]*)\\.$", Pattern.CASE_INSENSITIVE);

	private final Client client;
	private final ClientThread clientThread;
	private final SixthDegreeApiClient apiClient;
	private final CompetitionContext botw = new CompetitionContext("BOTW");
	private final CompetitionContext sotw = new CompetitionContext("SOTW");
	private final AtomicBoolean botwRefreshInFlight = new AtomicBoolean(false);
	private final AtomicBoolean sotwRefreshInFlight = new AtomicBoolean(false);

	private ScheduledExecutorService scheduler;
	private volatile String sessionToken;
	private volatile boolean active;

	@Inject
	SixthDegreeCompetitionTracker(Client client, ClientThread clientThread, SixthDegreeApiClient apiClient)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.apiClient = apiClient;
	}

	void start()
	{
		if (scheduler != null && !scheduler.isShutdown())
		{
			return;
		}
		scheduler = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread thread = new Thread(r, "sixth-degree-competitions");
			thread.setDaemon(true);
			return thread;
		});
		scheduler.scheduleAtFixedRate(
			this::refreshState,
			STATE_REFRESH_SECONDS,
			STATE_REFRESH_SECONDS,
			TimeUnit.SECONDS);
	}

	void stop()
	{
		deactivate();
		if (scheduler != null)
		{
			scheduler.shutdownNow();
			scheduler = null;
		}
	}

	void activate(String token)
	{
		if (token == null || token.isBlank())
		{
			return;
		}
		boolean changed = !active || !token.equals(sessionToken);
		sessionToken = token;
		active = true;
		if (changed)
		{
			clientThread.invokeLater(() ->
			{
				botw.reset();
				sotw.reset();
			});
		}
		refreshState();
	}

	void deactivate()
	{
		active = false;
		sessionToken = null;
		clientThread.invokeLater(() ->
		{
			botw.reset();
			sotw.reset();
		});
	}

	void refreshState()
	{
		String token = sessionToken;
		if (!active || token == null || token.isBlank())
		{
			return;
		}
		refreshKind(botw, botwRefreshInFlight, token);
		refreshKind(sotw, sotwRefreshInFlight, token);
	}

	private void refreshKind(CompetitionContext context, AtomicBoolean inFlight, String token)
	{
		if (!inFlight.compareAndSet(false, true))
		{
			return;
		}
		apiClient.getCompetition(context.kind, token).whenComplete((response, error) ->
		{
			inFlight.set(false);
			clientThread.invokeLater(() -> applyState(context, token, response, error));
		});
	}

	private void applyState(
		CompetitionContext context,
		String requestedToken,
		SixthDegreeApiClient.CompetitionResponse response,
		Throwable error)
	{
		if (!active || !requestedToken.equals(sessionToken))
		{
			return;
		}
		if (error != null || response == null || !response.ok)
		{
			if (error != null)
			{
				log.debug("Sixth Degree {} state refresh failed", context.kind, error);
			}
			return;
		}
		SixthDegreeApiClient.Competition competition = response.active;
		if (competition == null || competition.event_id <= 0)
		{
			context.reset();
			return;
		}

		String newMetric = safe(competition.metric);
		boolean sameEvent = context.eventId == competition.event_id
			&& normalise(context.metric).equals(normalise(newMetric));
		boolean wasPaused = context.paused;
		if (!sameEvent)
		{
			context.reset();
		}

		context.eventId = competition.event_id;
		context.metric = newMetric;
		context.startTime = competition.start_time;
		context.endTime = competition.end_time;
		context.status = safe(competition.status).isBlank() ? "ACTIVE" : competition.status.toUpperCase(Locale.ROOT);
		context.paused = competition.paused;

		long now = nowSeconds();
		if ("SOTW".equals(context.kind))
		{
			context.skill = findSkill(context.metric);
			if (context.skill == null)
			{
				baselineTo(context, 0L);
				return;
			}
			long currentXp = Math.max(0, client.getSkillExperience(context.skill));
			if (!sameEvent || !context.baselineSet || wasPaused != context.paused || !context.isScorable(now))
			{
				baselineTo(context, currentXp);
			}
		}
		else if (!sameEvent || wasPaused != context.paused || !context.isScorable(now))
		{
			context.needsFreshBossBaseline = true;
		}

		if (context.isScorable(now))
		{
			flushProgress(context);
		}
	}

	void onChatMessage(ChatMessage event)
	{
		if (!active || event == null || botw.eventId <= 0)
		{
			return;
		}
		BossCount count = parseBossCount(event.getMessage());
		if (count == null || !metricMatches(botw.metric, count.boss))
		{
			return;
		}

		long value = Math.max(0L, count.count);
		long now = nowSeconds();
		if (!botw.isScorable(now))
		{
			baselineTo(botw, value);
			botw.needsFreshBossBaseline = true;
			return;
		}

		if (!botw.baselineSet || botw.needsFreshBossBaseline)
		{
			botw.ackedValue = Math.max(0L, value - 1L);
			botw.latestValue = value;
			botw.baselineSet = true;
			botw.needsFreshBossBaseline = false;
		}
		else if (value > botw.latestValue)
		{
			botw.latestValue = value;
		}
		else
		{
			return;
		}
		flushProgress(botw);
	}

	void onStatChanged(StatChanged event)
	{
		if (!active || event == null || sotw.eventId <= 0 || sotw.skill == null || event.getSkill() != sotw.skill)
		{
			return;
		}
		long xp = Math.max(0L, event.getXp());
		long now = nowSeconds();
		if (!sotw.baselineSet || !sotw.isScorable(now))
		{
			baselineTo(sotw, xp);
			return;
		}
		if (xp < sotw.latestValue)
		{
			baselineTo(sotw, xp);
			return;
		}
		if (xp == sotw.latestValue)
		{
			return;
		}
		sotw.latestValue = xp;
		flushProgress(sotw);
	}

	private void flushProgress(CompetitionContext context)
	{
		if (!active || context.sending || !context.baselineSet || !context.isScorable(nowSeconds()))
		{
			return;
		}
		if (context.latestValue <= context.ackedValue)
		{
			return;
		}

		long maxDelta = "BOTW".equals(context.kind) ? MAX_BOTW_DELTA : MAX_SOTW_DELTA;
		if (context.pendingTarget <= context.ackedValue || context.pendingTarget > context.latestValue)
		{
			context.pendingTarget = Math.min(context.latestValue, context.ackedValue + maxDelta);
			context.pendingDelta = context.pendingTarget - context.ackedValue;
			context.pendingObservedAt = nowSeconds();
			context.pendingTelemetryId = telemetryId(context, context.pendingTarget);
		}
		if (context.pendingDelta <= 0L || context.pendingTelemetryId == null)
		{
			return;
		}

		String token = sessionToken;
		if (token == null || token.isBlank())
		{
			return;
		}
		final int eventId = context.eventId;
		final long target = context.pendingTarget;
		final long delta = context.pendingDelta;
		final long observedAt = context.pendingObservedAt;
		final String telemetryId = context.pendingTelemetryId;
		context.sending = true;

		apiClient.postCompetitionProgress(
			context.kind,
			token,
			eventId,
			delta,
			target,
			observedAt,
			telemetryId).whenComplete((response, error) ->
				clientThread.invokeLater(() -> handleProgressResult(
					context, eventId, target, response, error)));
	}

	private void handleProgressResult(
		CompetitionContext context,
		int eventId,
		long target,
		SixthDegreeApiClient.CompetitionProgressResponse response,
		Throwable error)
	{
		context.sending = false;
		if (context.eventId != eventId)
		{
			return;
		}
		if (error != null)
		{
			Throwable cause = unwrap(error);
			if (cause instanceof SixthDegreeApiClient.ApiException)
			{
				int code = ((SixthDegreeApiClient.ApiException) cause).getStatusCode();
				if (code == 400 || code == 401 || code == 403 || code == 404 || code == 409)
				{
					clearPending(context);
					refreshState();
				}
			}
			return;
		}
		if (response == null || !response.ok)
		{
			return;
		}

		if (response.paused)
		{
			baselineTo(context, Math.max(context.latestValue, target));
			if ("BOTW".equals(context.kind))
			{
				context.needsFreshBossBaseline = true;
			}
			refreshState();
			return;
		}

		// accepted=false without paused means Boss Lady already saw this deterministic
		// telemetry ID. Treat it as acknowledged so a lost HTTP response cannot double-count.
		context.ackedValue = Math.max(context.ackedValue, target);
		context.latestValue = Math.max(context.latestValue, context.ackedValue);
		context.baselineSet = true;
		clearPending(context);
		flushProgress(context);
	}

	private static void baselineTo(CompetitionContext context, long value)
	{
		long safeValue = Math.max(0L, value);
		context.ackedValue = safeValue;
		context.latestValue = safeValue;
		context.baselineSet = true;
		clearPending(context);
	}

	private static void clearPending(CompetitionContext context)
	{
		context.pendingTarget = -1L;
		context.pendingDelta = 0L;
		context.pendingObservedAt = 0L;
		context.pendingTelemetryId = null;
	}

	private String telemetryId(CompetitionContext context, long target)
	{
		return context.kind.toLowerCase(Locale.ROOT)
			+ ":" + context.eventId
			+ ":" + accountDiscriminator()
			+ ":" + target;
	}

	private String accountDiscriminator()
	{
		Player player = client.getLocalPlayer();
		String name = player == null ? "unknown" : safe(player.getName());
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(normalise(name).getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < 6; i++)
			{
				out.append(String.format(Locale.ROOT, "%02x", digest[i]));
			}
			return out.toString();
		}
		catch (Exception ignored)
		{
			return "unknown000000";
		}
	}

	private static Skill findSkill(String metric)
	{
		String wanted = normalise(metric);
		if (wanted.isBlank())
		{
			return null;
		}
		for (Skill skill : Skill.values())
		{
			if (normalise(skill.getName()).equals(wanted))
			{
				return skill;
			}
		}
		return null;
	}

	static boolean metricMatches(String configured, String observed)
	{
		String a = normaliseBoss(configured);
		String b = normaliseBoss(observed);
		if (a.isBlank() || b.isBlank())
		{
			return false;
		}
		if (isGotr(configured) || isGotr(observed))
		{
			return isGotr(configured) && isGotr(observed);
		}
		return a.equals(b) || (a.length() >= 5 && b.length() >= 5 && (a.contains(b) || b.contains(a)));
	}

	static BossCount parseBossCount(String rawMessage)
	{
		if (rawMessage == null || rawMessage.isBlank())
		{
			return null;
		}
		String message = Text.removeTags(rawMessage).trim();
		Matcher rifts = RIFTS_CLOSED.matcher(message);
		if (rifts.matches())
		{
			try
			{
				return new BossCount("GOTR", Long.parseLong(rifts.group(1).replace(",", "")));
			}
			catch (NumberFormatException ignored)
			{
				return null;
			}
		}
		Matcher matcher = BOSS_COUNT.matcher(message);
		if (!matcher.find())
		{
			matcher = BOSS_COUNT_SECONDARY.matcher(message);
			if (!matcher.find())
			{
				return null;
			}
		}
		try
		{
			String boss = matcher.group(1).trim();
			long count = Long.parseLong(matcher.group(2).replace(",", ""));
			return boss.isBlank() ? null : new BossCount(boss, count);
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	static boolean isGotr(String metric)
	{
		String value = normalise(metric);
		return value.equals("gotr") || value.equals("guardians of the rift") || value.equals("rifts closed");
	}

	private static String normaliseBoss(String value)
	{
		String normalised = normalise(value);
		return normalised.startsWith("the ") ? normalised.substring(4) : normalised;
	}

	private static String normalise(String value)
	{
		String clean = Text.removeTags(safe(value)).toLowerCase(Locale.ROOT)
			.replace('&', ' ')
			.replaceAll("[^a-z0-9]+", " ")
			.trim();
		return clean.replaceAll("\\s+", " ");
	}

	private static String safe(String value)
	{
		return value == null ? "" : value.trim();
	}

	private static long nowSeconds()
	{
		return System.currentTimeMillis() / 1000L;
	}

	private static Throwable unwrap(Throwable throwable)
	{
		Throwable current = throwable;
		while (current != null && current.getCause() != null
			&& current.getClass().getName().contains("Completion"))
		{
			current = current.getCause();
		}
		return current == null ? throwable : current;
	}

	private static final class CompetitionContext
	{
		final String kind;
		int eventId;
		String metric = "";
		long startTime;
		long endTime;
		String status = "";
		boolean paused;
		Skill skill;
		long ackedValue;
		long latestValue;
		boolean baselineSet;
		boolean needsFreshBossBaseline = true;
		boolean sending;
		long pendingTarget = -1L;
		long pendingDelta;
		long pendingObservedAt;
		String pendingTelemetryId;

		CompetitionContext(String kind)
		{
			this.kind = kind;
		}

		boolean isScorable(long now)
		{
			return "ACTIVE".equals(status)
				&& !paused
				&& eventId > 0
				&& now >= startTime
				&& now <= endTime;
		}

		void reset()
		{
			eventId = 0;
			metric = "";
			startTime = 0L;
			endTime = 0L;
			status = "";
			paused = false;
			skill = null;
			ackedValue = 0L;
			latestValue = 0L;
			baselineSet = false;
			needsFreshBossBaseline = true;
			sending = false;
			clearPending(this);
		}
	}

	static final class BossCount
	{
		final String boss;
		final long count;

		BossCount(String boss, long count)
		{
			this.boss = boss;
			this.count = count;
		}
	}
}
