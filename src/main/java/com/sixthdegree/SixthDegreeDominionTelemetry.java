package com.sixthdegree;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
@Singleton
final class SixthDegreeDominionTelemetry
{
	private static final int MAX_PENDING = 500;
	private static final long STATE_REFRESH_SECONDS = 60L;
	private static final long XP_FLUSH_SECONDS = 2L;
	private static final String COLLECTION_LOG_PREFIX = "New item added to your collection log:";
	private static final Pattern BOSS_COUNT = Pattern.compile(
		"Your (.+?)\\s(?:kill|chest|completion|harvest|success|opened)\\s?count is: ?([\\d,]+)\\b",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern BOSS_COUNT_SECONDARY = Pattern.compile(
		"Your (?:completed|subdued) (.+?) count is: ([\\d,]+)\\b",
		Pattern.CASE_INSENSITIVE);

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final SixthDegreeDominionApiClient apiClient;
	private final SixthDegreeDominionHud dominionHud;
	private final Map<Skill, Integer> xpBaselines = new EnumMap<>(Skill.class);
	private final Map<String, PendingXp> pendingXp = new HashMap<>();
	private final Deque<QueuedTelemetry> pending = new ArrayDeque<>();
	private final AtomicBoolean sending = new AtomicBoolean(false);
	private final AtomicBoolean refreshingState = new AtomicBoolean(false);
	private final List<String> lootSignaturesThisTick = new ArrayList<>();

	private ScheduledExecutorService scheduler;
	private volatile String sessionToken;
	private volatile boolean memberActive;
	private volatile boolean dominionActive;
	private int lootSignatureTick = -1;

	@Inject
	SixthDegreeDominionTelemetry(
		Client client,
		ClientThread clientThread,
		ItemManager itemManager,
		SixthDegreeDominionApiClient apiClient,
		SixthDegreeDominionHud dominionHud)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.apiClient = apiClient;
		this.dominionHud = dominionHud;
	}

	void start()
	{
		if (scheduler != null && !scheduler.isShutdown())
		{
			return;
		}
		scheduler = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread thread = new Thread(r, "sixth-degree-dominion-telemetry");
			thread.setDaemon(true);
			return thread;
		});
		scheduler.scheduleAtFixedRate(this::refreshState, 5, STATE_REFRESH_SECONDS, TimeUnit.SECONDS);
		scheduler.scheduleAtFixedRate(this::flushXp, XP_FLUSH_SECONDS, XP_FLUSH_SECONDS, TimeUnit.SECONDS);
		scheduler.scheduleAtFixedRate(this::flushQueue, 1, 1, TimeUnit.SECONDS);
	}

	void stop()
	{
		deactivate();
		if (scheduler != null)
		{
			scheduler.shutdownNow();
			scheduler = null;
		}
		synchronized (pending)
		{
			pending.clear();
		}
		synchronized (pendingXp)
		{
			pendingXp.clear();
		}
		sending.set(false);
		refreshingState.set(false);
	}

	void activate(String token)
	{
		if (token == null || token.isBlank())
		{
			return;
		}
		boolean changed = !memberActive || !token.equals(sessionToken);
		sessionToken = token;
		memberActive = true;
		if (changed)
		{
			dominionActive = false;
			seedXpBaselines();
		}
		refreshState();
	}

	void deactivate()
	{
		flushXp();
		memberActive = false;
		dominionActive = false;
		sessionToken = null;
		xpBaselines.clear();
		lootSignaturesThisTick.clear();
		lootSignatureTick = -1;
		synchronized (pendingXp)
		{
			pendingXp.clear();
		}
		synchronized (pending)
		{
			pending.clear();
		}
	}

	boolean isDominionActive()
	{
		return memberActive && dominionActive;
	}

	void onStatChanged(StatChanged event)
	{
		if (!memberActive || event == null)
		{
			return;
		}
		Skill skill = event.getSkill();
		int currentXp = event.getXp();
		Integer previousXp = xpBaselines.put(skill, currentXp);
		if (previousXp == null || currentXp <= previousXp || !dominionActive)
		{
			return;
		}
		LocationSnapshot location = captureLocation();
		if (location == null)
		{
			return;
		}
		int delta = currentXp - previousXp;
		long observedAt = now();
		String key = skill.name() + "|" + location.key();
		synchronized (pendingXp)
		{
			PendingXp bucket = pendingXp.get(key);
			if (bucket == null)
			{
				bucket = new PendingXp(skill.getName(), location, delta, observedAt, telemetryId("xp"));
				pendingXp.put(key, bucket);
			}
			else
			{
				bucket.delta += delta;
				bucket.observedAt = observedAt;
			}
		}
	}

	void onNpcLootReceived(NpcLootReceived event)
	{
		if (!isDominionActive() || event == null || event.getNpc() == null)
		{
			return;
		}
		String source = event.getNpc().getName();
		int sourceId = event.getNpc().getId();
		if (duplicateLootThisTick(source, event.getItems()))
		{
			return;
		}
		enqueueRaw("NPC_LOOT", source, sourceId, event.getItems(), null);
	}

	void onServerNpcLoot(ServerNpcLoot event)
	{
		if (!isDominionActive() || event == null || event.getComposition() == null)
		{
			return;
		}
		String source = event.getComposition().getName();
		int sourceId = event.getComposition().getId();
		if (duplicateLootThisTick(source, event.getItems()))
		{
			return;
		}
		enqueueRaw("NPC_LOOT", source, sourceId, event.getItems(), null);
	}

	void onLootReceived(LootReceived event)
	{
		if (!isDominionActive() || event == null)
		{
			return;
		}
		LootRecordType type = event.getType();
		if (type == LootRecordType.PLAYER || type == LootRecordType.NPC)
		{
			return;
		}
		String source = event.getName();
		String clueTier = clueTier(source);
		JsonObject extra = new JsonObject();
		extra.addProperty("loot_record_type", type == null ? "" : type.name());
		extra.addProperty("amount", event.getAmount());
		if (clueTier != null)
		{
			extra.addProperty("clue_tier", clueTier);
			enqueueRaw("CLUE", source, 0, event.getItems(), extra);
			return;
		}
		if (duplicateLootThisTick(source, event.getItems()))
		{
			return;
		}
		enqueueRaw("EVENT_LOOT", source, 0, event.getItems(), extra);
	}

	void onChatMessage(ChatMessage event)
	{
		if (!isDominionActive() || event == null)
		{
			return;
		}
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE
			&& type != ChatMessageType.SPAM
			&& type != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage()).trim();
		if (message.isEmpty())
		{
			return;
		}

		if (message.startsWith(COLLECTION_LOG_PREFIX))
		{
			String item = message.substring(COLLECTION_LOG_PREFIX.length()).trim();
			JsonObject extra = new JsonObject();
			extra.addProperty("item_name", item);
			enqueueRaw("COLLECTION_LOG", "Collection Log", 0, null, extra);
			return;
		}

		String lower = message.toLowerCase(Locale.ROOT);
		if (isPetMessage(lower))
		{
			JsonObject extra = new JsonObject();
			extra.addProperty("item_name", "Pet");
			enqueueRaw("PET", "Pet drop", 0, null, extra);
			return;
		}

		BossCount count = parseBossCount(message);
		if (count != null)
		{
			JsonObject extra = new JsonObject();
			extra.addProperty("count", count.count);
			enqueueRaw("BOSS_COUNT", count.source, 0, null, extra);
		}
	}

	void onActorDeath(ActorDeath event)
	{
		if (!isDominionActive() || event == null || event.getActor() != client.getLocalPlayer())
		{
			return;
		}
		enqueueRaw("DEATH", "Player death", 0, null, null);
	}

	void onGameStateChanged(GameStateChanged event)
	{
		if (event == null)
		{
			return;
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			dominionActive = false;
			xpBaselines.clear();
			synchronized (pendingXp)
			{
				pendingXp.clear();
			}
		}
		else if (event.getGameState() == GameState.LOGGED_IN && memberActive)
		{
			seedXpBaselines();
			refreshState();
		}
	}

	private void refreshState()
	{
		String token = sessionToken;
		if (!memberActive || token == null || token.isBlank() || !refreshingState.compareAndSet(false, true))
		{
			return;
		}
		apiClient.getState(token).whenComplete((state, error) ->
		{
			refreshingState.set(false);
			if (!memberActive || !token.equals(sessionToken))
			{
				return;
			}
			if (error != null)
			{
				Throwable cause = unwrap(error);
				if (cause instanceof SixthDegreeApiClient.ApiException)
				{
					int code = ((SixthDegreeApiClient.ApiException) cause).getStatusCode();
					if (code == 409)
					{
						dominionActive = false;
					}
				}
				return;
			}
			boolean wasActive = dominionActive;
			dominionActive = state != null && state.ok && state.active;
			if (!wasActive && dominionActive)
			{
				clientThread.invokeLater(this::seedXpBaselines);
				log.debug("Sixth Degree Dominion telemetry activated");
			}
		});
	}

	private void seedXpBaselines()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		for (Skill skill : Skill.values())
		{
			try
			{
				xpBaselines.put(skill, client.getSkillExperience(skill));
			}
			catch (Exception ignored)
			{
				// Some synthetic/aggregate skills may not expose XP.
			}
		}
	}

	private void flushXp()
	{
		if (!isDominionActive())
		{
			return;
		}
		List<PendingXp> batches;
		synchronized (pendingXp)
		{
			if (pendingXp.isEmpty())
			{
				return;
			}
			batches = new ArrayList<>(pendingXp.values());
			pendingXp.clear();
		}
		for (PendingXp xp : batches)
		{
			JsonObject payload = new JsonObject();
			payload.addProperty("telemetry_id", xp.telemetryId);
			payload.addProperty("skill", xp.skill);
			payload.addProperty("xp_delta", xp.delta);
			payload.addProperty("observed_at", xp.observedAt);
			xp.location.addTo(payload);
			JsonObject metadata = new JsonObject();
			metadata.addProperty("world", clientWorldSafe());
			payload.add("metadata", metadata);
			enqueue(new QueuedTelemetry(true, payload, xp.skill + " XP"));
		}
	}

	private void enqueueRaw(
		String kind,
		String source,
		int sourceId,
		java.util.Collection<ItemStack> items,
		JsonObject extra)
	{
		LocationSnapshot location = captureLocation();
		if (location == null)
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("telemetry_id", telemetryId("raw"));
		payload.addProperty("kind", kind);
		payload.addProperty("source_name", source == null ? "" : source);
		payload.addProperty("source_id", sourceId);
		payload.addProperty("observed_at", now());
		location.addTo(payload);
		if (items != null && !items.isEmpty())
		{
			JsonArray array = new JsonArray();
			for (ItemStack item : items)
			{
				if (item == null || item.getId() < 0 || item.getQuantity() <= 0)
				{
					continue;
				}
				JsonObject out = new JsonObject();
				out.addProperty("id", item.getId());
				out.addProperty("quantity", item.getQuantity());
				out.addProperty("name", itemName(item.getId()));
				array.add(out);
			}
			payload.add("items", array);
		}
		if (extra != null)
		{
			for (Map.Entry<String, com.google.gson.JsonElement> entry : extra.entrySet())
			{
				payload.add(entry.getKey(), entry.getValue());
			}
		}
		JsonObject metadata = new JsonObject();
		metadata.addProperty("tick", client.getTickCount());
		metadata.addProperty("world", clientWorldSafe());
		payload.add("metadata", metadata);
		enqueue(new QueuedTelemetry(false, payload, telemetryLabel(kind, source, extra)));
	}

	private static String telemetryLabel(String kind, String source, JsonObject extra)
	{
		String type = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
		if ("COLLECTION_LOG".equals(type) && extra != null && extra.has("item_name"))
		{
			try { return "Collection Log: " + extra.get("item_name").getAsString(); }
			catch (Exception ignored) { return "Collection Log"; }
		}
		if ("PET".equals(type)) return "Pet drop";
		if ("CLUE".equals(type)) return source == null || source.isBlank() ? "Clue completion" : source;
		if ("BOSS_COUNT".equals(type)) return source == null || source.isBlank() ? "Boss completion" : source;
		if ("DEATH".equals(type)) return "Player death";
		return source == null || source.isBlank() ? "Dominion activity" : source;
	}

	private void enqueue(QueuedTelemetry telemetry)
	{
		if (!isDominionActive())
		{
			return;
		}
		synchronized (pending)
		{
			while (pending.size() >= MAX_PENDING)
			{
				pending.removeFirst();
			}
			pending.addLast(telemetry);
		}
		flushQueue();
	}

	private void flushQueue()
	{
		String token = sessionToken;
		if (!isDominionActive() || token == null || token.isBlank() || !sending.compareAndSet(false, true))
		{
			return;
		}
		QueuedTelemetry next;
		synchronized (pending)
		{
			next = pending.peekFirst();
		}
		if (next == null)
		{
			sending.set(false);
			return;
		}
		CompletableFuture<SixthDegreeDominionApiClient.TelemetryResponse> future = next.xp
			? apiClient.postXp(token, next.payload)
			: apiClient.postRaw(token, next.payload);
		future.whenComplete((response, error) ->
		{
			boolean remove = error == null;
			boolean retry = false;
			if (error == null && response != null)
			{
				dominionHud.onTelemetryResult(next.label, response);
			}
			if (error != null)
			{
				Throwable cause = unwrap(error);
				if (cause instanceof SixthDegreeApiClient.ApiException)
				{
					int code = ((SixthDegreeApiClient.ApiException) cause).getStatusCode();
					remove = code == 400 || code == 401 || code == 403 || code == 409;
					if (code == 409)
					{
						dominionActive = false;
					}
				}
				if (!remove)
				{
					next.attempts++;
					retry = next.attempts < 5;
					remove = !retry;
				}
			}
			if (remove)
			{
				synchronized (pending)
				{
					if (pending.peekFirst() == next)
					{
						pending.removeFirst();
					}
					if (!dominionActive)
					{
						pending.clear();
					}
				}
			}
			sending.set(false);
			if (!retry && isDominionActive() && scheduler != null && !scheduler.isShutdown())
			{
				scheduler.schedule(this::flushQueue, 100, TimeUnit.MILLISECONDS);
			}
		});
	}

	private LocationSnapshot captureLocation()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		WorldPoint world = player.getWorldLocation();
		if (world == null)
		{
			return null;
		}
		boolean instance = client.isInInstancedRegion();
		WorldPoint template = world;
		if (instance)
		{
			try
			{
				LocalPoint local = player.getLocalLocation();
				WorldPoint resolved = local == null ? null : WorldPoint.fromLocalInstance(client, local);
				if (resolved != null)
				{
					template = resolved;
				}
			}
			catch (Exception ignored)
			{
				// Keep current world point; unknown instances fail closed on Boss Lady.
			}
		}
		return new LocationSnapshot(world, template, instance);
	}

	private boolean duplicateLootThisTick(String source, java.util.Collection<ItemStack> items)
	{
		int tick = client.getTickCount();
		if (tick != lootSignatureTick)
		{
			lootSignatureTick = tick;
			lootSignaturesThisTick.clear();
		}
		String itemSignature = items == null ? "" : items.stream()
			.filter(item -> item != null)
			.map(item -> item.getId() + "x" + item.getQuantity())
			.sorted()
			.collect(Collectors.joining(","));
		String signature = normalise(source) + "|" + itemSignature;
		if (lootSignaturesThisTick.contains(signature))
		{
			return true;
		}
		lootSignaturesThisTick.add(signature);
		return false;
	}

	private String itemName(int itemId)
	{
		try
		{
			ItemComposition composition = itemManager.getItemComposition(itemId);
			String name = composition == null ? null : composition.getName();
			return name == null || name.isBlank() ? "Item " + itemId : name;
		}
		catch (Exception ignored)
		{
			return "Item " + itemId;
		}
	}

	private int clientWorldSafe()
	{
		try
		{
			return client.getWorld();
		}
		catch (Exception ignored)
		{
			return 0;
		}
	}

	static BossCount parseBossCount(String message)
	{
		if (message == null)
		{
			return null;
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
			String source = matcher.group(1).trim();
			int count = Integer.parseInt(matcher.group(2).replace(",", ""));
			return source.isBlank() ? null : new BossCount(source, count);
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	static String clueTier(String source)
	{
		String normal = normalise(source);
		if (!(normal.contains("clue") || normal.contains("casket") || normal.contains("reward casket")))
		{
			return null;
		}
		for (String tier : new String[]{"beginner", "easy", "medium", "hard", "elite", "master"})
		{
			if (normal.contains(tier))
			{
				return tier;
			}
		}
		return "unknown";
	}

	static boolean isPetMessage(String lower)
	{
		if (lower == null)
		{
			return false;
		}
		return (lower.contains("funny feeling") && lower.contains("followed"))
			|| lower.contains("weird sneaking into your backpack");
	}

	static String normalise(String value)
	{
		return value == null ? "" : value.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", " ")
			.trim()
			.replaceAll("\\s+", " ");
	}

	private static String telemetryId(String kind)
	{
		return "dom-" + kind + "-" + UUID.randomUUID();
	}

	private static long now()
	{
		return System.currentTimeMillis() / 1000L;
	}

	private static Throwable unwrap(Throwable throwable)
	{
		Throwable current = throwable;
		while (current instanceof CompletionException && current.getCause() != null)
		{
			current = current.getCause();
		}
		return current;
	}

	static final class BossCount
	{
		final String source;
		final int count;

		BossCount(String source, int count)
		{
			this.source = source;
			this.count = count;
		}
	}

	private static final class PendingXp
	{
		final String skill;
		final LocationSnapshot location;
		final String telemetryId;
		int delta;
		long observedAt;

		PendingXp(String skill, LocationSnapshot location, int delta, long observedAt, String telemetryId)
		{
			this.skill = skill;
			this.location = location;
			this.delta = delta;
			this.observedAt = observedAt;
			this.telemetryId = telemetryId;
		}
	}

	private static final class QueuedTelemetry
	{
		final boolean xp;
		final JsonObject payload;
		final String label;
		int attempts;

		QueuedTelemetry(boolean xp, JsonObject payload, String label)
		{
			this.xp = xp;
			this.payload = payload;
			this.label = label;
		}
	}

	private static final class LocationSnapshot
	{
		final int x;
		final int y;
		final int plane;
		final int mapRegion;
		final boolean instance;
		final int templateX;
		final int templateY;
		final int templatePlane;
		final int templateRegion;

		LocationSnapshot(WorldPoint world, WorldPoint template, boolean instance)
		{
			this.x = world.getX();
			this.y = world.getY();
			this.plane = world.getPlane();
			this.mapRegion = world.getRegionID();
			this.instance = instance;
			this.templateX = template.getX();
			this.templateY = template.getY();
			this.templatePlane = template.getPlane();
			this.templateRegion = template.getRegionID();
		}

		String key()
		{
			return x + ":" + y + ":" + plane + ":" + templateX + ":" + templateY + ":" + templatePlane;
		}

		void addTo(JsonObject payload)
		{
			payload.addProperty("x", x);
			payload.addProperty("y", y);
			payload.addProperty("plane", plane);
			payload.addProperty("map_region_id", mapRegion);
			payload.addProperty("in_instance", instance);
			payload.addProperty("template_x", templateX);
			payload.addProperty("template_y", templateY);
			payload.addProperty("template_plane", templatePlane);
			payload.addProperty("template_region_id", templateRegion);
		}
	}
}