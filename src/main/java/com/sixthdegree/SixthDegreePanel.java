package com.sixthdegree;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

public class SixthDegreePanel extends PluginPanel
{
	private static final String DISCORD_INVITE = "https://discord.gg/6degree";
	private final JPanel content = new JPanel();

	public SixthDegreePanel()
	{
		super(false);
		setLayout(new BorderLayout());
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(16, 12, 16, 12));
		add(content, BorderLayout.NORTH);
		showLoggedOut();
	}

	public void showLoggedOut()
	{
		render(
			"SIXTH DEGREE",
			"Log in to Old School RuneScape so the plugin can check your clan membership.",
			null,
			null,
			null
		);
	}

	public void showRecruitment(String rsn)
	{
		String detail = rsn == null || rsn.isBlank()
			? "This account is not currently a member of Sixth Degree."
			: rsn + " is not currently a member of Sixth Degree.";

		render(
			"SIXTH DEGREE",
			detail + " Join our Discord to apply, meet the clan and get involved.",
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
		String message = "Welcome, " + rsn + ". Your RuneScape account is in Sixth Degree. Connect Discord to unlock Events, LFG and competition tracking.";
		if (reason != null && !reason.isBlank())
		{
			message += " " + reason;
		}

		render(
			"SIXTH DEGREE",
			message,
			"Connect Discord",
			onConnect,
			"Requires Sixth Degree Discord membership and the Clan Member role."
		);
	}

	public void showLinking(String rsn)
	{
		render(
			"CONNECTING DISCORD",
			"A Discord authorisation page has been opened for " + rsn + ". Complete it in your browser, then return here.",
			null,
			null,
			"Waiting for Boss Lady to verify your Discord account…"
		);
	}

	public void showCheckingAccess(String rsn)
	{
		render(
			"SIXTH DEGREE",
			"Checking Sixth Degree access for " + rsn + "…",
			null,
			null,
			"Clan membership and Discord access are checked separately."
		);
	}

	public void showMemberHome(String rsn)
	{
		render(
			"SIXTH DEGREE",
			"Welcome, " + rsn + ".",
			null,
			null,
			"Clan ✓   Discord ✓\nEvents home is connected next."
		);
	}

	public void showConnectionError(String rsn, Runnable onRetry)
	{
		render(
			"CONNECTION UNAVAILABLE",
			"Sixth Degree could not verify the server connection for " + rsn + ". Your in-game clan membership is still recognised.",
			"Retry",
			onRetry,
			"No access data has been changed."
		);
	}

	private void render(String title, String message, String buttonText, Runnable buttonAction, String footer)
	{
		SwingUtilities.invokeLater(() ->
		{
			content.removeAll();

			JLabel heading = new JLabel("<html><b>" + title + "</b></html>", SwingConstants.CENTER);
			heading.setAlignmentX(Component.CENTER_ALIGNMENT);
			content.add(heading);
			content.add(Box.createRigidArea(new Dimension(0, 14)));

			JLabel body = new JLabel("<html><div style='text-align:center;width:190px'>" + message + "</div></html>");
			body.setAlignmentX(Component.CENTER_ALIGNMENT);
			content.add(body);

			if (buttonText != null && buttonAction != null)
			{
				content.add(Box.createRigidArea(new Dimension(0, 14)));
				JButton button = new JButton(buttonText);
				button.setAlignmentX(Component.CENTER_ALIGNMENT);
				button.addActionListener(e -> buttonAction.run());
				content.add(button);
			}

			if (footer != null && !footer.isBlank())
			{
				content.add(Box.createRigidArea(new Dimension(0, 14)));
				String footerHtml = footer.replace("\n", "<br>");
				JLabel footerLabel = new JLabel("<html><div style='text-align:center;width:190px'><small>" + footerHtml + "</small></div></html>");
				footerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
				content.add(footerLabel);
			}

			content.revalidate();
			content.repaint();
		});
	}
}
