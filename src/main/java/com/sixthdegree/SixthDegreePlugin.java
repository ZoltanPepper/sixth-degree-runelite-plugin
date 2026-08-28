package com.sixthdegree;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.LinkBrowser;

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

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	private final SixthDegreeApiClient apiClient = new SixthDegreeApiClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r ->
	{
		Thread thread = new Thread(r, "sixth-degree-auth");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean authPollInFlight = new AtomicBoolean(false);

	private SixthDegreePanel panel;
	private NavigationButton navigationButton;
	private ScheduledFuture<?> authPollTask;
	private int membershipTicks;
	private ViewState displayedState;
	private String displayedRsn;
	private String validatedRsn;
	private boolean sessionValid;
	private long lastSessionCheckMillis;
	private boolean sessionValidationInFlight;
	private String pendingAuthRequestId;
	private String pendingAuthRsn;

	private enum ViewState
	{
		LOGGED_OUT,
		RECRUITMENT
	}

	@Override
	protected void startUp()
	{
		panel = new SixthDegreePanel();
		navigationButton = NavigationButton.builder()
			.tooltip("Sixth Degree")
			.icon(buildPlaceholderIcon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		refreshAccessState();
		log.debug("Sixth Degree started");
	}

	@Override
	protected void shutDown()
	{
		cancelAuthPolling();
		scheduler.shutdownNow();
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
		}
		navigationButton = null;
		panel = null;
		displayedState = null;
		displayedRsn = null;
		validatedRsn = null;
		sessionValid = false;
		membershipTicks = 0;
		log.debug("Sixth Degree stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		membershipTicks = 0;
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

	private void refreshAccessState()
	{
		if (panel == null)
		{
			return;
		}

		if (client.getGameState() != GameState.LOGGED_IN)
		{
			showBasicState(ViewState.LOGGED_OUT, null);
			return;
		}

		String rsn = currentRsn();
		if (rsn == null)
		{
			showBasicState(ViewState.LOGGED_OUT, null);
			return;
		}

		if (!isCurrentAccountInSixthDegree(rsn))
		{
			cancelAuthPolling();
			clearTransientValidation();
			showBasicState(ViewState.RECRUITMENT, rsn);
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
			panel.showDiscordLinkRequired(rsn, () -> beginDiscordLink(rsn));
			return;
		}

		long now = System.currentTimeMillis();
		if (sessionValid && sameRsn(rsn, validatedRsn) && now - lastSessionCheckMillis < SESSION_RECHECK_MILLIS)
		{
			panel.showMemberHome(rsn);
			return;
		}

		validateSession(rsn, token);
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
							() -> beginDiscordLink(rsn),
							"Your previous Discord authorisation is no longer valid."
						);
						return;
					}
				}

				panel.showConnectionError(rsn, this::refreshAccessState);
				return;
			}

			if (status == null || !status.ok || !"member".equalsIgnoreCase(status.access) || !sameRsn(rsn, status.rsn))
			{
				removeSessionToken(rsn);
				clearTransientValidation();
				panel.showDiscordLinkRequired(rsn, () -> beginDiscordLink(rsn));
				return;
			}

			validatedRsn = rsn;
			sessionValid = true;
			lastSessionCheckMillis = System.currentTimeMillis();
			panel.showMemberHome(rsn);
		});
	}

	private void beginDiscordLink(String rsn)
	{
		if (!isStillCurrentClanAccount(rsn) || pendingAuthRequestId != null)
		{
			return;
		}

		panel.showCheckingAccess(rsn);
		apiClient.startDiscordAuth(rsn).whenComplete((auth, throwable) ->
		{
			if (!isStillCurrentClanAccount(rsn))
			{
				return;
			}
			if (throwable != null || auth == null || auth.request_id == null || auth.authorize_url == null)
			{
				panel.showConnectionError(rsn, () -> beginDiscordLink(rsn));
				return;
			}

			pendingAuthRequestId = auth.request_id;
			pendingAuthRsn = rsn;
			panel.showLinking(rsn);
			SwingUtilities.invokeLater(() -> LinkBrowser.browse(auth.authorize_url));
			startAuthPolling();
		});
	}

	private void startAuthPolling()
	{
		cancelAuthPollingTaskOnly();
		authPollTask = scheduler.scheduleAtFixedRate(this::pollDiscordAuth, 2, 2, TimeUnit.SECONDS);
	}

	private void pollDiscordAuth()
	{
		String requestId = pendingAuthRequestId;
		String rsn = pendingAuthRsn;
		if (requestId == null || rsn == null || !isStillCurrentClanAccount(rsn))
		{
			cancelAuthPolling();
			return;
		}
		if (!authPollInFlight.compareAndSet(false, true))
		{
			return;
		}

		apiClient.getDiscordAuthStatus(requestId).whenComplete((status, throwable) ->
		{
			authPollInFlight.set(false);
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
					if (isStillCurrentClanAccount(rsn))
					{
						panel.showMemberHome(rsn);
					}
					break;
				case "denied":
					finishAuthFailure(rsn, status.reason == null ? "Discord access was not approved." : status.reason);
					break;
				case "expired":
					finishAuthFailure(rsn, "The Discord link expired. Please try again.");
					break;
				default:
					// pending
					break;
			}
		});
	}

	private void finishAuthFailure(String rsn, String reason)
	{
		cancelAuthPolling();
		if (isStillCurrentClanAccount(rsn))
		{
			panel.showDiscordLinkRequired(rsn, () -> beginDiscordLink(rsn), reason);
		}
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

	private void showBasicState(ViewState state, String rsn)
	{
		if (state == displayedState && Objects.equals(rsn, displayedRsn))
		{
			return;
		}
		displayedState = state;
		displayedRsn = rsn;
		if (state == ViewState.RECRUITMENT)
		{
			panel.showRecruitment(rsn);
		}
		else
		{
			panel.showLoggedOut();
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

	private static BufferedImage buildPlaceholderIcon()
	{
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(new Color(105, 72, 190));
			graphics.fillOval(1, 1, 30, 30);
			graphics.setColor(Color.WHITE);
			graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
			graphics.drawString("6", 10, 22);
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
