package com.sixthdegree;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Turns OSRS boss kill-count chat lines into clan points.
 *
 * The first count seen for a boss is a baseline and is never awarded.
 * Later increases, including kills done on mobile between sessions, are
 * sent to Boss Lady. Boss Lady owns the points table.
 */
@Slf4j
@Singleton
final class SixthDegreePointsTracker
{
	private static final String CONFIG_GROUP = "sixthdegree";
	private static final String CONFIG_PREFIX = "kc.";
	private static final long MAX_DELTA = 500L;
	private static final Pattern BOSS_COUNT = Pattern.compile(
		"Your (.+?)\\s(?:kill|chest|completion|harvest|success|opened)\\s?count is: ?([\\d,]+)\\b",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern BOSS_COUNT_SECONDARY = Pattern.compile(
		"Your (?:completed|subdued) (.+?) count is: ([\\d,]+)\\b",
		Pattern.CASE_INSENSITIVE);

	private final Client client;
	private final ChatMessageManager chatMessageManager;
	private final ConfigManager configManager;
	private final SixthDegreeApiClient apiClient;
	private final SixthDegreeConfig config;
	private final SixthDegreeSoundService sounds;
	private final Map<String, Integer> table = new ConcurrentHashMap<>();
	private final AtomicBoolean tableInFlight = new AtomicBoolean(false);
	private final AtomicBoolean sending = new AtomicBoolean(false);

	private volatile String sessionToken;
	private volatile boolean active;
	private Pending pending;

	@Inject
	SixthDegreePointsTracker(
		Client client,
		ChatMessageManager chatMessageManager,
		ConfigManager configManager,
		SixthDegreeApiClient apiClient,
		SixthDegreeConfig config,
		SixthDegreeSoundService sounds)
	{
		this.client = client;
		this.chatMessageManager = chatMessageManager;
		this.configManager = configManager;
		this.apiClient = apiClient;
		this.config = config;
		this.sounds = sounds;
	}

	void activate(String token)
	{
		if (token == null || token.isBlank())
		{
			return;
		}
		sessionToken = token;
		active = true;
		refreshTable();
	}

	void deactivate()
	{
		active = false;
		sessionToken = null;
		pending = null;
		table.clear();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!active || event == null)
		{
			return;
		}
		BossCount count = parse(event.getMessage());
		if (count == null)
		{
			return;
		}
		String boss = matchBoss(count.boss);
		if (boss == null)
		{
			return;
		}

		long previous = stored(boss);
		if (previous < 0L)
		{
			store(boss, count.count);
			return;
		}
		if (count.count <= previous)
		{
			return;
		}

		long delta = count.count - previous;
		if (delta > MAX_DELTA)
		{
			// A jump this large is a name change or a bad parse, not a session.
			store(boss, count.count);
			return;
		}

		queue(boss, (int) delta, count.count);
	}

	private void refreshTable()
	{
		String token = sessionToken;
		if (!active || token == null || !tableInFlight.compareAndSet(false, true))
		{
			return;
		}
		apiClient.getPointsTable(token).whenComplete((response, error) ->
		{
			tableInFlight.set(false);
			if (!active || error != null || response == null || !response.ok || response.bosses == null)
			{
				return;
			}
			table.clear();
			for (SixthDegreeApiClient.BossPointRow row : response.bosses)
			{
				if (row != null && row.boss != null && row.points > 0)
				{
					table.put(normalise(row.boss), row.points);
				}
			}
		});
	}

	private void queue(String boss, int delta, long absolute)
	{
		if (sending.get())
		{
			return;
		}
		String token = sessionToken;
		if (token == null || token.isBlank())
		{
			return;
		}
		String telemetryId = telemetryId(boss, absolute);
		pending = new Pending(boss, delta, absolute, telemetryId);
		sending.set(true);
		apiClient.postKcProgress(token, telemetryId, boss, delta).whenComplete((response, error) ->
			handleResult(response, error));
	}

	private void handleResult(SixthDegreeApiClient.KcProgressResponse response, Throwable error)
	{
		Pending sent = pending;
		sending.set(false);
		if (sent == null)
		{
			return;
		}
		if (error != null || response == null || !response.ok)
		{
			return;
		}
		store(sent.boss, sent.absolute);
		pending = null;
		if (!response.accepted || response.earned < 1)
		{
			return;
		}
		String line = response.earned == 1
			? "You earned 1 point from " + sent.boss + ". You now have " + response.total + " points."
			: "You earned " + response.earned + " points from " + sent.boss + ". You now have " + response.total + " points.";
		addGameMessage(line);
		if (config.notificationSound() && sounds != null)
		{
			sounds.play(SixthDegreeSoundService.Cue.LOGIN);
		}
	}

	private String matchBoss(String observed)
	{
		String wanted = normalise(observed);
		if (wanted.isBlank() || table.isEmpty())
		{
			return null;
		}
		for (String boss : table.keySet())
		{
			if (boss.equals(wanted) || (wanted.length() >= 5 && boss.length() >= 5 && (boss.contains(wanted) || wanted.contains(boss))))
			{
				return displayName(boss);
			}
		}
		return null;
	}

	private String displayName(String normalised)
	{
		for (String boss : table.keySet())
		{
			if (boss.equals(normalised))
			{
				return capitalise(boss);
			}
		}
		return capitalise(normalised);
	}

	private long stored(String boss)
	{
		String value = configManager.getConfiguration(CONFIG_GROUP, CONFIG_PREFIX + key(boss));
		if (value == null || value.isBlank())
		{
			return -1L;
		}
		try
		{
			return Long.parseLong(value);
		}
		catch (NumberFormatException ignored)
		{
			return -1L;
		}
	}

	private void store(String boss, long count)
	{
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_PREFIX + key(boss), Long.toString(Math.max(0L, count)));
	}

	private String telemetryId(String boss, long absolute)
	{
		return "kc:" + accountDiscriminator() + ":" + key(boss) + ":" + absolute;
	}

	private String accountDiscriminator()
	{
		Player player = client.getLocalPlayer();
		String name = player == null ? "unknown" : Text.removeTags(player.getName());
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

	private void addGameMessage(String message)
	{
		chatMessageManager.queue(
			QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.name("Sixth Degree")
				.runeLiteFormattedMessage("<col=ff69b4>[Sixth Degree] " + Text.escapeJagex(message) + "</col>")
				.build());
	}

	private static BossCount parse(String raw)
	{
		if (raw == null || raw.isBlank())
		{
			return null;
		}
		String message = Text.removeTags(raw).trim();
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

	private static String key(String boss)
	{
		return normalise(boss).replace(' ', '-');
	}

	private static String normalise(String value)
	{
		String clean = Text.removeTags(value == null ? "" : value).toLowerCase(Locale.ROOT)
			.replace('&', ' ')
			.replaceAll("[^a-z0-9]+", " ")
			.trim()
			.replaceAll("\\s+", " ");
		return clean.startsWith("the ") ? clean.substring(4) : clean;
	}

	private static String capitalise(String value)
	{
		String[] parts = value.split(" ");
		StringBuilder out = new StringBuilder();
		for (String part : parts)
		{
			if (part.isBlank())
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append(' ');
			}
			out.append(Character.toUpperCase(part.charAt(0)));
			if (part.length() > 1)
			{
				out.append(part.substring(1));
			}
		}
		return out.toString();
	}

	private static final class BossCount
	{
		private final String boss;
		private final long count;

		private BossCount(String boss, long count)
		{
			this.boss = boss;
			this.count = count;
		}
	}

	private static final class Pending
	{
		private final String boss;
		private final int delta;
		private final long absolute;
		private final String telemetryId;

		private Pending(String boss, int delta, long absolute, String telemetryId)
		{
			this.boss = boss;
			this.delta = delta;
			this.absolute = absolute;
			this.telemetryId = telemetryId;
		}
	}
}
