package com.sixthdegree;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
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
	private static final int TEXT_WIDTH = 200;
	private final JPanel content = new JPanel();

	public SixthDegreePanel()
	{
		super(false);
		setLayout(new BorderLayout());
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(18, 12, 18, 12));
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
			: "<b>" + rsn + "</b> is not currently a member of Sixth Degree.";

		render(
			"SIXTH DEGREE",
			detail + "<br><br>Join our Discord to apply, meet the clan and get involved.",
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
		String message = "Welcome, <b>" + rsn + "</b>.<br><br>Your RuneScape account is in Sixth Degree. Connect Discord to unlock Events, LFG and competition tracking.";
		if (reason != null && !reason.isBlank())
		{
			message += "<br><br>" + reason;
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
			"A Discord authorisation page has been opened for <b>" + rsn + "</b>.<br><br>Complete it in your browser, then return here.",
			null,
			null,
			"Waiting for Boss Lady to verify your Discord account…"
		);
	}

	public void showCheckingAccess(String rsn)
	{
		render(
			"SIXTH DEGREE",
			"Checking Sixth Degree access for <b>" + rsn + "</b>…",
			null,
			null,
			"Clan membership and Discord access are checked separately."
		);
	}

	public void showMemberHome(String rsn)
	{
		render(
			"SIXTH DEGREE",
			"Welcome, <b>" + rsn + "</b><br><br><b>Access verified ✓</b>",
			null,
			null,
			"Clan ✓   Discord ✓<br>Events home is connected next."
		);
	}

	public void showConnectionError(String rsn, Runnable onRetry)
	{
		render(
			"CONNECTION UNAVAILABLE",
			"Sixth Degree could not verify the server connection for <b>" + rsn + "</b>.<br><br>Your in-game clan membership is still recognised.",
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

			JLabel heading = new JLabel(title, SwingConstants.CENTER);
			heading.setFont(heading.getFont().deriveFont(Font.BOLD, 17f));
			heading.setAlignmentX(Component.CENTER_ALIGNMENT);
			content.add(heading);
			content.add(Box.createRigidArea(new Dimension(0, 16)));

			JLabel body = new JLabel("<html><div style='text-align:center;width:" + TEXT_WIDTH + "px'>" + message + "</div></html>");
			body.setFont(body.getFont().deriveFont(Font.PLAIN, 14f));
			body.setAlignmentX(Component.CENTER_ALIGNMENT);
			content.add(body);

			if (buttonText != null && buttonAction != null)
			{
				content.add(Box.createRigidArea(new Dimension(0, 16)));
				JButton button = new JButton(buttonText);
				button.setFont(button.getFont().deriveFont(Font.BOLD, 13f));
				button.setAlignmentX(Component.CENTER_ALIGNMENT);
				button.addActionListener(e -> buttonAction.run());
				content.add(button);
			}

			if (footer != null && !footer.isBlank())
			{
				content.add(Box.createRigidArea(new Dimension(0, 16)));
				String footerHtml = footer.replace("\n", "<br>");
				JLabel footerLabel = new JLabel("<html><div style='text-align:center;width:" + TEXT_WIDTH + "px'>" + footerHtml + "</div></html>");
				footerLabel.setFont(footerLabel.getFont().deriveFont(Font.PLAIN, 12.5f));
				footerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
				content.add(footerLabel);
			}

			content.revalidate();
			content.repaint();
		});
	}
}
