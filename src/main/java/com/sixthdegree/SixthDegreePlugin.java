package com.sixthdegree;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
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

@Slf4j
@PluginDescriptor(
	name = "Sixth Degree",
	description = "Official Sixth Degree clan companion for events, competitions, LFG and clan notifications"
)
public class SixthDegreePlugin extends Plugin
{
	private static final String CLAN_NAME = "Sixth Degree";
	private static final int MEMBERSHIP_RECHECK_TICKS = 100;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	private SixthDegreePanel panel;
	private NavigationButton navigationButton;
	private int membershipTicks;
	private ViewState displayedState;
	private String displayedRsn;

	private enum ViewState
	{
		LOGGED_OUT,
		RECRUITMENT,
		DISCORD_LINK_REQUIRED
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
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
		}
		navigationButton = null;
		panel = null;
		displayedState = null;
		displayedRsn = null;
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
			showState(ViewState.LOGGED_OUT, null);
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		String rsn = localPlayer == null ? null : localPlayer.getName();
		if (rsn == null || rsn.isBlank())
		{
			showState(ViewState.LOGGED_OUT, null);
			return;
		}

		ClanSettings clanSettings = client.getClanSettings();
		boolean isSixthDegreeMember = clanSettings != null
			&& CLAN_NAME.equalsIgnoreCase(clanSettings.getName())
			&& clanSettings.findMember(rsn) != null;

		showState(
			isSixthDegreeMember ? ViewState.DISCORD_LINK_REQUIRED : ViewState.RECRUITMENT,
			rsn
		);
	}

	private void showState(ViewState state, String rsn)
	{
		if (state == displayedState && java.util.Objects.equals(rsn, displayedRsn))
		{
			return;
		}

		displayedState = state;
		displayedRsn = rsn;

		switch (state)
		{
			case RECRUITMENT:
				panel.showRecruitment(rsn);
				break;
			case DISCORD_LINK_REQUIRED:
				panel.showDiscordLinkRequired(rsn);
				break;
			case LOGGED_OUT:
			default:
				panel.showLoggedOut();
				break;
		}
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
	SixthDegreeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SixthDegreeConfig.class);
	}
}
