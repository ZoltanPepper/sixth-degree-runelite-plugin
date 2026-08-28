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
	private static final int CARD_WIDTH = 204;
	private static final int TEXT_WIDTH = 196;
	private static final Color WHITE = new Color(238, 238, 238);
	private static final Color MUTED = new Color(180, 180, 180);
	private static final Color CARD = new Color(45, 45, 45);
	private static final Color BORDER = new Color(74, 74, 74);
	private static final Color SELECTED_BORDER = new Color(230, 230, 230);
	private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("EEE d MMM • HH:mm");
	private static final NumberFormat NUMBER = NumberFormat.getIntegerInstance(Locale.UK);

	private enum PrimaryPage
	{
		HOME,
		EVENTS,
		LFG
	}

	private enum EventPage
	{
		EVENT_HOME,
		BOTW,
		SOTW
	}

	private final SixthDegreeApiClient apiClient;
	private final JPanel header = new JPanel();
	private final JPanel primaryNav = new JPanel(new GridLayout(1, 3, 7, 0));
	private final JPanel eventNav = new JPanel(new GridLayout(1, 3, 6, 0));
	private final JPanel content = new JPanel();
	private final JScrollPane scrollPane;
	private final ButtonGroup primaryGroup = new ButtonGroup();
	private final ButtonGroup eventGroup = new ButtonGroup();
	private final JToggleButton[] primaryButtons = new JToggleButton[PrimaryPage.values().length];
	private final JToggleButton[] eventButtons = new JToggleButton[EventPage.values().length];

	private String memberRsn;
	private String sessionToken;
	private int currentWorld;
	private boolean personalNotifications;
	private boolean personalSound;
	private PrimaryPage currentPrimary;
	private EventPage currentEvent = EventPage.EVENT_HOME;

	public SixthDegreePanel(SixthDegreeApiClient apiClient)
	{
		super(false);
		this.apiClient = apiClient;
		setLayout(new BorderLayout());

		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBorder(BorderFactory.createEmptyBorder(16, 8, 8, 8));
		header.add(centerRow(titleLabel("SIXTH DEGREE", 17f)));
		header.add(Box.createRigidArea(new Dimension(0, 11)));

		buildPrimaryNavigation();
		primaryNav.setVisible(false);
		header.add(centerRow(primaryNav));

		header.add(Box.createRigidArea(new Dimension(0, 7)));
		buildEventNavigation();
		eventNav.setVisible(false);
		header.add(centerRow(eventNav));
		add(header, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(7, 6, 18, 6));

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
			"Not a member yet?<br>Apply through our Discord."
		);
	}

	public void showRecruitment(String rsn)
	{
		leaveMemberMode();
		String name = rsn == null || rsn.isBlank() ? "This account" : "<b>" + escape(rsn) + "</b>";
		renderAccess(
			name + " isn’t currently a Sixth Degree clan member.<br><br>Join our Discord to apply and get involved.",
			"Join Sixth Degree",
			() -> LinkBrowser.browse(DISCORD_INVITE),
			"Events • PvM • Competitions<br>Learners welcome"
		);
	}

	public void showDiscordLinkRequired(String rsn, Runnable onConnect)
	{
		showDiscordLinkRequired(rsn, onConnect, null);
	}

	public void showDiscordLinkRequired(String rsn, Runnable onConnect, String reason)
	{
		leaveMemberMode();
		String message = "Welcome, <b>" + escape(rsn) + "</b>.<br><br>Your RuneScape account is in Sixth Degree. Connect Discord to unlock the clan panel.";
		if (reason != null && !reason.isBlank())
		{
			message += "<br><br>" + escape(reason);
		}
		renderAccess(
			message,
			"Connect Discord",
			onConnect,
			"Requires the Sixth Degree Discord<br>and Clan Member role."
		);
	}

	public void showLinking(String rsn)
	{
		leaveMemberMode();
		renderAccess(
			"Linking <b>" + escape(rsn) + "</b>…<br><br>Finish the Discord authorisation in your browser, then return here.",
			null,
			null,
			"Waiting for Boss Lady to verify your account."
		);
	}

	public void showCheckingAccess(String rsn)
	{
		leaveMemberMode();
		renderAccess(
			"Checking clan and Discord access for <b>" + escape(rsn) + "</b>…",
			null,
			null,
			"This only takes a moment."
		);
	}

	public void showMemberHome(
		String rsn,
		String token,
		int world,
		boolean notifications,
		boolean notificationSound)
	{
		boolean sameSession = memberRsn != null
			&& memberRsn.equalsIgnoreCase(rsn)
			&& sessionToken != null
			&& sessionToken.equals(token)
			&& currentPrimary != null;

		memberRsn = rsn;
		sessionToken = token;
		currentWorld = world;
		personalNotifications = notifications;
		personalSound = notificationSound;
		primaryNav.setVisible(true);

		if (!sameSession)
		{
			selectPrimary(PrimaryPage.HOME);
		}
	}

	public void showConnectionError(String rsn, Runnable onRetry)
	{
		leaveMemberMode();
		renderAccess(
			"Sixth Degree couldn’t verify the server connection for <b>" + escape(rsn) + "</b>.<br><br>Your in-game clan membership is still recognised.",
			"Retry",
			onRetry,
			"No account access data has been changed."
		);
	}

	private void buildPrimaryNavigation()
	{
		PrimaryPage[] pages = PrimaryPage.values();
		for (int i = 0; i < pages.length; i++)
		{
			PrimaryPage page = pages[i];
			JToggleButton button = iconButton(buildPrimaryIcon(page), primaryTooltip(page), 62, 38);
			button.addActionListener(e -> selectPrimary(page));
			primaryButtons[i] = button;
			primaryGroup.add(button);
			primaryNav.add(button);
		}
		primaryNav.setOpaque(false);
		primaryNav.setPreferredSize(new Dimension(204, 38));
	}

	private void buildEventNavigation()
	{
		String[] labels = {"EVENTS", "BOTW", "SOTW"};
		EventPage[] pages = EventPage.values();
		for (int i = 0; i < pages.length; i++)
		{
			EventPage page = pages[i];
			JToggleButton button = new JToggleButton(labels[i]);
			button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
			button.setForeground(WHITE);
			button.setFocusPainted(false);
			button.setContentAreaFilled(false);
			button.setOpaque(false);
			button.setBorder(BorderFactory.createLineBorder(BORDER));
			button.addActionListener(e -> selectEvent(page));
			eventButtons[i] = button;
			eventGroup.add(button);
			eventNav.add(button);
		}
		eventNav.setOpaque(false);
		eventNav.setPreferredSize(new Dimension(204, 30));
	}

	private JToggleButton iconButton(BufferedImage icon, String tooltip, int width, int height)
	{
		JToggleButton button = new JToggleButton(new ImageIcon(icon));
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setPreferredSize(new Dimension(width, height));
		button.setBorder(BorderFactory.createLineBorder(BORDER));
		return button;
	}

	private void selectPrimary(PrimaryPage page)
	{
		if (sessionToken == null || memberRsn == null)
		{
			return;
		}
		currentPrimary = page;
		for (int i = 0; i < primaryButtons.length; i++)
		{
			boolean selected = PrimaryPage.values()[i] == page;
			primaryButtons[i].setSelected(selected);
			primaryButtons[i].setBorder(BorderFactory.createLineBorder(selected ? SELECTED_BORDER : BORDER));
		}
		eventNav.setVisible(page == PrimaryPage.EVENTS);

		switch (page)
		{
			case HOME:
				loadHome();
				break;
			case EVENTS:
				selectEvent(currentEvent == null ? EventPage.EVENT_HOME : currentEvent);
				break;
			case LFG:
				showLoading("Looking for Group");
				loadLfg();
				break;
			default:
				break;
		}
	}

	private void selectEvent(EventPage page)
	{
		if (sessionToken == null || currentPrimary != PrimaryPage.EVENTS)
		{
			return;
		}
		currentEvent = page;
		for (int i = 0; i < eventButtons.length; i++)
		{
			boolean selected = EventPage.values()[i] == page;
			eventButtons[i].setSelected(selected);
			eventButtons[i].setBorder(BorderFactory.createLineBorder(selected ? SELECTED_BORDER : BORDER));
		}

		switch (page)
		{
			case EVENT_HOME:
				showLoading("Events");
				apiClient.getDashboard(sessionToken).whenComplete((data, error) ->
					SwingUtilities.invokeLater(() -> renderEventsHome(data, error)));
				break;
			case BOTW:
				showLoading("Boss of the Week");
				apiClient.getCompetition("BOTW", sessionToken).whenComplete((data, error) ->
					SwingUtilities.invokeLater(() -> renderCompetition(EventPage.BOTW, data, error)));
				break;
			case SOTW:
				showLoading("Skill of the Week");
				apiClient.getCompetition("SOTW", sessionToken).whenComplete((data, error) ->
					SwingUtilities.invokeLater(() -> renderCompetition(EventPage.SOTW, data, error)));
				break;
			default:
				break;
		}
	}

	private void loadHome()
	{
		showLoading("Home");
		apiClient.getDashboard(sessionToken).whenComplete((dashboard, dashboardError) ->
		{
			if (dashboardError != null || dashboard == null || !dashboard.ok)
			{
				SwingUtilities.invokeLater(() -> renderHome(dashboard, null, dashboardError));
				return;
			}
			apiClient.getNotificationRules(sessionToken).whenComplete((alerts, alertsError) ->
				SwingUtilities.invokeLater(() -> renderHome(dashboard, alerts, alertsError)));
		});
	}

	private void renderHome(
		SixthDegreeApiClient.Dashboard data,
		SixthDegreeApiClient.NotificationResponse alerts,
		Throwable error)
	{
		if (currentPrimary != PrimaryPage.HOME)
		{
			return;
		}
		if (data == null || !data.ok)
		{
			renderPageError("HOME", error);
			return;
		}

		clearContent();
		addSection("HOME");
		addCenteredHtml("Welcome, <b>" + escape(memberRsn) + "</b> &nbsp; ✓", 14f, WHITE);
		addGap(9);

		JPanel status = card();
		addCardTitle(status, "CONNECTED");
		addCardText(status, "Clan <b>✓</b> &nbsp; Discord <b>✓</b> &nbsp; World <b>" + currentWorld + "</b>");
		addCard(status);

		if (data.announcement != null && !data.announcement.isBlank())
		{
			addGap(9);
			JPanel announcement = card();
			addCardTitle(announcement, "CLAN ANNOUNCEMENT");
			addCardText(announcement, escape(data.announcement));
			addCard(announcement);
		}

		addGap(12);
		addSection("AT A GLANCE");
		JPanel glance = card();
		int eventCount = data.events == null ? 0 : data.events.length;
		addCardText(glance, "Upcoming/live events: <b>" + eventCount + "</b>");
		addCardText(glance, "Active LFG posts: <b>" + data.lfg_count + "</b>");
		addCardText(glance, "BOTW: <b>" + activeLabel(data.botw) + "</b>");
		addCardText(glance, "SOTW: <b>" + activeLabel(data.sotw) + "</b>");
		addCard(glance);

		addGap(12);
		addSection("CLAN ALERTS");
		JPanel alertCard = card();
		if (alerts == null || !alerts.ok)
		{
			addCardText(alertCard, "Clan alert rules couldn’t be loaded right now.");
		}
		else
		{
			JsonObject rules = alerts.rules == null ? new JsonObject() : alerts.rules;
			addCardText(alertCard, "Loot: <b>" + onOff(nested(rules, "loot")) + "</b>");
			addCardText(alertCard, "Pets: <b>" + onOff(nested(rules, "pets")) + "</b>");
			addCardText(alertCard, "Collection logs: <b>" + onOff(nested(rules, "collection_logs")) + "</b>");
			addCardText(alertCard, "Milestones: <b>" + onOff(nested(rules, "milestones")) + "</b>");
			addCardText(alertCard, "Boss PBs: <b>" + onOff(nested(rules, "boss_pbs")) + "</b>");
		}
		addCardText(alertCard, "Your notifications: <b>" + (personalNotifications ? "On" : "Off") + "</b> • Sound: <b>" + (personalSound ? "On" : "Off") + "</b>");
		addCard(alertCard);

		addGap(12);
		JPanel actions = new JPanel(new GridLayout(1, 2, 7, 0));
		actions.setOpaque(false);
		JButton events = compactButton("Open Events");
		events.addActionListener(e -> selectPrimary(PrimaryPage.EVENTS));
		JButton lfg = compactButton("Open LFG");
		lfg.addActionListener(e -> selectPrimary(PrimaryPage.LFG));
		actions.add(events);
		actions.add(lfg);
		addCenteredFixed(actions, CARD_WIDTH, 31);
		finishContent();
	}

	private void renderEventsHome(SixthDegreeApiClient.Dashboard data, Throwable error)
	{
		if (currentPrimary != PrimaryPage.EVENTS || currentEvent != EventPage.EVENT_HOME)
		{
			return;
		}
		if (error != null || data == null || !data.ok)
		{
			renderPageError("EVENTS", error);
			return;
		}

		clearContent();
		addSection("EVENT HOME");
		if (data.events == null || data.events.length == 0)
		{
			JPanel empty = card();
			addCardStrong(empty, "No clan events listed");
			addCardText(empty, "Live and upcoming Discord events will appear here automatically.");
			addCard(empty);
		}
		else
		{
			for (SixthDegreeApiClient.EventItem event : data.events)
			{
				addCard(buildEventCard(event));
				addGap(8);
			}
		}

		addGap(10);
		addSection("COMPETITIONS");
		addCompetitionShortcut("BOSS OF THE WEEK", data.botw, EventPage.BOTW);
		addGap(7);
		addCompetitionShortcut("SKILL OF THE WEEK", data.sotw, EventPage.SOTW);
		finishContent();
	}

	private JPanel buildEventCard(SixthDegreeApiClient.EventItem event)
	{
		JPanel panel = card();
		addCardTitle(panel, event.live ? "LIVE" : "UPCOMING");
		addCardStrong(panel, escape(nullTo(event.title, "Clan Event")));
		addCardText(panel, event.live ? "Ends " + remaining(event.end_time) : formatDate(event.start_time));
		if (event.world > 0)
		{
			addCardText(panel, "World <b>" + event.world + "</b>");
		}
		if (event.location != null && !event.location.isBlank())
		{
			addCardText(panel, escape(event.location));
		}
		if (event.requirements != null && !event.requirements.isBlank())
		{
			addCardText(panel, "Requires: " + escape(event.requirements));
		}
		if (event.description != null && !event.description.isBlank())
		{
			addCardText(panel, escape(trim(event.description, 150)));
		}
		addCardText(panel, event.attending_count + " attending on Discord");
		return panel;
	}

	private void addCompetitionShortcut(
		String title,
		SixthDegreeApiClient.CompetitionResponse response,
		EventPage target)
	{
		JPanel panel = card();
		addCardTitle(panel, title);
		if (response != null && response.active != null)
		{
			addCardStrong(panel, escape(nullTo(response.active.metric, response.active.title)));
			addCardText(panel, "Ends " + remaining(response.active.end_time));
			if (response.active.you != null)
			{
				String kind = target == EventPage.BOTW ? "BOTW" : "SOTW";
				addCardText(panel, "You: <b>#" + response.active.you.rank + " • " + formatScore(response.active.you.score, kind) + "</b>");
			}
		}
		else
		{
			addCardText(panel, "Nothing live right now.");
		}
		JButton open = compactButton(target == EventPage.BOTW ? "Open BOTW" : "Open SOTW");
		open.addActionListener(e -> selectEvent(target));
		panel.add(Box.createRigidArea(new Dimension(0, 6)));
		panel.add(open);
		addCard(panel);
	}

	private void renderCompetition(
		EventPage page,
		SixthDegreeApiClient.CompetitionResponse data,
		Throwable error)
	{
		if (currentPrimary != PrimaryPage.EVENTS || currentEvent != page)
		{
			return;
		}
		String kind = page == EventPage.BOTW ? "BOTW" : "SOTW";
		String heading = page == EventPage.BOTW ? "BOSS OF THE WEEK" : "SKILL OF THE WEEK";
		if (error != null || data == null)
		{
			renderPageError(heading, error);
			return;
		}

		clearContent();
		addSection(heading);
		if (data.active == null)
		{
			JPanel empty = card();
			addCardStrong(empty, "Nothing live right now");
			addCardText(empty, page == EventPage.BOTW
				? "When BOTW starts, boss kills and the leaderboard will appear here automatically."
				: "When SOTW starts, skill XP gains and the leaderboard will appear here automatically.");
			addCard(empty);
			if (data.recent != null && data.recent.length > 0)
			{
				addGap(12);
				addSection("RECENT");
				for (SixthDegreeApiClient.RecentCompetition recent : data.recent)
				{
					JPanel item = card();
					addCardStrong(item, escape(nullTo(recent.title, recent.metric)));
					addCardText(item, formatDate(recent.end_time));
					addCard(item);
					addGap(7);
				}
			}
			finishContent();
			return;
		}

		SixthDegreeApiClient.Competition active = data.active;
		JPanel summary = card();
		addCardStrong(summary, escape(nullTo(active.metric, active.title)));
		addCardText(summary, "Ends " + remaining(active.end_time));
		if (active.prize != null && !active.prize.isBlank())
		{
			addCardText(summary, "Prize: <b>" + escape(active.prize) + "</b>");
		}
		if (active.you != null)
		{
			addCardText(summary, "Your score: <b>" + formatScore(active.you.score, kind) + "</b> (#" + active.you.rank + ")");
			if (page == EventPage.SOTW)
			{
				addCardText(summary, "Start XP: " + NUMBER.format(active.you.baseline));
				addCardText(summary, "Current XP: " + NUMBER.format(active.you.current_value));
			}
		}
		addCard(summary);

		addGap(12);
		addSection("LEADERBOARD");
		if (active.standings == null || active.standings.length == 0)
		{
			addMuted("Scores will appear as members start gaining progress.");
		}
		else
		{
			int shown = Math.min(active.standings.length, 15);
			for (int i = 0; i < shown; i++)
			{
				SixthDegreeApiClient.Standing standing = active.standings[i];
				JPanel row = card();
				addCardText(row, "<b>" + standing.rank + ". " + escape(standing.rsn) + "</b><br>" + formatScore(standing.score, kind));
				addCard(row);
				addGap(5);
			}
		}
		addGap(8);
		addMuted("Leaderboard display refreshes about every 15 minutes.");
		finishContent();
	}

	private void loadLfg()
	{
		apiClient.getLfg(sessionToken).whenComplete((data, error) ->
			SwingUtilities.invokeLater(() -> renderLfg(data, error, null)));
	}

	private void renderLfg(SixthDegreeApiClient.LfgResponse data, Throwable error, String status)
	{
		if (currentPrimary != PrimaryPage.LFG)
		{
			return;
		}
		if (error != null || data == null)
		{
			renderPageError("LOOKING FOR GROUP", error);
			return;
		}

		clearContent();
		addSection("LOOKING FOR GROUP");
		addMuted("Post what you want to do, then use clan chat to group up.");
		addGap(9);

		JPanel composer = card();
		addCardText(composer, "What are you doing?");
		JTextField noteField = new JTextField();
		noteField.setMaximumSize(new Dimension(182, 28));
		noteField.setPreferredSize(new Dimension(182, 28));
		noteField.setToolTipText("Up to 100 characters");
		composer.add(noteField);
		composer.add(Box.createRigidArea(new Dimension(0, 7)));
		addCardText(composer, "World <b>" + currentWorld + "</b> • expires after 60 minutes");
		JButton post = compactButton("Post LFG");
		post.addActionListener(e ->
		{
			String note = noteField.getText() == null ? "" : noteField.getText().trim();
			if (note.isEmpty())
			{
				noteField.requestFocusInWindow();
				return;
			}
			post.setEnabled(false);
			apiClient.postLfg(sessionToken, note, currentWorld).whenComplete((result, postError) ->
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
		composer.add(Box.createRigidArea(new Dimension(0, 7)));
		composer.add(post);
		addCard(composer);

		addGap(8);
		JPanel actions = new JPanel(new GridLayout(1, 2, 6, 0));
		actions.setOpaque(false);
		JButton refresh = compactButton("Refresh");
		refresh.addActionListener(e -> loadLfg());
		JButton remove = compactButton("Remove mine");
		remove.addActionListener(e -> apiClient.deleteMyLfg(sessionToken).whenComplete((ignored, deleteError) ->
			apiClient.getLfg(sessionToken).whenComplete((fresh, freshError) ->
				SwingUtilities.invokeLater(() -> renderLfg(fresh, freshError, deleteError == null ? "Your LFG was removed." : errorText(deleteError))))));
		actions.add(refresh);
		actions.add(remove);
		addCenteredFixed(actions, CARD_WIDTH, 30);

		if (status != null && !status.isBlank())
		{
			addGap(8);
			addMuted(escape(status));
		}

		addGap(12);
		addSection("ACTIVE");
		if (data.entries == null || data.entries.length == 0)
		{
			addMuted("Nobody is looking for a group right now.");
		}
		else
		{
			for (SixthDegreeApiClient.LfgEntry entry : data.entries)
			{
				JPanel item = card();
				boolean mine = entry.rsn != null && memberRsn != null && entry.rsn.equalsIgnoreCase(memberRsn);
				addCardStrong(item, escape(nullTo(entry.rsn, "Clan member")) + (mine ? " • YOU" : ""));
				addCardText(item, escape(nullTo(entry.note, "")));
				addCardText(item, "W<b>" + entry.world + "</b> • " + remaining(entry.expires_at) + " left");
				addCard(item);
				addGap(7);
			}
		}
		finishContent();
	}

	private void renderAccess(String message, String buttonText, Runnable buttonAction, String footer)
	{
		clearContent();
		addGap(20);
		addCenteredHtml(message, 14f, WHITE);
		if (buttonText != null && buttonAction != null)
		{
			addGap(20);
			JButton button = largeButton(buttonText);
			button.addActionListener(e -> buttonAction.run());
			content.add(centerRow(button));
		}
		if (footer != null && !footer.isBlank())
		{
			addGap(15);
			addCenteredHtml(footer, 12.5f, MUTED);
		}
		finishContent();
	}

	private void showLoading(String page)
	{
		clearContent();
		addGap(20);
		addCenteredHtml("Loading <b>" + escape(page) + "</b>…", 14f, MUTED);
		finishContent();
	}

	private void renderPageError(String page, Throwable error)
	{
		clearContent();
		addSection(page.toUpperCase(Locale.ROOT));
		JPanel panel = card();
		addCardStrong(panel, "Couldn’t load this page");
		addCardText(panel, escape(errorText(error)));
		JButton retry = compactButton("Retry");
		retry.addActionListener(e -> retryCurrent());
		panel.add(Box.createRigidArea(new Dimension(0, 7)));
		panel.add(retry);
		addCard(panel);
		finishContent();
	}

	private void retryCurrent()
	{
		if (currentPrimary == PrimaryPage.EVENTS)
		{
			selectEvent(currentEvent);
		}
		else if (currentPrimary != null)
		{
			selectPrimary(currentPrimary);
		}
	}

	private void leaveMemberMode()
	{
		memberRsn = null;
		sessionToken = null;
		currentPrimary = null;
		currentEvent = EventPage.EVENT_HOME;
		primaryGroup.clearSelection();
		eventGroup.clearSelection();
		primaryNav.setVisible(false);
		eventNav.setVisible(false);
	}

	private void clearContent()
	{
		content.removeAll();
	}

	private void finishContent()
	{
		content.revalidate();
		content.repaint();
		SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(0));
	}

	private void addSection(String text)
	{
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(WHITE);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 13.5f));
		content.add(centerRow(label));
		addGap(8);
	}

	private void addMuted(String text)
	{
		addCenteredHtml(text, 12.5f, MUTED);
	}

	private void addCenteredHtml(String html, float size, Color color)
	{
		JLabel label = new JLabel("<html><div style='text-align:center;width:" + TEXT_WIDTH + "px'>" + html + "</div></html>", SwingConstants.CENTER);
		label.setForeground(color);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, size));
		content.add(centerRow(label));
	}

	private void addGap(int height)
	{
		JPanel gap = new JPanel();
		gap.setOpaque(false);
		gap.setPreferredSize(new Dimension(1, height));
		gap.setMinimumSize(new Dimension(1, height));
		gap.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		content.add(gap);
	}

	private JPanel card()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(CARD);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER),
			BorderFactory.createEmptyBorder(9, 10, 9, 10)));
		return panel;
	}

	private void addCard(JPanel panel)
	{
		Dimension preferred = panel.getPreferredSize();
		int height = Math.max(preferred.height, 42);
		panel.setPreferredSize(new Dimension(CARD_WIDTH, height));
		panel.setMinimumSize(new Dimension(CARD_WIDTH, height));
		panel.setMaximumSize(new Dimension(CARD_WIDTH, height));
		content.add(centerRow(panel));
	}

	private void addCardTitle(JPanel panel, String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(MUTED);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 10.5f));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(label);
		panel.add(Box.createRigidArea(new Dimension(0, 5)));
	}

	private void addCardStrong(JPanel panel, String html)
	{
		JLabel label = new JLabel("<html><div style='width:180px'><b>" + html + "</b></div></html>");
		label.setForeground(WHITE);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(label);
		panel.add(Box.createRigidArea(new Dimension(0, 4)));
	}

	private void addCardText(JPanel panel, String html)
	{
		JLabel label = new JLabel("<html><div style='width:180px'>" + html + "</div></html>");
		label.setForeground(WHITE);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, 12.5f));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(label);
		panel.add(Box.createRigidArea(new Dimension(0, 3)));
	}

	private JButton compactButton(String text)
	{
		JButton button = new JButton(text);
		button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
		button.setFocusPainted(false);
		button.setAlignmentX(Component.CENTER_ALIGNMENT);
		return button;
	}

	private JButton largeButton(String text)
	{
		JButton button = new JButton(text);
		button.setFont(button.getFont().deriveFont(Font.BOLD, 13.5f));
		button.setFocusPainted(false);
		button.setPreferredSize(new Dimension(170, 38));
		button.setMinimumSize(new Dimension(170, 38));
		button.setMaximumSize(new Dimension(170, 38));
		return button;
	}

	private void addCenteredFixed(Component component, int width, int height)
	{
		component.setPreferredSize(new Dimension(width, height));
		component.setMinimumSize(new Dimension(width, height));
		component.setMaximumSize(new Dimension(width, height));
		content.add(centerRow(component));
	}

	private JPanel centerRow(Component component)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		row.setOpaque(false);
		row.add(component);
		Dimension preferred = component.getPreferredSize();
		row.setPreferredSize(new Dimension(Math.max(CARD_WIDTH, preferred.width), preferred.height));
		row.setMinimumSize(new Dimension(1, preferred.height));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
		row.setAlignmentX(Component.CENTER_ALIGNMENT);
		return row;
	}

	private JLabel titleLabel(String text, float size)
	{
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(WHITE);
		label.setFont(label.getFont().deriveFont(Font.BOLD, size));
		return label;
	}

	private static String primaryTooltip(PrimaryPage page)
	{
		switch (page)
		{
			case HOME: return "Home";
			case EVENTS: return "Events";
			case LFG: return "Looking for Group";
			default: return "Sixth Degree";
		}
	}

	private static BufferedImage buildPrimaryIcon(PrimaryPage page)
	{
		BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			switch (page)
			{
				case HOME:
					g.fillPolygon(new Polygon(new int[]{3, 12, 21}, new int[]{11, 3, 11}, 3));
					g.fillRect(6, 10, 12, 10);
					break;
				case EVENTS:
					g.drawRoundRect(3, 5, 18, 16, 3, 3);
					g.fillRect(3, 8, 18, 3);
					g.fillRect(7, 2, 2, 6);
					g.fillRect(15, 2, 2, 6);
					g.fillOval(7, 13, 3, 3);
					g.fillOval(14, 13, 3, 3);
					break;
				case LFG:
					g.fillOval(9, 3, 6, 6);
					g.fillOval(2, 6, 5, 5);
					g.fillOval(17, 6, 5, 5);
					g.fillRoundRect(7, 10, 10, 9, 6, 6);
					g.fillRoundRect(0, 12, 7, 7, 5, 5);
					g.fillRoundRect(17, 12, 7, 7, 5, 5);
					break;
				default:
					break;
			}
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	private static String activeLabel(SixthDegreeApiClient.CompetitionResponse response)
	{
		if (response == null || response.active == null)
		{
			return "None live";
		}
		return nullTo(response.active.metric, response.active.title);
	}

	private static String onOff(JsonObject rule)
	{
		return bool(rule, "enabled", false) ? "On" : "Off";
	}

	private static String formatDate(long epochSeconds)
	{
		if (epochSeconds <= 0)
		{
			return "Time TBC";
		}
		return EVENT_TIME.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()));
	}

	private static String remaining(long epochSeconds)
	{
		long seconds = Math.max(0, epochSeconds - Instant.now().getEpochSecond());
		long days = seconds / 86400;
		long hours = (seconds % 86400) / 3600;
		long minutes = (seconds % 3600) / 60;
		if (days > 0)
		{
			return days + "d " + hours + "h";
		}
		if (hours > 0)
		{
			return hours + "h " + minutes + "m";
		}
		return minutes + "m";
	}

	private static String formatScore(long score, String kind)
	{
		return ("SOTW".equalsIgnoreCase(kind) ? "+" : "") + NUMBER.format(score)
			+ ("SOTW".equalsIgnoreCase(kind) ? " XP" : " KC");
	}

	private static JsonObject nested(JsonObject object, String key)
	{
		try
		{
			return object != null && object.has(key) && object.get(key).isJsonObject()
				? object.getAsJsonObject(key)
				: new JsonObject();
		}
		catch (Exception ignored)
		{
			return new JsonObject();
		}
	}

	private static boolean bool(JsonObject object, String key, boolean fallback)
	{
		try
		{
			return object != null && object.has(key) ? object.get(key).getAsBoolean() : fallback;
		}
		catch (Exception ignored)
		{
			return fallback;
		}
	}

	private static String errorText(Throwable throwable)
	{
		Throwable current = throwable;
		while (current != null && current.getCause() != null && current.getClass().getName().contains("Completion"))
		{
			current = current.getCause();
		}
		String message = current == null ? null : current.getMessage();
		return message == null || message.isBlank() ? "Sixth Degree service is unavailable. Please retry." : message;
	}

	private static String trim(String value, int max)
	{
		if (value == null || value.length() <= max)
		{
			return value == null ? "" : value;
		}
		return value.substring(0, Math.max(0, max - 1)).trim() + "…";
	}

	private static String nullTo(String value, String fallback)
	{
		return value == null || value.isBlank() ? fallback : value;
	}

	private static String escape(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}
}
