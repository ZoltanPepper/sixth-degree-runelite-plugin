package com.sixthdegree;

import com.google.gson.JsonObject;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

public class SixthDegreePanel extends PluginPanel
{
	private static final String DISCORD_INVITE = "https://discord.gg/6degree";
	// Leave a little breathing room for RuneLite's scrollbar and panel insets.
	private static final int CARD_WIDTH = 184;
	private static final int INNER_WIDTH = 156;
	private static final Color WHITE = new Color(242, 242, 242);
	private static final Color MUTED = new Color(178, 178, 178);
	private static final Color CARD = new Color(45, 45, 45);
	private static final Color BORDER = new Color(76, 76, 76);
	private static final Color SELECTED = new Color(232, 232, 232);
	private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("EEE d MMM • HH:mm");
	private static final NumberFormat NUMBER = NumberFormat.getIntegerInstance(Locale.UK);
	private static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font BODY_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12);
	private static final Font HEADING = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	private static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 16);

	private enum PrimaryPage { HOME, EVENTS, LFG }
	private enum HomePage { LEADERBOARD, SETTINGS }
	private enum EventPage { EVENT_HOME, BOTW, SOTW, DOMINION }
	private enum LootPeriod { DAILY, WEEKLY, MONTHLY }

	private final SixthDegreeApiClient apiClient;
	private final SixthDegreeDominionApiClient dominionApiClient;
	private final Runnable dominionWarMapAction;
	private final JPanel header = new JPanel();
	private final JPanel primaryNav = new JPanel(new GridLayout(1, 3, 7, 0));
	private final JPanel secondaryNav = new JPanel(new GridLayout(1, 3, 6, 0));
	private final JPanel content = new JPanel();
	private final JScrollPane scrollPane;
	private final ButtonGroup primaryGroup = new ButtonGroup();
	private final ButtonGroup secondaryGroup = new ButtonGroup();
	private final JToggleButton[] primaryButtons = new JToggleButton[3];

	private String memberRsn;
	private String sessionToken;
	private int currentWorld;
	private boolean personalNotifications;
	private boolean personalSound;
	private PrimaryPage primaryPage;
	private HomePage homePage = HomePage.LEADERBOARD;
	private EventPage eventPage = EventPage.EVENT_HOME;
	private LootPeriod lootPeriod = LootPeriod.WEEKLY;
	private boolean lootRefreshInFlight;
	private boolean competitionRefreshInFlight;
	private boolean dominionRefreshInFlight;

	public SixthDegreePanel(SixthDegreeApiClient apiClient, SixthDegreeDominionApiClient dominionApiClient, Runnable dominionWarMapAction)
	{
		super(false);
		this.apiClient = apiClient;
		this.dominionApiClient = dominionApiClient;
		this.dominionWarMapAction = dominionWarMapAction == null ? () -> { } : dominionWarMapAction;
		setLayout(new BorderLayout());

		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBorder(BorderFactory.createEmptyBorder(14, 7, 8, 7));
		header.add(centerRow(label("SIXTH DEGREE", TITLE, WHITE)));
		header.add(Box.createRigidArea(new Dimension(0, 10)));
		buildPrimaryNavigation();
		primaryNav.setVisible(false);
		header.add(primaryNav);
		header.add(Box.createRigidArea(new Dimension(0, 7)));
		secondaryNav.setVisible(false);
		header.add(secondaryNav);
		add(header, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(5, 4, 18, 4));
		scrollPane = new JScrollPane(content);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);
		showLoggedOut();
	}

	public void showLoggedOut()
	{
		leaveMemberMode();
		renderAccess(
			"Log in to Old School RuneScape.<br><br>We’ll check whether this account belongs to Sixth Degree.",
			"Join Sixth Degree",
			() -> LinkBrowser.browse(DISCORD_INVITE),
			"Not a member yet? Apply through our Discord."
		);
	}

	public void showRecruitment(String rsn)
	{
		leaveMemberMode();
		String who = rsn == null || rsn.isBlank() ? "This account" : "<b>" + escape(rsn) + "</b>";
		renderAccess(
			who + " isn’t currently a Sixth Degree clan member.<br><br>Join our Discord to apply and get involved.",
			"Join Sixth Degree",
			() -> LinkBrowser.browse(DISCORD_INVITE),
			"PvM • Events • Competitions • Learners welcome"
		);
	}

	public void showDiscordLinkRequired(String rsn, Runnable onConnect)
	{
		showDiscordLinkRequired(rsn, onConnect, null);
	}

	public void showDiscordLinkRequired(String rsn, Runnable onConnect, String reason)
	{
		leaveMemberMode();
		String text = "Welcome, <b>" + escape(rsn) + "</b>.<br><br>This account is in Sixth Degree. Connect Discord to unlock the clan panel.";
		if (reason != null && !reason.isBlank())
		{
			text += "<br><br>" + escape(reason);
		}
		renderAccess(text, "Connect Discord", onConnect, "Requires the Discord Clan Member role.");
	}

	public void showLinking(String rsn)
	{
		leaveMemberMode();
		renderAccess(
			"Linking <b>" + escape(rsn) + "</b>…<br><br>Finish the Discord authorisation in your browser.",
			null, null,
			"Waiting for Boss Lady to verify your account."
		);
	}

	public void showCheckingAccess(String rsn)
	{
		leaveMemberMode();
		renderAccess("Checking clan and Discord access for <b>" + escape(rsn) + "</b>…", null, null, "This only takes a moment.");
	}

	public void showConnectionError(String rsn, Runnable onRetry)
	{
		leaveMemberMode();
		renderAccess(
			"Sixth Degree couldn’t verify the server connection for <b>" + escape(rsn) + "</b>.",
			"Retry", onRetry,
			"Your local RuneScape clan membership is still recognised."
		);
	}

	public void showMemberHome(String rsn, String token, int world, boolean notifications, boolean sound)
	{
		boolean same = memberRsn != null && memberRsn.equalsIgnoreCase(rsn)
			&& sessionToken != null && sessionToken.equals(token) && primaryPage != null;
		memberRsn = rsn;
		sessionToken = token;
		currentWorld = world;
		personalNotifications = notifications;
		personalSound = sound;
		primaryNav.setVisible(true);
		if (!same)
		{
			selectPrimary(PrimaryPage.HOME);
		}
	}

	/** Refresh only while the user is actually looking at the loot board. */
	public void refreshLootLeaderboardIfVisible()
	{
		if (sessionToken == null
			|| primaryPage != PrimaryPage.HOME
			|| homePage != HomePage.LEADERBOARD
			|| !isShowing())
		{
			return;
		}
		requestLootLeaderboard(false);
	}

	/** Refresh live competition standings without moving the member's scroll position. */
	public void refreshCompetitionIfVisible()
	{
		if (sessionToken == null
			|| primaryPage != PrimaryPage.EVENTS
			|| (eventPage != EventPage.BOTW && eventPage != EventPage.SOTW)
			|| !isShowing())
		{
			return;
		}
		requestCompetition(eventPage == EventPage.BOTW ? "BOTW" : "SOTW", false);
	}

	/** Refresh the live Dominion dashboard without forcing the War Map open. */
	public void refreshDominionIfVisible()
	{
		if (sessionToken == null
			|| primaryPage != PrimaryPage.EVENTS
			|| eventPage != EventPage.DOMINION
			|| !isShowing())
		{
			return;
		}
		requestDominion(false);
	}

	private void buildPrimaryNavigation()
	{
		PrimaryPage[] pages = PrimaryPage.values();
		for (int i = 0; i < pages.length; i++)
		{
			PrimaryPage page = pages[i];
			JToggleButton button = iconButton(buildPrimaryIcon(page), primaryTooltip(page));
			button.addActionListener(e -> selectPrimary(page));
			primaryButtons[i] = button;
			primaryGroup.add(button);
			primaryNav.add(button);
		}
	}

	private void selectPrimary(PrimaryPage page)
	{
		if (sessionToken == null || memberRsn == null)
		{
			return;
		}
		primaryPage = page;
		for (int i = 0; i < primaryButtons.length; i++)
		{
			boolean selected = PrimaryPage.values()[i] == page;
			primaryButtons[i].setSelected(selected);
			primaryButtons[i].setBorder(BorderFactory.createLineBorder(selected ? SELECTED : BORDER));
		}
		if (page == PrimaryPage.HOME)
		{
			buildHomeSubNav();
			loadHome();
		}
		else if (page == PrimaryPage.EVENTS)
		{
			buildEventSubNav();
			loadEventPage();
		}
		else
		{
			secondaryNav.setVisible(false);
			secondaryNav.removeAll();
			loadLfg();
		}
		header.revalidate();
		header.repaint();
	}

	private void buildHomeSubNav()
	{
		secondaryNav.removeAll();
		secondaryGroup.clearSelection();
		secondaryNav.setLayout(new GridLayout(1, 2, 6, 0));
		for (HomePage page : HomePage.values())
		{
			JToggleButton button = textToggle(page == HomePage.LEADERBOARD ? "LEADERBOARD" : "SETTINGS");
			button.setSelected(page == homePage);
			button.setBorder(BorderFactory.createLineBorder(page == homePage ? SELECTED : BORDER));
			button.addActionListener(e ->
			{
				homePage = page;
				buildHomeSubNav();
				loadHome();
			});
			secondaryGroup.add(button);
			secondaryNav.add(button);
		}
		secondaryNav.setVisible(true);
	}

	private void buildEventSubNav()
	{
		secondaryNav.removeAll();
		secondaryGroup.clearSelection();
		secondaryNav.setLayout(new GridLayout(1, 4, 5, 0));
		for (EventPage page : EventPage.values())
		{
			JToggleButton button;
			if (page == EventPage.DOMINION)
			{
				button = subIconToggle(buildDominionIcon(), "Dominion");
			}
			else
			{
				String text = page == EventPage.EVENT_HOME ? "EVENTS" : page.name();
				button = textToggle(text);
			}
			button.setSelected(page == eventPage);
			button.setBorder(BorderFactory.createLineBorder(page == eventPage ? SELECTED : BORDER));
			button.addActionListener(e ->
			{
				eventPage = page;
				buildEventSubNav();
				loadEventPage();
			});
			secondaryGroup.add(button);
			secondaryNav.add(button);
		}
		secondaryNav.setVisible(true);
	}

	private void loadHome()
	{
		if (homePage == HomePage.LEADERBOARD)
		{
			requestLootLeaderboard(true);
		}
		else
		{
			showLoading("Settings");
			apiClient.getNotificationRules(sessionToken).whenComplete((data, error) ->
				SwingUtilities.invokeLater(() -> renderSettings(data, error)));
		}
	}

	private void requestLootLeaderboard(boolean showSpinner)
	{
		if (lootRefreshInFlight || sessionToken == null)
		{
			return;
		}
		lootRefreshInFlight = true;
		final LootPeriod requestedPeriod = lootPeriod;
		if (showSpinner)
		{
			showLoading("Loot leaderboard");
		}
		apiClient.getLootLeaderboard(requestedPeriod.name().toLowerCase(Locale.ROOT), sessionToken)
			.whenComplete((data, error) -> SwingUtilities.invokeLater(() ->
			{
				lootRefreshInFlight = false;
				if (lootPeriod == requestedPeriod)
				{
					renderLootLeaderboard(data, error);
				}
				else
				{
					refreshLootLeaderboardIfVisible();
				}
			}));
	}

	private void renderLootLeaderboard(SixthDegreeApiClient.LootLeaderboardResponse data, Throwable error)
	{
		if (primaryPage != PrimaryPage.HOME || homePage != HomePage.LEADERBOARD)
		{
			return;
		}
		if (error != null || data == null || !data.ok)
		{
			renderError("LOOT LEADERBOARD", error);
			return;
		}

		clearContent();
		addHeading("LOOT LEADERBOARD");
		addCentered("Every drop counts.<br>Screenshot thresholds do not affect totals.", MUTED);
		addGap(10);

		JPanel periods = new JPanel(new GridLayout(1, 3, 5, 0));
		periods.setOpaque(false);
		periods.setMaximumSize(new Dimension(CARD_WIDTH, 30));
		for (LootPeriod period : LootPeriod.values())
		{
			JButton button = smallButton(period.name());
			button.setBorder(BorderFactory.createLineBorder(period == lootPeriod ? SELECTED : BORDER));
			button.addActionListener(e ->
			{
				lootPeriod = period;
				loadHome();
			});
			periods.add(button);
		}
		content.add(centerRow(periods));
		addGap(12);

		JPanel board = card();
		addCardStrong(board, prettyPeriod(lootPeriod));
		if (data.entries == null || data.entries.length == 0)
		{
			addCardText(board, "No tracked loot yet. Drops appear automatically while verified Sixth Degree accounts are logged in.");
		}
		else
		{
			for (SixthDegreeApiClient.LootLeaderboardEntry entry : data.entries)
			{
				addCardText(board,
					"<b>" + entry.rank + ". " + escape(entry.rsn) + "</b> — "
						+ formatLootGp(entry.value_gp));
			}
		}
		addCard(board);

		if (data.you != null)
		{
			addGap(10);
			addCentered("You: <b>#" + data.you.rank + " • " + formatLootGp(data.you.value_gp)
				+ "</b><br>" + NUMBER.format(data.you.drop_count) + " drops", WHITE);
		}
		addGap(8);
		addCentered("Exact values are stored; the panel uses compact K/M/B display.", MUTED);
		finishContent();
	}

	private void renderSettings(SixthDegreeApiClient.NotificationResponse data, Throwable error)
	{
		if (primaryPage != PrimaryPage.HOME || homePage != HomePage.SETTINGS)
		{
			return;
		}
		if (error != null || data == null)
		{
			renderError("SETTINGS", error);
			return;
		}
		clearContent();
		addHeading("SETTINGS");
		addCentered("Clan alert rules are locked centrally.<br>Members only control local sound and notifications.", MUTED);
		addGap(10);
		JsonObject rules = data.rules == null ? new JsonObject() : data.rules;
		addRule("VALUABLE LOOT", nested(rules, "loot"), true);
		addGap(6);
		addRule("PETS", nested(rules, "pets"), false);
		addGap(6);
		addRule("COLLECTION LOGS", nested(rules, "collection_logs"), false);
		addGap(6);
		addRule("MILESTONES", nested(rules, "milestones"), false);
		addGap(6);
		addRule("BOSS PBS", nested(rules, "boss_pbs"), false);
		addGap(10);
		JPanel local = card();
		addCardTitle(local, "YOUR CLIENT");
		addCardText(local, "Notifications: <b>" + (personalNotifications ? "On" : "Off") + "</b>");
		addCardText(local, "Sound: <b>" + (personalSound ? "On" : "Off") + "</b>");
		addCard(local);
		finishContent();
	}

	private void loadEventPage()
	{
		if (eventPage == EventPage.EVENT_HOME)
		{
			showLoading("Events");
			apiClient.getDashboard(sessionToken).whenComplete((data, error) ->
				SwingUtilities.invokeLater(() -> renderEventsHome(data, error)));
		}
		else if (eventPage == EventPage.DOMINION)
		{
			requestDominion(true);
		}
		else
		{
			requestCompetition(eventPage == EventPage.BOTW ? "BOTW" : "SOTW", true);
		}
	}

	private void requestDominion(boolean showSpinner)
	{
		if (dominionRefreshInFlight || sessionToken == null || dominionApiClient == null)
		{
			return;
		}
		dominionRefreshInFlight = true;
		final int scrollPosition = showSpinner ? 0 : scrollPane.getVerticalScrollBar().getValue();
		if (showSpinner)
		{
			showLoading("Dominion");
		}
		dominionApiClient.getState(sessionToken).whenComplete((data, error) ->
			SwingUtilities.invokeLater(() ->
			{
				dominionRefreshInFlight = false;
				if (primaryPage == PrimaryPage.EVENTS && eventPage == EventPage.DOMINION)
				{
					renderDominionDashboard(data, error);
					if (!showSpinner)
					{
						SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(scrollPosition));
					}
				}
			}));
	}

	private void renderDominionDashboard(SixthDegreeDominionApiClient.StateResponse data, Throwable error)
	{
		if (primaryPage != PrimaryPage.EVENTS || eventPage != EventPage.DOMINION)
		{
			return;
		}
		if (error != null || data == null || !data.ok)
		{
			renderError("DOMINION", error);
			return;
		}

		clearContent();
		addHeading("DOMINION");
		if (!data.available)
		{
			JPanel unavailable = card();
			addCardStrong(unavailable, "No active Dominion assignment");
			addCardText(unavailable, "When this RuneScape account is placed on a Dominion team, your war dashboard will appear here automatically.");
			addCard(unavailable);
			finishContent();
			return;
		}

		JPanel summary = card();
		String teamName = data.team == null ? "Dominion" : escape(nullTo(data.team.name, data.team.code));
		addCardTitle(summary, teamName.toUpperCase(Locale.ROOT));
		int dayNumber = data.day == null ? 0 : data.day.day_number;
		addCardStrong(summary, dayNumber > 0 ? "Day " + dayNumber + " / 14" : "Campaign ready");
		addCardText(summary, dominionScoreline(data));
		addCardText(summary, "Phase: <b>" + escape(dominionPhase(data.event_phase)) + "</b>");
		if (data.day != null && data.day.ends_at > Instant.now().getEpochSecond() && "BATTLE".equalsIgnoreCase(data.event_phase))
		{
			addCardText(summary, "Resolution in <b>" + remaining(data.day.ends_at) + "</b>");
		}
		else if ("VOTING".equalsIgnoreCase(data.event_phase))
		{
			addCardText(summary, "Attack voting is open in your Discord war room. Scoring is paused until orders lock.");
		}
		addCard(summary);

		addGap(10);
		JPanel orders = card();
		addCardTitle(orders, "TODAY'S ORDERS");
		boolean hasFront = false;
		if (data.team != null && data.team.attack_orders != null && data.team.attack_orders.orders != null)
		{
			for (SixthDegreeDominionApiClient.AttackOrder order : data.team.attack_orders.orders)
			{
				if (order == null || order.region_id == null || order.region_id.isBlank()) continue;
				hasFront = true;
				addCardStrong(orders, "ATTACK — " + escape(dominionRegionName(order.region_id)));
				addCardText(orders, dominionBattleLine(data, order.region_id));
			}
		}
		if (data.publicState != null && data.publicState.battles != null && data.team != null)
		{
			for (SixthDegreeDominionApiClient.Battle battle : data.publicState.battles)
			{
				if (battle == null || battle.teams == null || battle.region_id == null) continue;
				boolean defence = false;
				for (SixthDegreeDominionApiClient.BattleTeam participant : battle.teams)
				{
					if (participant != null && participant.team_id == data.team.id && "DEFENCE".equalsIgnoreCase(participant.role))
					{
						defence = true;
						break;
					}
				}
				if (defence)
				{
					hasFront = true;
					addCardStrong(orders, "DEFEND — " + escape(dominionRegionName(battle.region_id)));
					addCardText(orders, dominionBattleLine(data, battle.region_id));
				}
			}
		}
		if (!hasFront)
		{
			addCardText(orders, "No battle fronts are open yet. Attack Orders appear here as soon as voting is locked.");
		}
		addCard(orders);

		addGap(10);
		JPanel personal = card();
		addCardTitle(personal, "PERSONAL ORDER");
		JsonObject personalOrder = data.player == null ? null : data.player.personal_order;
		if (personalOrder == null || personalOrder.size() == 0)
		{
			addCardText(personal, "Your Personal Order is assigned when today's battles open.");
		}
		else
		{
			addCardStrong(personal, escape(jsonString(personalOrder, "title", "Personal Order")));
			String description = jsonString(personalOrder, "description", "");
			if (!description.isBlank()) addCardText(personal, escape(description));
			addCardText(personal, dominionObjectiveProgress(personalOrder));
			String reward = jsonString(personalOrder, "reward_influence", "");
			if (!reward.isBlank()) addCardText(personal, "Reward: <b>+" + escape(reward) + " War Reserve</b>");
		}
		addCard(personal);

		addGap(10);
		JPanel shared = card();
		addCardTitle(shared, "SHARED MISSION");
		JsonObject sharedMission = data.team == null ? null : data.team.shared_mission;
		if (sharedMission == null || sharedMission.size() == 0)
		{
			addCardText(shared, "Your team mission is assigned when today's battles open.");
		}
		else
		{
			addCardStrong(shared, escape(jsonString(sharedMission, "title", "Shared Mission")));
			addCardText(shared, dominionObjectiveProgress(sharedMission));
			JsonObject progress = nested(sharedMission, "progress");
			try
			{
				if (progress.has("components") && progress.get("components").isJsonArray())
				{
					int shown = 0;
					for (com.google.gson.JsonElement element : progress.getAsJsonArray("components"))
					{
						if (!element.isJsonObject() || shown++ >= 4) continue;
						JsonObject component = element.getAsJsonObject();
						String label = jsonString(component, "label", "Objective");
						int current = jsonInt(component, "current", 0);
						int target = jsonInt(component, "target", 0);
						boolean complete = bool(component, "complete", false);
						addCardText(shared, (complete ? "✓ " : "• ") + escape(label) + (target > 0 ? " — <b>" + current + "/" + target + "</b>" : ""));
					}
				}
			}
			catch (Exception ignored) { }
			String reward = jsonString(sharedMission, "reward_influence", "");
			if (!reward.isBlank()) addCardText(shared, "Reward: <b>+" + escape(reward) + " War Reserve</b>");
		}
		addCard(shared);

		addGap(10);
		JPanel contribution = card();
		addCardTitle(contribution, "YOUR CONTRIBUTION");
		if (data.player != null && data.player.contribution != null)
		{
			addCardText(contribution, "Territory: <b>" + escape(nullTo(data.player.contribution.territory_influence, "0")) + " Influence</b>");
			addCardText(contribution, "Support / Reserve: <b>" + escape(nullTo(data.player.contribution.war_influence, "0")) + " Influence</b>");
		}
		if (data.player != null && data.player.xp != null)
		{
			addCardText(contribution, "XP Influence: <b>" + data.player.xp.used_units + " / " + data.player.xp.cap_units + "</b>");
			addCardText(contribution, NUMBER.format((long) data.player.xp.used_units * data.player.xp.xp_per_influence)
				+ " / " + NUMBER.format((long) data.player.xp.cap_units * data.player.xp.xp_per_influence) + " qualifying XP");
		}
		addCardText(contribution, "Routine diminishing returns are applied automatically before Support routing.");
		addCard(contribution);

		addGap(10);
		JPanel reserve = card();
		addCardTitle(reserve, "WAR RESERVE");
		if (data.team != null && data.team.war_reserve != null)
		{
			addCardStrong(reserve, escape(nullTo(data.team.war_reserve.available_influence, "0")) + " available");
			addCardText(reserve, "Earned: <b>" + escape(nullTo(data.team.war_reserve.gross_influence, "0")) + "</b><br>Deployed today: <b>" + escape(nullTo(data.team.war_reserve.deployed_influence, "0")) + "</b>");
		}
		JsonObject reserveProposal = data.team == null ? null : data.team.reserve_proposal;
		if (reserveProposal != null && reserveProposal.size() > 0)
		{
			long amountMilli = jsonLong(reserveProposal, "amount_milli", 0L);
			String region = jsonString(reserveProposal, "region_id", "");
			int approve = jsonInt(reserveProposal, "approve_count", 0);
			int required = jsonInt(reserveProposal, "required_votes", 0);
			addCardText(reserve, "Pending: <b>" + formatInfluence(amountMilli) + " → " + escape(dominionRegionName(region)) + "</b>");
			addCardText(reserve, "Team approval: <b>" + approve + " / " + required + "</b>");
		}
		else
		{
			addCardText(reserve, "Reserve proposals and voting are handled privately in your Discord war room.");
		}
		addCard(reserve);

		addGap(12);
		JButton map = wideButton("Open Dominion Map");
		map.setMaximumSize(new Dimension(CARD_WIDTH, 38));
		map.addActionListener(e -> dominionWarMapAction.run());
		content.add(centerRow(map));
		addGap(6);
		JButton refresh = smallButton("Refresh Dominion");
		refresh.addActionListener(e -> requestDominion(false));
		content.add(centerRow(refresh));
		finishContent();
	}

	private static String dominionScoreline(SixthDegreeDominionApiClient.StateResponse data)
	{
		int red = 0;
		int blue = 0;
		if (data.publicState != null && data.publicState.dominion_standings != null)
		{
			for (SixthDegreeDominionApiClient.Standing standing : data.publicState.dominion_standings)
			{
				if (standing == null || standing.code == null) continue;
				if ("RED".equalsIgnoreCase(standing.code)) red = standing.points;
				if ("BLUE".equalsIgnoreCase(standing.code)) blue = standing.points;
			}
		}
		return "<font color='#e45b5b'><b>Red " + red + " DP</b></font> • <font color='#6297ea'><b>Blue " + blue + " DP</b></font>";
	}

	private static String dominionPhase(String phase)
	{
		if (phase == null || phase.isBlank()) return "Ready";
		if ("BATTLE".equalsIgnoreCase(phase)) return "Battles live";
		if ("VOTING".equalsIgnoreCase(phase)) return "Attack voting";
		if ("RESOLVED".equalsIgnoreCase(phase)) return "Day resolved";
		if ("COMPLETE".equalsIgnoreCase(phase)) return "Campaign complete";
		return phase.substring(0, 1).toUpperCase(Locale.ROOT) + phase.substring(1).toLowerCase(Locale.ROOT);
	}

	private static String dominionRegionName(String regionId)
	{
		SixthDegreeDominionMapModel.TerritorySpec spec = SixthDegreeDominionMapModel.byId(regionId);
		return spec == null ? nullTo(regionId, "Unknown region") : spec.name;
	}

	private static String dominionBattleLine(SixthDegreeDominionApiClient.StateResponse data, String regionId)
	{
		if (data.publicState == null || data.publicState.battles == null)
		{
			return "Waiting for live score.";
		}
		for (SixthDegreeDominionApiClient.Battle battle : data.publicState.battles)
		{
			if (battle == null || battle.region_id == null || !battle.region_id.equalsIgnoreCase(regionId)) continue;
			if (battle.scores == null || battle.scores.length == 0) return "Battle open — no Influence scored yet.";
			StringBuilder score = new StringBuilder();
			long our = 0;
			long bestOther = 0;
			for (SixthDegreeDominionApiClient.BattleScore row : battle.scores)
			{
				if (row == null) continue;
				if (score.length() > 0) score.append(" • ");
				score.append(escape(nullTo(row.code, row.name))).append(" ").append(formatInfluence(row.final_milli));
				if (data.team != null && row.team_id == data.team.id) our = row.final_milli;
				else bestOther = Math.max(bestOther, row.final_milli);
			}
			long delta = our - bestOther;
			if (battle.scores.length > 1)
			{
				score.append("<br>").append(delta >= 0 ? "Ahead by <b>" : "Behind by <b>")
					.append(formatInfluence(Math.abs(delta))).append("</b>");
			}
			return score.toString();
		}
		return "Front preparing.";
	}

	private static String dominionObjectiveProgress(JsonObject objective)
	{
		JsonObject progress = nested(objective, "progress");
		if (progress.size() == 0)
		{
			String status = jsonString(objective, "status", "ASSIGNED");
			return "Status: <b>" + escape(status) + "</b>";
		}
		int current = jsonInt(progress, "current", 0);
		int target = jsonInt(progress, "target", 0);
		String label = jsonString(progress, "label", "Progress");
		boolean complete = bool(progress, "complete", false);
		if (target > 0)
		{
			return (complete ? "Complete ✓" : escape(label)) + ": <b>" + current + " / " + target + "</b>";
		}
		return complete ? "Complete ✓" : "In progress";
	}

	private static String formatInfluence(long milli)
	{
		String value = String.format(Locale.UK, "%.2f", Math.max(0L, milli) / 1000.0);
		while (value.contains(".") && value.endsWith("0")) value = value.substring(0, value.length() - 1);
		if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
		return value;
	}

	private static String jsonString(JsonObject object, String key, String fallback)
	{
		try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback; }
		catch (Exception ignored) { return fallback; }
	}

	private static int jsonInt(JsonObject object, String key, int fallback)
	{
		try { return object != null && object.has(key) ? object.get(key).getAsInt() : fallback; }
		catch (Exception ignored) { return fallback; }
	}

	private static long jsonLong(JsonObject object, String key, long fallback)
	{
		try { return object != null && object.has(key) ? object.get(key).getAsLong() : fallback; }
		catch (Exception ignored) { return fallback; }
	}

	private void requestCompetition(String kind, boolean showSpinner)
	{
		if (competitionRefreshInFlight || sessionToken == null)
		{
			return;
		}
		competitionRefreshInFlight = true;
		final EventPage requestedPage = "BOTW".equals(kind) ? EventPage.BOTW : EventPage.SOTW;
		final int scrollPosition = showSpinner ? 0 : scrollPane.getVerticalScrollBar().getValue();
		if (showSpinner)
		{
			showLoading(kind);
		}
		apiClient.getCompetition(kind, sessionToken).whenComplete((data, error) ->
			SwingUtilities.invokeLater(() ->
			{
				competitionRefreshInFlight = false;
				if (primaryPage == PrimaryPage.EVENTS && eventPage == requestedPage)
				{
					renderCompetition(kind, data, error);
					if (!showSpinner)
					{
						SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(scrollPosition));
					}
				}
				else
				{
					refreshCompetitionIfVisible();
				}
			}));
	}

	private void renderEventsHome(SixthDegreeApiClient.Dashboard data, Throwable error)
	{
		if (primaryPage != PrimaryPage.EVENTS || eventPage != EventPage.EVENT_HOME)
		{
			return;
		}
		if (error != null || data == null || !data.ok)
		{
			renderError("EVENTS", error);
			return;
		}
		clearContent();
		addHeading("EVENT HOME");
		List<SixthDegreeApiClient.EventItem> visible = new ArrayList<>();
		if (data.events != null)
		{
			for (SixthDegreeApiClient.EventItem event : data.events)
			{
				if (event.live || (event.event_type != null && !event.event_type.isBlank()))
				{
					visible.add(event);
				}
			}
		}
		if (visible.isEmpty())
		{
			JPanel empty = card();
			addCardStrong(empty, "No published clan events");
			addCardText(empty, "Events published for the RuneLite companion will appear here automatically.");
			addCard(empty);
		}
		else
		{
			for (SixthDegreeApiClient.EventItem event : visible)
			{
				addCard(eventCard(event));
				addGap(7);
			}
		}
		addGap(12);
		addHeading("COMPETITIONS");
		competitionShortcut("BOSS OF THE WEEK", "BOTW", data.botw);
		addGap(7);
		competitionShortcut("SKILL OF THE WEEK", "SOTW", data.sotw);
		finishContent();
	}

	private JPanel eventCard(SixthDegreeApiClient.EventItem event)
	{
		JPanel panel = card();
		addCardTitle(panel, event.live ? "LIVE" : "UPCOMING");
		addCardStrong(panel, escape(nullTo(event.title, "Clan Event")));
		addCardText(panel, event.live ? "Ends " + remaining(event.end_time) : formatDate(event.start_time));
		if (event.world > 0) addCardText(panel, "World <b>" + event.world + "</b>");
		if (event.location != null && !event.location.isBlank()) addCardText(panel, escape(event.location));
		if (event.requirements != null && !event.requirements.isBlank()) addCardText(panel, "Requires: " + escape(event.requirements));
		if (event.description != null && !event.description.isBlank()) addCardText(panel, escape(trim(event.description, 150)));
		addCardText(panel, event.attending_count + " attending on Discord");
		return panel;
	}

	private void competitionShortcut(String title, String kind, SixthDegreeApiClient.CompetitionResponse response)
	{
		JPanel panel = card();
		addCardTitle(panel, title);
		if (response != null && response.active != null)
		{
			addCardStrong(panel, escape(nullTo(response.active.metric, response.active.title)));
			addCardText(panel, "Ends " + remaining(response.active.end_time));
			if (response.active.you != null) addCardText(panel, "You: <b>#" + response.active.you.rank + " • " + formatScore(response.active.you.score, kind, response.active.metric) + "</b>");
		}
		else
		{
			addCardText(panel, "Nothing live right now.");
		}
		addCard(panel);
	}

	private void renderCompetition(String kind, SixthDegreeApiClient.CompetitionResponse data, Throwable error)
	{
		EventPage expectedPage = "BOTW".equals(kind) ? EventPage.BOTW : EventPage.SOTW;
		if (primaryPage != PrimaryPage.EVENTS || eventPage != expectedPage) return;
		if (error != null || data == null || !data.ok)
		{
			renderError(kind, error);
			return;
		}
		clearContent();
		addHeading("BOTW".equals(kind) ? "BOSS OF THE WEEK" : "SKILL OF THE WEEK");
		if (data.active == null)
		{
			JPanel empty = card();
			addCardStrong(empty, "Nothing active right now");
			addCardText(empty, "BOTW".equals(kind)
				? "The next boss competition and leaderboard will appear here automatically."
				: "The next skill competition and XP leaderboard will appear here automatically.");
			addCard(empty);
			finishContent();
			return;
		}
		SixthDegreeApiClient.Competition active = data.active;
		String state = competitionState(active);
		JPanel summary = card();
		addCardTitle(summary, state);
		addCardStrong(summary, escape(nullTo(active.metric, active.title)));
		if ("UPCOMING".equals(state))
		{
			addCardText(summary, "Starts " + formatDate(active.start_time));
		}
		else
		{
			addCardText(summary, "Ends " + remaining(active.end_time));
		}
		if ("PAUSED".equals(state)) addCardText(summary, "Scoring is temporarily paused.");
		if ("CLOSING".equals(state)) addCardText(summary, "Final standings are being calculated.");
		if (active.description != null && !active.description.isBlank()) addCardText(summary, escape(trim(active.description, 180)));
		if (active.prize != null && !active.prize.isBlank()) addCardText(summary, "Prize: <b>" + escape(active.prize) + "</b>");
		if (active.you != null)
		{
			addCardText(summary, "Your score: <b>" + formatScore(active.you.score, kind, active.metric) + "</b> (#" + active.you.rank + ")");
			if ("SOTW".equals(kind)) addCardText(summary, "Start XP: " + NUMBER.format(active.you.baseline) + "<br>Current XP: " + NUMBER.format(active.you.current_value));
		}
		addCard(summary);
		addGap(12);
		addHeading("LEADERBOARD");
		if (active.standings == null || active.standings.length == 0)
		{
			addCentered("Scores will appear as members gain progress.", MUTED);
		}
		else
		{
			for (SixthDegreeApiClient.Standing standing : active.standings)
			{
				JPanel row = card();
				addCardText(row, "<b>" + standing.rank + ". " + escape(standing.rsn) + "</b><br>" + formatScore(standing.score, kind, active.metric));
				addCard(row);
				addGap(5);
			}
		}
		finishContent();
	}

	private void loadLfg()
	{
		showLoading("Looking for Group");
		apiClient.getLfg(sessionToken).whenComplete((data, error) ->
			SwingUtilities.invokeLater(() -> renderLfg(data, error, null)));
	}

	private void renderLfg(SixthDegreeApiClient.LfgResponse data, Throwable error, String status)
	{
		if (primaryPage != PrimaryPage.LFG) return;
		if (error != null || data == null)
		{
			renderError("LOOKING FOR GROUP", error);
			return;
		}
		clearContent();
		addHeading("LOOKING FOR GROUP");
		addCentered("Post what you want to do.<br>Clan members are notified instantly.<br>Group up in clan chat.", MUTED);
		addGap(10);

		JPanel composer = card();
		addCardTitle(composer, "ACTIVITY");
		JTextField activity = field("e.g. TOB, Nex, learner TOA");
		composer.add(activity);
		composer.add(Box.createRigidArea(new Dimension(0, 8)));
		addCardTitle(composer, "DESCRIPTION");
		JTextField description = field("e.g. Need +2, happy to teach");
		composer.add(description);
		composer.add(Box.createRigidArea(new Dimension(0, 8)));
		addCardText(composer, "World <b>" + currentWorld + "</b><br>Expires after 60 minutes");
		JButton post = wideButton("Post LFG");
		post.setMaximumSize(new Dimension(INNER_WIDTH, 32));
		post.addActionListener(e ->
		{
			String activityText = clean(activity.getText());
			String descriptionText = clean(description.getText());
			if (activityText.isEmpty())
			{
				activity.requestFocusInWindow();
				return;
			}
			String note = descriptionText.isEmpty() ? activityText : activityText + " — " + descriptionText;
			if (note.length() > 100) note = note.substring(0, 100);
			post.setEnabled(false);
			apiClient.postLfg(sessionToken, note, currentWorld).whenComplete((posted, postError) ->
			{
				if (postError != null)
				{
					SwingUtilities.invokeLater(() -> renderLfg(data, null, errorText(postError)));
					return;
				}
				apiClient.getLfg(sessionToken).whenComplete((fresh, freshError) ->
					SwingUtilities.invokeLater(() -> renderLfg(fresh, freshError, "LFG posted ✓")));
			});
		});
		composer.add(Box.createRigidArea(new Dimension(0, 8)));
		composer.add(post);
		addCard(composer);

		addGap(8);
		JPanel actions = new JPanel(new GridLayout(1, 2, 6, 0));
		actions.setOpaque(false);
		actions.setMaximumSize(new Dimension(CARD_WIDTH, 32));
		JButton refresh = smallButton("Refresh");
		refresh.addActionListener(e -> loadLfg());
		JButton remove = smallButton("Remove");
		remove.addActionListener(e -> apiClient.deleteMyLfg(sessionToken).whenComplete((ignored, deleteError) ->
			apiClient.getLfg(sessionToken).whenComplete((fresh, freshError) ->
				SwingUtilities.invokeLater(() -> renderLfg(fresh, freshError, deleteError == null ? "Your LFG was removed." : errorText(deleteError))))));
		actions.add(refresh);
		actions.add(remove);
		content.add(centerRow(actions));
		if (status != null && !status.isBlank())
		{
			addGap(7);
			addCentered(escape(status), MUTED);
		}
		addGap(12);
		addHeading("ACTIVE");
		if (data.entries == null || data.entries.length == 0)
		{
			addCentered("Nobody is looking for a group<br>right now.", MUTED);
		}
		else
		{
			for (SixthDegreeApiClient.LfgEntry entry : data.entries)
			{
				JPanel item = card();
				boolean mine = entry.rsn != null && entry.rsn.equalsIgnoreCase(memberRsn);
				addCardStrong(item, escape(nullTo(entry.rsn, "Clan member")) + (mine ? " • YOU" : ""));
				addCardText(item, escape(nullTo(entry.note, "")));
				addCardText(item, "W<b>" + entry.world + "</b> • " + remaining(entry.expires_at) + " left");
				addCard(item);
				addGap(7);
			}
		}
		finishContent();
	}

	private void addRule(String title, JsonObject rule, boolean threshold)
	{
		JPanel panel = card();
		addCardTitle(panel, title);
		addCardText(panel, bool(rule, "enabled", false) ? "Enabled ✓" : "Disabled");
		if (threshold && rule != null && rule.has("minimum_value"))
		{
			try { addCardText(panel, "Minimum: <b>" + NUMBER.format(rule.get("minimum_value").getAsLong()) + " gp</b>"); }
			catch (Exception ignored) { }
		}
		if (bool(rule, "screenshots", false)) addCardText(panel, "Screenshots ✓");
		addCard(panel);
	}

	private void renderAccess(String message, String buttonText, Runnable action, String footer)
	{
		clearContent();
		addGap(10);
		addCentered(message, WHITE);
		if (buttonText != null && action != null)
		{
			addGap(18);
			JButton button = wideButton(buttonText);
			button.setPreferredSize(new Dimension(176, 40));
			button.setMinimumSize(new Dimension(176, 40));
			button.setMaximumSize(new Dimension(176, 40));
			button.addActionListener(e -> action.run());
			content.add(centerRow(button));
		}
		if (footer != null && !footer.isBlank())
		{
			addGap(18);
			addCentered(footer, MUTED);
		}
		finishContent();
	}

	private void showLoading(String name)
	{
		clearContent();
		addGap(15);
		addCentered("Loading <b>" + escape(name) + "</b>…", MUTED);
		finishContent();
	}

	private void renderError(String name, Throwable error)
	{
		clearContent();
		addHeading(name.toUpperCase(Locale.ROOT));
		JPanel panel = card();
		addCardStrong(panel, "Couldn’t load this page");
		addCardText(panel, escape(errorText(error)));
		addCard(panel);
		finishContent();
	}

	private void leaveMemberMode()
	{
		memberRsn = null;
		sessionToken = null;
		primaryPage = null;
		lootRefreshInFlight = false;
		competitionRefreshInFlight = false;
		dominionRefreshInFlight = false;
		primaryGroup.clearSelection();
		secondaryGroup.clearSelection();
		primaryNav.setVisible(false);
		secondaryNav.setVisible(false);
		secondaryNav.removeAll();
	}

	private void clearContent()
	{
		content.removeAll();
	}

	private void finishContent()
	{
		content.add(Box.createVerticalGlue());
		content.revalidate();
		content.repaint();
		SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(0));
	}

	private void addHeading(String text)
	{
		content.add(centerRow(label(text, HEADING, WHITE)));
		addGap(8);
	}

	private void addCentered(String html, Color color)
	{
		JLabel label = new JLabel("<html><div style='text-align:center;width:" + INNER_WIDTH + "px'>" + html + "</div></html>", SwingConstants.CENTER);
		label.setFont(BODY);
		label.setForeground(color);
		content.add(centerRow(label));
	}

	private JPanel card()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(CARD);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER),
			BorderFactory.createEmptyBorder(9, 10, 9, 10)));
		panel.setMaximumSize(new Dimension(CARD_WIDTH, Short.MAX_VALUE));
		panel.setAlignmentX(Component.CENTER_ALIGNMENT);
		return panel;
	}

	private void addCard(JPanel panel)
	{
		content.add(centerRow(panel));
	}

	private void addCardTitle(JPanel panel, String text)
	{
		JLabel label = label(text, new Font(Font.SANS_SERIF, Font.BOLD, 10), MUTED);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(label);
		panel.add(Box.createRigidArea(new Dimension(0, 4)));
	}

	private void addCardStrong(JPanel panel, String html)
	{
		JLabel label = new JLabel("<html><div style='width:" + INNER_WIDTH + "px'><b>" + html + "</b></div></html>");
		label.setFont(BODY_BOLD);
		label.setForeground(WHITE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(label);
		panel.add(Box.createRigidArea(new Dimension(0, 4)));
	}

	private void addCardText(JPanel panel, String html)
	{
		JLabel label = new JLabel("<html><div style='width:" + INNER_WIDTH + "px'>" + html + "</div></html>");
		label.setFont(BODY);
		label.setForeground(WHITE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(label);
		panel.add(Box.createRigidArea(new Dimension(0, 3)));
	}

	private JTextField field(String tooltip)
	{
		JTextField field = new JTextField();
		field.setFont(BODY);
		field.setToolTipText(tooltip);
		field.setAlignmentX(Component.LEFT_ALIGNMENT);
		field.setMaximumSize(new Dimension(INNER_WIDTH, 30));
		field.setPreferredSize(new Dimension(INNER_WIDTH, 30));
		return field;
	}

	private JToggleButton textToggle(String text)
	{
		JToggleButton button = new JToggleButton(text);
		button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		button.setFocusPainted(false);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setPreferredSize(new Dimension(62, 29));
		return button;
	}

	private JToggleButton subIconToggle(BufferedImage icon, String tooltip)
	{
		JToggleButton button = new JToggleButton(new ImageIcon(icon));
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setPreferredSize(new Dimension(42, 29));
		return button;
	}

	private JToggleButton iconButton(BufferedImage icon, String tooltip)
	{
		JToggleButton button = new JToggleButton(new ImageIcon(icon));
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setPreferredSize(new Dimension(60, 40));
		button.setBorder(BorderFactory.createLineBorder(BORDER));
		return button;
	}

	private JButton smallButton(String text)
	{
		JButton button = new JButton(text);
		button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		button.setFocusPainted(false);
		return button;
	}

	private JButton wideButton(String text)
	{
		JButton button = new JButton(text);
		button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
		button.setFocusPainted(false);
		button.setAlignmentX(Component.CENTER_ALIGNMENT);
		return button;
	}

	private JPanel centerRow(Component component)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.CENTER_ALIGNMENT);
		row.add(component);
		Dimension preferred = row.getPreferredSize();
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
		return row;
	}

	private JLabel label(String text, Font font, Color color)
	{
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setFont(font);
		label.setForeground(color);
		return label;
	}

	private void addGap(int height)
	{
		content.add(Box.createRigidArea(new Dimension(0, height)));
	}

	private static BufferedImage buildPrimaryIcon(PrimaryPage page)
	{
		BufferedImage image = new BufferedImage(26, 26, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			if (page == PrimaryPage.HOME)
			{
				g.fillPolygon(new Polygon(new int[]{4, 13, 22}, new int[]{12, 4, 12}, 3));
				g.fillRect(7, 11, 12, 11);
			}
			else if (page == PrimaryPage.EVENTS)
			{
				g.drawRoundRect(4, 6, 18, 16, 3, 3);
				g.fillRect(4, 9, 18, 3);
				g.fillRect(8, 3, 2, 6);
				g.fillRect(16, 3, 2, 6);
			}
			else
			{
				g.fillOval(10, 3, 6, 6);
				g.fillOval(3, 6, 5, 5);
				g.fillOval(18, 6, 5, 5);
				g.fillRoundRect(7, 11, 12, 10, 7, 7);
				g.fillRoundRect(1, 13, 7, 7, 5, 5);
				g.fillRoundRect(18, 13, 7, 7, 5, 5);
			}
		}
		finally { g.dispose(); }
		return image;
	}

	private static BufferedImage buildDominionIcon()
	{
		BufferedImage image = new BufferedImage(22, 22, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(4, 3, 18, 17);
			g.drawLine(18, 3, 4, 17);
			g.drawLine(3, 14, 7, 18);
			g.drawLine(15, 18, 19, 14);
			g.drawLine(5, 4, 8, 3);
			g.drawLine(17, 4, 14, 3);
			g.fillOval(2, 17, 4, 4);
			g.fillOval(16, 17, 4, 4);
		}
		finally { g.dispose(); }
		return image;
	}

	private static String primaryTooltip(PrimaryPage page)
	{
		if (page == PrimaryPage.HOME) return "Home";
		if (page == PrimaryPage.EVENTS) return "Events";
		return "Looking for Group";
	}

	private static String prettyPeriod(LootPeriod period)
	{
		if (period == LootPeriod.DAILY) return "Today";
		if (period == LootPeriod.WEEKLY) return "This week";
		return "This month";
	}

	private static String formatLootGp(long exact)
	{
		long value = Math.max(0L, exact);
		if (value < 1_000L)
		{
			return NUMBER.format(value) + " gp";
		}
		if (value < 1_000_000L)
		{
			return (value / 1_000L) + "K";
		}
		if (value < 1_000_000_000L)
		{
			int decimals = value >= 100_000_000L ? 0 : value >= 10_000_000L ? 1 : 2;
			return compactFloor(value, 1_000_000L, decimals) + "M";
		}
		int decimals = value >= 100_000_000_000L ? 0 : value >= 10_000_000_000L ? 1 : 2;
		return compactFloor(value, 1_000_000_000L, decimals) + "B";
	}

	private static String compactFloor(long value, long unit, int decimals)
	{
		long scale = decimals == 0 ? 1L : decimals == 1 ? 10L : 100L;
		long scaled = value * scale / unit;
		if (decimals == 0)
		{
			return Long.toString(scaled);
		}
		long whole = scaled / scale;
		long fraction = scaled % scale;
		String suffix = decimals == 1
			? Long.toString(fraction)
			: String.format(Locale.UK, "%02d", fraction);
		while (suffix.endsWith("0")) suffix = suffix.substring(0, suffix.length() - 1);
		return suffix.isEmpty() ? Long.toString(whole) : whole + "." + suffix;
	}

	private static String formatDate(long epochSeconds)
	{
		if (epochSeconds <= 0) return "Time TBC";
		return EVENT_TIME.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()));
	}

	private static String remaining(long epochSeconds)
	{
		long seconds = Math.max(0, epochSeconds - Instant.now().getEpochSecond());
		long days = seconds / 86400;
		long hours = (seconds % 86400) / 3600;
		long minutes = (seconds % 3600) / 60;
		if (days > 0) return days + "d " + hours + "h";
		if (hours > 0) return hours + "h " + minutes + "m";
		return minutes + "m";
	}

	private static String competitionState(SixthDegreeApiClient.Competition competition)
	{
		if (competition == null) return "INACTIVE";
		String status = competition.status == null ? "" : competition.status.toUpperCase(Locale.ROOT);
		if ("CLOSING".equals(status)) return "CLOSING";
		if (competition.paused) return "PAUSED";
		long now = Instant.now().getEpochSecond();
		if (competition.start_time > now) return "UPCOMING";
		if (competition.live || ("ACTIVE".equals(status) && now <= competition.end_time)) return "LIVE";
		return status.isBlank() ? "ACTIVE" : status;
	}

	static String formatScore(long score, String kind, String metric)
	{
		String unit = "SOTW".equals(kind) ? " XP"
			: SixthDegreeCompetitionTracker.isGotr(metric) ? (score == 1 ? " Rift" : " Rifts") : " KC";
		return ("SOTW".equals(kind) ? "+" : "") + NUMBER.format(score) + unit;
	}

	private static JsonObject nested(JsonObject object, String key)
	{
		try { return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject(); }
		catch (Exception ignored) { return new JsonObject(); }
	}

	private static boolean bool(JsonObject object, String key, boolean fallback)
	{
		try { return object != null && object.has(key) ? object.get(key).getAsBoolean() : fallback; }
		catch (Exception ignored) { return fallback; }
	}

	private static String errorText(Throwable throwable)
	{
		Throwable current = throwable;
		while (current != null && current.getCause() != null && current.getClass().getName().contains("Completion")) current = current.getCause();
		String message = current == null ? null : current.getMessage();
		return message == null || message.isBlank() ? "Sixth Degree service is unavailable. Please retry." : message;
	}

	private static String nullTo(String value, String fallback)
	{
		return value == null || value.isBlank() ? fallback : value;
	}

	private static String clean(String value)
	{
		return value == null ? "" : value.trim().replaceAll("\\s+", " ");
	}

	private static String trim(String value, int max)
	{
		if (value == null || value.length() <= max) return value == null ? "" : value;
		return value.substring(0, Math.max(0, max - 1)).trim() + "…";
	}

	private static String escape(String value)
	{
		if (value == null) return "";
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
			.replace("\"", "&quot;").replace("'", "&#39;");
	}
}
