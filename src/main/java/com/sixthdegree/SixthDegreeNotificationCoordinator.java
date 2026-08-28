package com.sixthdegree;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
@Singleton
final class SixthDegreeNotificationCoordinator
{
	private static final int MAX_PENDING = 30;
	private static final long RULE_REFRESH_MINUTES = 5L;

	private final SixthDegreeNotificationEngine engine;
	private final SixthDegreeScreenshotService screenshots;
	private final SixthDegreeRarityService rarityService;
	private final Deque<PendingNotification> pending = new ArrayDeque<>();
	private final AtomicBoolean sending = new AtomicBoolean(false);
	private final AtomicBoolean rulesRefreshing = new AtomicBoolean(false);
	private final AtomicBoolean rarityRefreshing = new AtomicBoolean(false);

	private SixthDegreeApiClient apiClient;
	private ScheduledExecutorService scheduler;
	private volatile String sessionToken;
	private volatile boolean active;

	@Inject
	SixthDegreeNotificationCoordinator(
		SixthDegreeNotificationEngine engine,
		SixthDegreeScreenshotService screenshots,
		SixthDegreeRarityService rarityService)
	{
		this.engine = engine;
		this.screenshots = screenshots;
		this.rarityService = rarityService;
	}

	void start(SixthDegreeApiClient apiClient)
	{
		this.apiClient = apiClient;
		if (scheduler == null || scheduler.isShutdown())
		{
			scheduler = Executors.newSingleThreadScheduledExecutor(r ->
			{
				Thread thread = new Thread(r, "sixth-degree-notifications");
				thread.setDaemon(true);
				return thread;
			});
			scheduler.scheduleAtFixedRate(
				this::refreshRules,
				RULE_REFRESH_MINUTES,
				RULE_REFRESH_MINUTES,
				TimeUnit.MINUTES);
			scheduler.scheduleAtFixedRate(this::flush, 10, 10, TimeUnit.SECONDS);
		}
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
		sending.set(false);
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
			engine.reset();
			refreshRules();
			flush();
		}
	}

	void deactivate()
	{
		active = false;
		sessionToken = null;
		engine.setRules(SixthDegreeNotificationRules.DISABLED);
		engine.reset();
	}

	void refreshRules()
	{
		String token = sessionToken;
		if (!active || token == null || token.isBlank() || apiClient == null
			|| !rulesRefreshing.compareAndSet(false, true))
		{
			return;
		}
		apiClient.getNotificationRules(token).whenComplete((response, error) ->
		{
			rulesRefreshing.set(false);
			if (error != null || response == null || !response.ok)
			{
				return;
			}

			SixthDegreeNotificationRules parsed = SixthDegreeNotificationRules.from(response.rules);
			if (parsed.loot.rarityOverride > 0 && !rarityService.isLoaded())
			{
				// Avoid a small startup window where value notifications work but a rare,
				// low-value drop could be missed before its rarity table is ready.
				engine.setRules(SixthDegreeNotificationRules.DISABLED);
				loadRarityData(token, parsed);
				return;
			}
			engine.setRules(parsed);
		});
	}

	private void loadRarityData(String token, SixthDegreeNotificationRules parsed)
	{
		if (!rarityRefreshing.compareAndSet(false, true))
		{
			return;
		}
		apiClient.getNpcDropRarityData(token).whenComplete((json, error) ->
		{
			rarityRefreshing.set(false);
			if (!active || !token.equals(sessionToken))
			{
				return;
			}
			if (error == null && rarityService.load(json))
			{
				log.debug("Sixth Degree NPC rarity data loaded");
			}
			else if (error != null)
			{
				log.warn("Sixth Degree rarity data unavailable; value-based loot notifications remain active", error);
			}
			engine.setRules(parsed);
		});
	}

	@Subscribe(priority = 0)
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!active)
		{
			return;
		}
		String source = event.getNpc() == null ? "NPC" : event.getNpc().getName();
		dispatch(engine.onLoot(event.getItems(), source));
	}

	@Subscribe(priority = 0)
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (!active)
		{
			return;
		}
		String source = event.getComposition() == null ? "NPC" : event.getComposition().getName();
		dispatch(engine.onLoot(event.getItems(), source));
	}

	@Subscribe(priority = 0)
	public void onLootReceived(LootReceived event)
	{
		if (!active)
		{
			return;
		}
		LootRecordType type = event.getType();
		// Normal NPC loot already arrives through NpcLootReceived/ServerNpcLoot.
		// Processing LootReceived(NPC) as well can represent the same kill twice.
		if (type != LootRecordType.EVENT && type != LootRecordType.PICKPOCKET)
		{
			return;
		}
		dispatch(engine.onLoot(event.getItems(), event.getName()));
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!active || event == null)
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
		List<SixthDegreeNotificationEvent> events = engine.onGameMessage(event.getMessage());
		for (SixthDegreeNotificationEvent notification : events)
		{
			dispatch(notification);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (!active)
		{
			return;
		}
		for (SixthDegreeNotificationEvent notification : engine.onStatChanged(event))
		{
			dispatch(notification);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			engine.reset();
		}
	}

	private void dispatch(SixthDegreeNotificationEvent event)
	{
		if (event == null || !active)
		{
			return;
		}
		if (!event.screenshot)
		{
			enqueue(new PendingNotification(event, null));
			return;
		}

		screenshots.capturePng().whenComplete((png, error) ->
		{
			if (error != null)
			{
				log.debug("Sixth Degree screenshot capture failed; posting without image", error);
				enqueue(new PendingNotification(event, null));
			}
			else
			{
				enqueue(new PendingNotification(event, png));
			}
		});
	}

	private void enqueue(PendingNotification notification)
	{
		if (!active)
		{
			return;
		}
		synchronized (pending)
		{
			while (pending.size() >= MAX_PENDING)
			{
				pending.removeFirst();
			}
			pending.addLast(notification);
		}
		flush();
	}

	private void flush()
	{
		String token = sessionToken;
		if (!active || token == null || token.isBlank() || apiClient == null
			|| !sending.compareAndSet(false, true))
		{
			return;
		}

		PendingNotification next;
		synchronized (pending)
		{
			next = pending.peekFirst();
		}
		if (next == null)
		{
			sending.set(false);
			return;
		}

		apiClient.postNotification(token, next.event, next.png).whenComplete((response, error) ->
		{
			boolean remove = error == null && response != null && response.ok;
			boolean retry = false;
			if (error != null)
			{
				Throwable cause = unwrap(error);
				if (cause instanceof SixthDegreeApiClient.ApiException)
				{
					int code = ((SixthDegreeApiClient.ApiException) cause).getStatusCode();
					remove = code == 400 || code == 409 || code == 401 || code == 403;
					if (code == 409)
					{
						refreshRules();
					}
				}
				else
				{
					retry = true;
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
				}
			}
			sending.set(false);

			if (!retry && active && scheduler != null && !scheduler.isShutdown())
			{
				scheduler.schedule(this::flush, 250, TimeUnit.MILLISECONDS);
			}
		});
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

	private static final class PendingNotification
	{
		final SixthDegreeNotificationEvent event;
		final byte[] png;
		int attempts;

		PendingNotification(SixthDegreeNotificationEvent event, byte[] png)
		{
			this.event = event;
			this.png = png;
		}
	}
}
