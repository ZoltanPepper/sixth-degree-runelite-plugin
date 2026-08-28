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
import javax.swing.border.Border;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

public class SixthDegreePanel extends PluginPanel
{
	private static final String DISCORD_INVITE = "https://discord.gg/6degree";
	private static final int PANEL_WIDTH = 218;
	private static final Color WHITE = new Color(238, 238, 238);
	private static final Color MUTED = new Color(180, 180, 180);
	private static final Color CARD = new Color(45, 45, 45);
	private static final Color BORDER = new Color(74, 74, 74);
	private static final Color SELECTED_BORDER = new Color(220, 220, 220);
	private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("EEE d MMM • HH:mm");
	private static final NumberFormat NUMBER = NumberFormat.getIntegerInstance(Locale.UK);

	private enum Page
	{
		HOME,
		BOTW,
		SOTW,
		LFG,
		ALERTS
	}

	private final SixthDegreeApiClient apiClient;
	private final JPanel header = new JPanel();
	private final JPanel nav = new JPanel(new GridLayout(1, 5, 5, 0));
	private final JPanel content = new JPanel();
	private final JScrollPane scrollPane;
	private final ButtonGroup navGroup = new ButtonGroup();
	private final JToggleButton[] navButtons = new JToggleButton[Page.values().length];

	private String memberRsn;
	private String sessionToken;
	private int currentWorld;
	private boolean personalNotifications;
	private boolean personalSound;
	private Page currentPage;

	public SixthDegreePanel(SixthDegreeApiClient apiClient)
	{
		super(false);
		this.apiClient = apiClient;
		setLayout(new BorderLayout());

		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBorder(BorderFactory.createEmptyBorder(16, 10, 8, 10));
		header.add(centerRow(titleLabel("SIXTH DEGREE", 17f)));
		header.add(Box.createRigidArea(new Dimension(0, 10)));

		buildNavigation();
		nav.setVisible(false);
		header.add(nav);
		add(header, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(8, 10, 18, 10));

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
		String name = rsn == null || rsn.isBlank() ? "This account" : "<b>" + escape(rsn) + "</b>";
		renderAccess(
			name + " isn’t currently a Sixth Degree clan member.<br><br>Join our Discord to apply and get involved.",
			"Join Sixth Degree",
			() -> LinkBrowser.browse(DISCORD_INVITE),
			"Events • PvM • Competitions • Learners welcome"
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
			"Requires the Sixth Degree Discord and Clan Member role."
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
			&& currentPage != null;

		memberRsn = rsn;
		sessionToken = token;
		currentWorld = world;
		personalNotifications = notifications;
		personalSound = notificationSound;
		nav.setVisible(true);

		if (!sameSession)
		{
			selectPage(Page.HOME);
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

	private void buildNavigation()
	{
		Page[] pages = Page.values();
		for (int i = 0; i < pages.length; i++)
		{
			Page page = pages[i];
			JToggleButton button = new JToggleButton(new ImageIcon(buildPageIcon(page)));
			button.setToolTipText(pageTooltip(page));
			button.setFocusPainted(false);
			button.setContentAreaFilled(false);
			button.setOpaque(false);
			button.setPreferredSize(new Dimension(36, 34));
			button.setBorder(BorderFactory.createLineBorder(BORDER));
			button.addActionListener(e -> selectPage(page));
			navButtons[i] = button;
			navGroup.add(button);
			nav.add(button);
		}
	}

	private void selectPage(Page page)
	{
		if (sessionToken == null || memberRsn == null)
		{
			return;
		}
		currentPage = page;
		for (int i = 0; i < navButtons.length; i++)
		{
			boolean selected = Page.values()[i] == page;
			navButtons[i].setSelected(selected);
			navButtons[i].setBorder(BorderFactory.createLineBorder(selected ? SELECTED_BORDER : BORDER));
		}
		showLoading(pageTitle(page));

		switch (page)
		{
			case HOME:
				apiClient.getDashboard(sessionToken).whenComplete((data, error) ->
					SwingUtilities.invokeLater(() -> renderDashboard(data, error)));
				break;
			case BOTW:
				apiClient.getCompetition("BOTW", sessionToken).whenComplete((data, error) ->
					SwingUtilities.invokeLater(() -> renderCompetition(Page.BOTW, data, error)));
				break;
			case SOTW:
				apiClient.getCompetition("SOTW", sessionToken).whenComplete((data, error) ->
					SwingUtilities.invokeLater(() -> renderCompetition(Page.SOTW, data, error)));
				break;
			case LFG:
				loadLfg();
				break;
			case ALERTS:
				apiClient.getNotificationRules(sessionToken).whenComplete((data, error) ->
					SwingUtilities.invokeLater(() -> renderAlerts(data, error)));
				break;
			default:
				break;
		}
	}

	private void renderDashboard(SixthDegreeApiClient.Dashboard data, Throwable error)
	{
		if (currentPage != Page.HOME)
		{
			return;
		}
		if (error != null || data == null || !data.ok)
		{
			renderPageError("Home", error);
			return;
		}

		clearContent();
		addCenteredHtml("Welcome, <b>" + escape(memberRsn) + "</b> &nbsp; ✓", 14f, WHITE);
		addGap(12);

		if (data.announcement != null && !data.announcement.isBlank())
		{
			JPanel announcement = card();
			addCardTitle(announcement, "ANNOUNCEMENT");
			addCardText(announcement, escape(data.announcement));
			addCard(announcement);
			addGap(10);
		}

		addSection("LIVE & UPCOMING");
		if (data.events == null || data.events.length == 0)
		{
			addMuted("No upcoming clan events are currently listed.");
		}
		else
		{
			for (SixthDegreeApiClient.EventItem event : data.events)
			{
				addCard(buildEventCard(event));
				addGap(8);
			}
		}

		addGap(8);
		addSection("COMPETITIONS");
		addCompetitionSummary("BOTW", data.botw);
		addGap(7);
		addCompetitionSummary("SOTW", data.sotw);

		addGap(12);
		JPanel lfg = card();
		addCardTitle(lfg, "LOOKING FOR GROUP");
		addCardText(lfg, "<b>" + data.lfg_count + "</b> active LFG " + (data.lfg_count == 1 ? "post" : "posts") + ".");
		JButton openLfg = compactButton("Open LFG");
		openLfg.addActionListener(e -> selectPage(Page.LFG));
		lfg.add(Box.createRigidArea(new Dimension(0, 7)));
		lfg.add(openLfg);
		addCard(lfg);

		addGap(13);
		addMuted("Clan ✓   Discord ✓   W" + currentWorld);
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
			addCardText(panel, escape(trim(event.description, 145)));
		}
		addCardText(panel, event.attending_count + " attending on Discord");
		return panel;
	}

	private void addCompetitionSummary(String kind, SixthDegreeApiClient.CompetitionResponse response)
	{
		JPanel panel = card();
		addCardTitle(panel, "BOTW".equals(kind) ? "BOSS OF THE WEEK" : "SKILL OF THE WEEK");
		if (response != null && response.active != null)
		{
			SixthDegreeApiClient.Competition active = response.active;
			addCardStrong(panel, escape(nullTo(active.metric, active.title)));
			addCardText(panel, "Ends " + remaining(active.end_time));
			if (active.you != null)
			{
				addCardText(panel, "You: <b>#" + active.you.rank + " • " + formatScore(active.you.score, kind) + "</b>");
			}
		}
		else
		{
			addCardText(panel, "No active " + kind + " right now.");
		}
		JButton open = compactButton("View " + kind);
		open.addActionListener(e -> selectPage("BOTW".equals(kind) ? Page.BOTW : Page.SOTW));
		panel.add(Box.createRigidArea(new Dimension(0, 7)));
		panel.add(open);
		addCard(panel);
	}

	private void renderCompetition(Page page, SixthDegreeApiClient.CompetitionResponse data, Throwable error)
	{
		if (currentPage != page)
		{
			return;
		}
		String kind = page == Page.BOTW ? "BOTW" : "SOTW";
		String heading = page == Page.BOTW ? "BOSS OF THE WEEK" : "SKILL OF THE WEEK";
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
			addCardText(empty, page == Page.BOTW
				? "When the next BOTW starts, boss kills and the live leaderboard will appear here automatically."
				: "When the next SOTW starts, skill XP gains and the live leaderboard will appear here automatically.");
			addCard(empty);
			if (data.recent != null && data.recent.length > 0)
			{
				addGap(14);
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
			if (page == Page.SOTW)
			{
				addCardText(summary, "Start XP: " + NUMBER.format(active.you.baseline) + "<br>Current XP: " + NUMBER.format(active.you.current_value));
			}
		}
		addCard(summary);

		addGap(14);
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
		addMuted("Leaderboard refresh target: every 15 minutes.");
		finishContent();
	}

	private void loadLfg()
	{
		apiClient.getLfg(sessionToken).whenComplete((data, error) ->
			SwingUtilities.invokeLater(() -> renderLfg(data, error, null)));
	}

	private void renderLfg(SixthDegreeApiClient.LfgResponse data, Throwable error, String status)
	{
		if (currentPage != Page.LFG)
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
		addMuted("Post what you want to do. Use clan chat to group up.");
		addGap(10);

		JPanel composer = card();
		addCardText(composer, "What are you doing?");
		JTextField noteField = new JTextField();
		noteField.setMaximumSize(new Dimension(190, 28));
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
		actions.setMaximumSize(new Dimension(PANEL_WIDTH, 30));
		JButton refresh = compactButton("Refresh");
		refresh.addActionListener(e -> loadLfg());
		JButton remove = compactButton("Remove mine");
		remove.addActionListener(e -> apiClient.deleteMyLfg(sessionToken).whenComplete((ignored, deleteError) ->
			apiClient.getLfg(sessionToken).whenComplete((fresh, freshError) ->
				SwingUtilities.invokeLater(() -> renderLfg(fresh, freshError, deleteError == null ? "Your LFG was removed." : errorText(deleteError))))));
		actions.add(refresh);
		actions.add(remove);
		content.add(actions);

		if (status != null && !status.isBlank())
		{
			addGap(8);
			addMuted(escape(status));
		}

		addGap(14);
		addSection("ACTIVE");
		if (data.entries == null || data.entries.length == 0)
		{
			addMuted("Nobody is looking for a group right now.");
		}
		else
		{
			for (SixthDegreeApiClient.LfgEntry entry : data.entries)
			{
				JPanel card = card();
				boolean mine = entry.rsn != null && memberRsn != null && entry.rsn.equalsIgnoreCase(memberRsn);
				addCardStrong(card, escape(nullTo(entry.rsn, "Clan member")) + (mine ? " • YOU" : ""));
				addCardText(card, escape(nullTo(entry.note, "")));
				addCardText(card, "W<b>" + entry.world + "</b> • " + remaining(entry.expires_at) + " left");
				addCard(card);
				addGap(7);
			}
		}
		finishContent();
	}

	private void renderAlerts(SixthDegreeApiClient.NotificationResponse data, Throwable error)
	{
		if (currentPage != Page.ALERTS)
		{
			return;
		}
		if (error != null || data == null)
		{
			renderPageError("CLAN ALERTS", error);
			return;
		}

		clearContent();
		addSection("CLAN ALERTS");
		addMuted("Clan-wide rules replace individual Discord webhooks and Dink configuration.");
		addGap(10);

		JsonObject rules = data.rules == null ? new JsonObject() : data.rules;
		boolean engineLive = bool(rules, "engine_live", false);
		JPanel engine = card();
		addCardStrong(engine, engineLive ? "Notification engine live ✓" : "Notification engine integration pending");
		addCardText(engine, "Rules are controlled centrally by Sixth Degree. Members don’t set thresholds or Discord destinations.");
		addCard(engine);

		addGap(10);
		addRuleCard("VALUABLE LOOT", nested(rules, "loot"), true);
		addGap(7);
		addRuleCard("PETS", nested(rules, "pets"), false);
		addGap(7);
		addRuleCard("COLLECTION LOGS", nested(rules, "collection_logs"), false);
		addGap(7);
		addRuleCard("MILESTONES", nested(rules, "milestones"), false);
		addGap(7);
		addRuleCard("BOSS PBS", nested(rules, "boss_pbs"), false);

		addGap(14);
		addSection("YOUR CLIENT");
		JPanel local = card();
		addCardText(local, "Notifications: <b>" + (personalNotifications ? "On" : "Off") + "</b>");
		addCardText(local, "Sound: <b>" + (personalSound ? "On" : "Off") + "</b>");
		addCardText(local, "These two harmless preferences remain in RuneLite settings.");
		addCard(local);
		finishContent();
	}

	private void addRuleCard(String title, JsonObject rule, boolean showThreshold)
	{
		JPanel panel = card();
		addCardTitle(panel, title);
		boolean enabled = bool(rule, "enabled", false);
		addCardText(panel, enabled ? "Enabled ✓" : "Disabled");
		if (showThreshold && rule != null && rule.has("minimum_value"))
		{
			try
			{
				addCardText(panel, "Minimum value: <b>" + NUMBER.format(rule.get("minimum_value").getAsLong()) + " gp</b>");
			}
			catch (Exception ignored)
			{
				// Ignore malformed centrally managed display value.
			}
		}
		if (bool(rule, "screenshots", false))
		{
			addCardText(panel, "Screenshot evidence ✓");
		}
		addCard(panel);
	}

	private void renderAccess(String message, String buttonText, Runnable buttonAction, String footer)
	{
		clearContent();
		addGap(8);
		addCenteredHtml(message, 14f, WHITE);
		if (buttonText != null && buttonAction != null)
		{
			addGap(16);
			JButton button = compactButton(buttonText);
			button.addActionListener(e -> buttonAction.run());
			content.add(centerRow(button));
		}
		if (footer != null && !footer.isBlank())
		{
			addGap(16);
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
		retry.addActionListener(e -> selectPage(currentPage));
		panel.add(Box.createRigidArea(new Dimension(0, 7)));
		panel.add(retry);
		addCard(panel);
		finishContent();
	}

	private void leaveMemberMode()
	{
		memberRsn = null;
		sessionToken = null;
		currentPage = null;
		navGroup.clearSelection();
		nav.setVisible(false);
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

	private void addSection(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(WHITE);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 13.5f));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(label);
		addGap(8);
	}

	private void addMuted(String text)
	{
		addCenteredHtml(text, 12.5f, MUTED);
	}

	private void addCenteredHtml(String html, float size, Color color)
	{
		JLabel label = new JLabel("<html><div style='text-align:center;width:198px'>" + html + "</div></html>", SwingConstants.CENTER);
		label.setForeground(color);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, size));
		content.add(centerRow(label));
	}

	private void addGap(int height)
	{
		content.add(Box.createRigidArea(new Dimension(0, height)));
	}

	private JPanel card()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(CARD);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER),
			BorderFactory.createEmptyBorder(9, 10, 9, 10)));
		panel.setAlignmentX(Component.CENTER_ALIGNMENT);
		panel.setMaximumSize(new Dimension(PANEL_WIDTH, Short.MAX_VALUE));
		return panel;
	}

	private void addCard(JPanel panel)
	{
		content.add(panel);
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
		JLabel label = new JLabel("<html><div style='width:188px'><b>" + html + "</b></div></html>");
		label.setForeground(WHITE);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(label);
		panel.add(Box.createRigidArea(new Dimension(0, 4)));
	}

	private void addCardText(JPanel panel, String html)
	{
		JLabel label = new JLabel("<html><div style='width:188px'>" + html + "</div></html>");
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

	private JPanel centerRow(Component component)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.CENTER_ALIGNMENT);
		row.add(component);
		return row;
	}

	private JLabel titleLabel(String text, float size)
	{
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(WHITE);
		label.setFont(label.getFont().deriveFont(Font.BOLD, size));
		return label;
	}

	private static String pageTitle(Page page)
	{
		switch (page)
		{
			case HOME: return "Home";
			case BOTW: return "Boss of the Week";
			case SOTW: return "Skill of the Week";
			case LFG: return "Looking for Group";
			case ALERTS: return "Clan Alerts";
			default: return "Sixth Degree";
		}
	}

	private static String pageTooltip(Page page)
	{
		return pageTitle(page);
	}

	private static BufferedImage buildPageIcon(Page page)
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
					g.setColor(new Color(0, 0, 0, 0));
					break;
				case BOTW:
					g.fillRoundRect(7, 3, 10, 10, 3, 3);
					g.drawArc(2, 4, 9, 9, 90, 180);
					g.drawArc(13, 4, 9, 9, 270, 180);
					g.fillRect(10, 12, 4, 5);
					g.fillRect(7, 17, 10, 3);
					break;
				case SOTW:
					g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
					g.drawString("XP", 3, 16);
					break;
				case LFG:
					g.fillOval(9, 3, 6, 6);
					g.fillOval(2, 6, 5, 5);
					g.fillOval(17, 6, 5, 5);
					g.fillRoundRect(7, 10, 10, 9, 6, 6);
					g.fillRoundRect(0, 12, 7, 7, 5, 5);
					g.fillRoundRect(17, 12, 7, 7, 5, 5);
					break;
				case ALERTS:
					g.fillOval(10, 2, 4, 4);
					g.fillRoundRect(6, 5, 12, 12, 8, 8);
					g.fillRect(4, 14, 16, 4);
					g.fillOval(10, 18, 4, 4);
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
