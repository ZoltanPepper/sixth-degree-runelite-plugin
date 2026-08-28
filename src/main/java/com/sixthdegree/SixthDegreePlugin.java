package com.sixthdegree;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.LinkBrowser;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
@PluginDescriptor(
	name = "Sixth Degree",
	description = "Official Sixth Degree clan companion for events, competitions, LFG and clan notifications"
)
public class SixthDegreePlugin extends Plugin
{
	private static final String CLAN_NAME = "Sixth Degree";
	private static final String CONFIG_GROUP = "sixthdegree";
	private static final int MEMBERSHIP_RECHECK_TICKS = 100;
	private static final long SESSION_RECHECK_MILLIS = TimeUnit.MINUTES.toMillis(10);
	private static final long LOOT_FLUSH_SECONDS = 60L;
	private static final long LEADERBOARD_REFRESH_SECONDS = 30L;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SixthDegreeConfig config;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Notifier notifier;

	@Inject
	private SixthDegreeLootService lootService;

	@Inject
	private EventBus eventBus;

	@Inject
	private SixthDegreeNotificationCoordinator notificationCoordinator;

	@Inject
	private SixthDegreeApiClient apiClient;

	@Inject
	private SixthDegreeRealtimeClient realtimeClient;

	private final AtomicBoolean authPollInFlight = new AtomicBoolean(false);
	private final AtomicBoolean lootSendInFlight = new AtomicBoolean(false);
	private final AtomicBoolean realtimeConnecting = new AtomicBoolean(false);

	private SixthDegreePanel panel;
	private NavigationButton navigationButton;
	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> authPollTask;
	private ScheduledFuture<?> lootFlushTask;
	private ScheduledFuture<?> leaderboardRefreshTask;
	private ScheduledFuture<?> realtimeGuardTask;
	private volatile WebSocket realtimeSocket;
	private int membershipTicks;
	private String validatedRsn;
	private boolean sessionValid;
	private long lastSessionCheckMillis;
	private boolean sessionValidationInFlight;
	private String connectionAnnouncedRsn;
	private volatile String pendingAuthRequestId;
	private volatile String pendingAuthRsn;

	@Override
	protected void startUp()
	{
		scheduler = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread thread = new Thread(r, "sixth-degree-services");
			thread.setDaemon(true);
			return thread;
		});

		panel = new SixthDegreePanel(apiClient);
		navigationButton = NavigationButton.builder()
			.tooltip("Sixth Degree")
			.icon(buildSixthDegreeIcon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);

		notificationCoordinator.start(apiClient);
		eventBus.register(notificationCoordinator);

		lootFlushTask = scheduler.scheduleAtFixedRate(
			this::flushLootTelemetry,
			LOOT_FLUSH_SECONDS,
			LOOT_FLUSH_SECONDS,
			TimeUnit.SECONDS);
		leaderboardRefreshTask = scheduler.scheduleAtFixedRate(
			() -> SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.refreshLootLeaderboardIfVisible();
				}
			}),
			LEADERBOARD_REFRESH_SECONDS,
			LEADERBOARD_REFRESH_SECONDS,
			TimeUnit.SECONDS);
		realtimeGuardTask = scheduler.scheduleAtFixedRate(
			() -> clientThread.invokeLater(this::ensureRealtimeConnected),
			10,
			30,
			TimeUnit.SECONDS);

		refreshAccessState();
		log.debug("Sixth Degree started");
	}

	@Override
	protected void shutDown()
	{
		removeLfgForValidatedAccount();
		lootService.sealBatch();
		flushLootTelemetry();
		disconnectRealtime();
		cancelAuthPolling();
		eventBus.unregister(notificationCoordinator);
		notificationCoordinator.stop();
		if (lootFlushTask != null)
		{
			lootFlushTask.cancel(false);
			lootFlushTask = null;
		}
		if (leaderboardRefreshTask != null)
		{
			leaderboardRefreshTask.cancel(false);
			leaderboardRefreshTask = null;
		}
		if (realtimeGuardTask != null)
		{
			realtimeGuardTask.cancel(false);
			realtimeGuardTask = null;
		}
		if (scheduler != null)
		{
			scheduler.shutdown();
			scheduler = null;
		}
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
		}
		navigationButton = null;
		panel = null;
		validatedRsn = null;
		sessionValid = false;
		membershipTicks = 0;
		connectionAnnouncedRsn = null;
		log.debug("Sixth Degree stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		membershipTicks = 0;
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			removeLfgForValidatedAccount();
			lootService.sealBatch();
			flushLootTelemetry();
			disconnectRealtime();
			notificationCoordinator.deactivate();
			connectionAnnouncedRsn = null;
		}
		refreshAccessState();
	}

	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		if (!event.isGuest())
		{
			refreshAccessState();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		membershipTicks++;
		if (membershipTicks >= MEMBERSHIP_RECHECK_TICKS)
		{
			membershipTicks = 0;
			refreshAccessState();
		}
	}

	@Subscribe(priority = 1)
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (canSendMemberTelemetry())
		{
			lootService.record(event.getItems());
		}
	}

	@Subscribe(priority = 1)
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (canSendMemberTelemetry())
		{
			lootService.record(event.getItems());
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (canSendMemberTelemetry() && event.getType() != LootRecordType.PLAYER)
		{
			lootService.record(event.getItems());
		}
	}

	private boolean canSendMemberTelemetry()
	{
		String rsn = validatedRsn;
		return sessionValid
			&& rsn != null
			&& client.getGameState() == GameState.LOGGED_IN
			&& sameRsn(rsn, currentRsn())
			&& isCurrentAccountInSixthDegree(rsn);
	}

	private void refreshAccessState()
	{
		if (panel == null)
		{
			return;
		}

		if (client.getGameState() != GameState.LOGGED_IN)
		{
			panel.showLoggedOut();
			return;
		}

		String rsn = currentRsn();
		if (rsn == null)
		{
			panel.showLoggedOut();
			return;
		}

		if (!isCurrentAccountInSixthDegree(rsn))
		{
			cancelAuthPolling();
			clearTransientValidation();
			panel.showRecruitment(rsn);
			return;
		}

		if (pendingAuthRequestId != null && sameRsn(rsn, pendingAuthRsn))
		{
			panel.showLinking(rsn);
			return;
		}

		String token = loadSessionToken(rsn);
		if (token == null || token.isBlank())
		{
			clearTransientValidation();
			panel.showDiscordLinkRequired(rsn, () -> clientThread.invokeLater(() -> beginDiscordLink(rsn)));
			return;
		}

		long now = System.currentTimeMillis();
		if (sessionValid && sameRsn(rsn, validatedRsn) && now - lastSessionCheckMillis < SESSION_RECHECK_MILLIS)
		{
			showMemberPanel(rsn, token);
			ensureRealtimeConnected();
			return;
		}

		validateSession(rsn, token);
	}

	private void showMemberPanel(String rsn, String token)
	{
		panel.showMemberHome(
			rsn,
			token,
			client.getWorld(),
			config.notifications(),
			config.notificationSound()
		);
	}

	private void validateSession(String rsn, String token)
	{
		if (sessionValidationInFlight)
		{
			return;
		}
		sessionValidationInFlight = true;
		panel.showCheckingAccess(rsn);

		apiClient.getMemberStatus(token).whenComplete((status, throwable) ->
			clientThread.invokeLater(() -> handleSessionValidation(rsn, status, throwable)));
	}

	private void handleSessionValidation(String rsn, SixthDegreeApiClient.MemberStatus status, Throwable throwable)
	{
		sessionValidationInFlight = false;
		if (!isStillCurrentClanAccount(rsn))
		{
			return;
		}

		if (throwable != null)
		{
			Throwable cause = unwrap(throwable);
			if (cause instanceof SixthDegreeApiClient.ApiException)
			{
				int code = ((SixthDegreeApiClient.ApiException) cause).getStatusCode();
				if (code == 401 || code == 403)
				{
					removeSessionToken(rsn);
					clearTransientValidation();
					panel.showDiscordLinkRequired(
						rsn,
						() -> clientThread.invokeLater(() -> beginDiscordLink(rsn)),
						"Your previous Discord authorisation is no longer valid."
					);
					return;
				}
			}

			panel.showConnectionError(rsn, () -> clientThread.invokeLater(this::refreshAccessState));
			return;
		}

		if (status == null || !status.ok || !"member".equalsIgnoreCase(status.access) || !sameRsn(rsn, status.rsn))
		{
			removeSessionToken(rsn);
			clearTransientValidation();
			panel.showDiscordLinkRequired(rsn, () -> clientThread.invokeLater(() -> beginDiscordLink(rsn)));
			return;
		}

		validatedRsn = rsn;
		sessionValid = true;
		lastSessionCheckMillis = System.currentTimeMillis();
		String token = loadSessionToken(rsn);
		if (token != null && !token.isBlank())
		{
			showMemberPanel(rsn, token);
			announceConnectedOnce(rsn);
			ensureRealtimeConnected();
			notificationCoordinator.activate(token);
			flushLootTelemetry();
		}
	}

	private void beginDiscordLink(String rsn)
	{
		if (!isStillCurrentClanAccount(rsn) || pendingAuthRequestId != null)
		{
			return;
		}

		panel.showCheckingAccess(rsn);
		apiClient.startDiscordAuth(rsn).whenComplete((auth, throwable) ->
			clientThread.invokeLater(() -> handleAuthStart(rsn, auth, throwable)));
	}

	private void handleAuthStart(String rsn, SixthDegreeApiClient.AuthStart auth, Throwable throwable)
	{
		if (!isStillCurrentClanAccount(rsn))
		{
			return;
		}
		if (throwable != null || auth == null || auth.request_id == null || auth.authorize_url == null)
		{
			panel.showConnectionError(rsn, () -> clientThread.invokeLater(() -> beginDiscordLink(rsn)));
			return;
		}

		pendingAuthRequestId = auth.request_id;
		pendingAuthRsn = rsn;
		panel.showLinking(rsn);
		SwingUtilities.invokeLater(() -> LinkBrowser.browse(auth.authorize_url));
		startAuthPolling();
	}

	private void startAuthPolling()
	{
		cancelAuthPollingTaskOnly();
		if (scheduler != null && !scheduler.isShutdown())
		{
			authPollTask = scheduler.scheduleAtFixedRate(this::pollDiscordAuth, 2, 2, TimeUnit.SECONDS);
		}
	}

	private void pollDiscordAuth()
	{
		String requestId = pendingAuthRequestId;
		String rsn = pendingAuthRsn;
		if (requestId == null || rsn == null)
		{
			return;
		}
		if (!authPollInFlight.compareAndSet(false, true))
		{
			return;
		}

		apiClient.getDiscordAuthStatus(requestId).whenComplete((status, throwable) ->
		{
			authPollInFlight.set(false);
			clientThread.invokeLater(() -> handleAuthPollResult(rsn, status, throwable));
		});
	}

	private void handleAuthPollResult(String rsn, SixthDegreeApiClient.AuthStatus status, Throwable throwable)
	{
		if (!isStillCurrentClanAccount(rsn))
		{
			cancelAuthPolling();
			return;
		}
		if (throwable != null)
		{
			Throwable cause = unwrap(throwable);
			if (cause instanceof SixthDegreeApiClient.ApiException
				&& ((SixthDegreeApiClient.ApiException) cause).getStatusCode() == 404)
			{
				finishAuthFailure(rsn, "The Discord link expired. Please try again.");
			}
			return;
		}
		if (status == null || status.status == null)
		{
			return;
		}

		switch (status.status.toLowerCase(Locale.ROOT))
		{
			case "approved":
				if (status.session_token == null || status.session_token.isBlank() || !sameRsn(rsn, status.rsn))
				{
					finishAuthFailure(rsn, "Boss Lady could not complete the account link. Please try again.");
					return;
				}
				saveSessionToken(rsn, status.session_token);
				cancelAuthPolling();
				validatedRsn = rsn;
				sessionValid = true;
				lastSessionCheckMillis = System.currentTimeMillis();
				showMemberPanel(rsn, status.session_token);
				announceConnectedOnce(rsn);
				ensureRealtimeConnected();
				notificationCoordinator.activate(status.session_token);
				break;
			case "denied":
				finishAuthFailure(rsn, status.reason == null ? "Discord access was not approved." : status.reason);
				break;
			case "expired":
				finishAuthFailure(rsn, "The Discord link expired. Please try again.");
				break;
			default:
				break;
		}
	}

	private void announceConnectedOnce(String rsn)
	{
		if (sameRsn(connectionAnnouncedRsn, rsn))
		{
			return;
		}
		connectionAnnouncedRsn = rsn;
		addGameMessage("You are connected to Sixth Degree.");
	}

	private void addGameMessage(String message)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		chatMessageManager.queue(
			QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.name("Sixth Degree")
				.runeLiteFormattedMessage("<col=ffffff>[Sixth Degree]</col> " + message)
				.build());
	}

	private void ensureRealtimeConnected()
	{
		if (!canSendMemberTelemetry() || realtimeSocket != null || realtimeConnecting.get())
		{
			return;
		}
		String rsn = validatedRsn;
		String token = rsn == null ? null : loadSessionToken(rsn);
		if (token == null || token.isBlank() || !realtimeConnecting.compareAndSet(false, true))
		{
			return;
		}

		realtimeClient.connect(
			token,
			event -> clientThread.invokeLater(() -> handleRealtimeEvent(event)),
			() ->
			{
				realtimeSocket = null;
				realtimeConnecting.set(false);
			})
			.whenComplete((socket, error) ->
			{
				realtimeConnecting.set(false);
				if (error != null)
				{
					realtimeSocket = null;
					return;
				}
				realtimeSocket = socket;
			});
	}

	private void handleRealtimeEvent(SixthDegreeRealtimeClient.RealtimeEvent event)
	{
		if (event == null || event.type == null || !canSendMemberTelemetry())
		{
			return;
		}
		if ("lfg_posted".equalsIgnoreCase(event.type) && event.entry != null)
		{
			SixthDegreeApiClient.LfgEntry entry = event.entry;
			if (entry.rsn == null || sameRsn(entry.rsn, validatedRsn))
			{
				return;
			}
			if (!config.notifications())
			{
				return;
			}
			String note = entry.note == null || entry.note.isBlank() ? "a group" : entry.note;
			String line = entry.rsn + " is looking for: " + note + (entry.world > 0 ? " (W" + entry.world + ")" : "");
			addGameMessage(line);
			if (config.notificationSound())
			{
				notifier.notify("Sixth Degree LFG: " + line);
			}
		}
	}

	private void disconnectRealtime()
	{
		WebSocket socket = realtimeSocket;
		realtimeSocket = null;
		realtimeConnecting.set(false);
		if (socket != null)
		{
			try
			{
				socket.sendClose(WebSocket.NORMAL_CLOSURE, "Sixth Degree session ended");
			}
			catch (Exception ignored)
			{
				// Socket is already gone.
			}
		}
	}

	private void flushLootTelemetry()
	{
		lootService.sealBatch();
		if (!sessionValid || validatedRsn == null || !lootSendInFlight.compareAndSet(false, true))
		{
			return;
		}
		SixthDegreeLootService.Batch batch = lootService.peekBatch();
		if (batch == null)
		{
			lootSendInFlight.set(false);
			return;
		}
		String token = loadSessionToken(validatedRsn);
		if (token == null || token.isBlank())
		{
			lootSendInFlight.set(false);
			return;
		}

		apiClient.postLootBatch(token, batch.id, batch.valueGp, batch.dropCount, batch.recordedAt)
			.whenComplete((response, error) ->
			{
				if (error == null && response != null && response.ok)
				{
					lootService.acknowledge(batch.id);
					SwingUtilities.invokeLater(() ->
					{
						if (panel != null)
						{
							panel.refreshLootLeaderboardIfVisible();
						}
					});
				}
				lootSendInFlight.set(false);
				if (error == null && lootService.peekBatch() != null && scheduler != null && !scheduler.isShutdown())
				{
					scheduler.schedule(this::flushLootTelemetry, 1, TimeUnit.SECONDS);
				}
			});
	}

	private void finishAuthFailure(String rsn, String reason)
	{
		cancelAuthPolling();
		if (isStillCurrentClanAccount(rsn))
		{
			panel.showDiscordLinkRequired(
				rsn,
				() -> clientThread.invokeLater(() -> beginDiscordLink(rsn)),
				reason
			);
		}
	}

	private void removeLfgForValidatedAccount()
	{
		String rsn = validatedRsn;
		if (rsn == null || rsn.isBlank())
		{
			return;
		}
		String token = loadSessionToken(rsn);
		if (token == null || token.isBlank())
		{
			return;
		}
		apiClient.deleteMyLfg(token).exceptionally(error -> null);
	}

	private void cancelAuthPolling()
	{
		cancelAuthPollingTaskOnly();
		pendingAuthRequestId = null;
		pendingAuthRsn = null;
		authPollInFlight.set(false);
	}

	private void cancelAuthPollingTaskOnly()
	{
		if (authPollTask != null)
		{
			authPollTask.cancel(false);
			authPollTask = null;
		}
	}

	private void clearTransientValidation()
	{
		disconnectRealtime();
		notificationCoordinator.deactivate();
		validatedRsn = null;
		sessionValid = false;
		lastSessionCheckMillis = 0;
		sessionValidationInFlight = false;
	}

	private String currentRsn()
	{
		Player localPlayer = client.getLocalPlayer();
		String name = localPlayer == null ? null : localPlayer.getName();
		return name == null || name.isBlank() ? null : name;
	}

	private boolean isCurrentAccountInSixthDegree(String rsn)
	{
		ClanSettings clanSettings = client.getClanSettings();
		return clanSettings != null
			&& CLAN_NAME.equalsIgnoreCase(clanSettings.getName())
			&& clanSettings.findMember(rsn) != null;
	}

	private boolean isStillCurrentClanAccount(String rsn)
	{
		return client.getGameState() == GameState.LOGGED_IN
			&& sameRsn(rsn, currentRsn())
			&& isCurrentAccountInSixthDegree(rsn);
	}

	private static boolean sameRsn(String a, String b)
	{
		return a != null && b != null && normaliseRsn(a).equals(normaliseRsn(b));
	}

	private static String normaliseRsn(String rsn)
	{
		return rsn.trim()
			.replace('_', ' ')
			.replaceAll("\\s+", " ")
			.toLowerCase(Locale.ROOT);
	}

	private String loadSessionToken(String rsn)
	{
		return configManager.getConfiguration(CONFIG_GROUP, sessionKey(rsn));
	}

	private void saveSessionToken(String rsn, String token)
	{
		configManager.setConfiguration(CONFIG_GROUP, sessionKey(rsn), token);
	}

	private void removeSessionToken(String rsn)
	{
		configManager.unsetConfiguration(CONFIG_GROUP, sessionKey(rsn));
	}

	private static String sessionKey(String rsn)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(normaliseRsn(rsn).getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder("session_");
			for (int i = 0; i < 12; i++)
			{
				out.append(String.format("%02x", digest[i]));
			}
			return out.toString();
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Unable to create RuneLite session key", e);
		}
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

	private static BufferedImage buildSixthDegreeIcon()
	{
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			graphics.setColor(Color.WHITE);
			graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 29));
			graphics.drawString("6", 3, 29);
			graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
			graphics.drawString("°", 22, 11);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	@Provides
	SixthDegreeConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(SixthDegreeConfig.class);
	}
}
