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
			true,
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
			true,
			"Events • PvM • Competitions • Learners welcome"
		);
	}

	public void showDiscordLinkRequired(String rsn)
	{
		render(
			"SIXTH DEGREE",
			"Welcome, " + rsn + ". Your RuneScape account is in Sixth Degree. Connect Discord to unlock Events, LFG and competition tracking.",
			false,
			"Discord linking will be enabled when the Sixth Degree HTTPS API is connected."
		);
	}

	private void render(String title, String message, boolean showJoinButton, String footer)
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

			if (showJoinButton)
			{
				content.add(Box.createRigidArea(new Dimension(0, 14)));
				JButton join = new JButton("Join Sixth Degree");
				join.setAlignmentX(Component.CENTER_ALIGNMENT);
				join.addActionListener(e -> LinkBrowser.browse(DISCORD_INVITE));
				content.add(join);
			}

			if (footer != null && !footer.isBlank())
			{
				content.add(Box.createRigidArea(new Dimension(0, 14)));
				JLabel footerLabel = new JLabel("<html><div style='text-align:center;width:190px'><small>" + footer + "</small></div></html>");
				footerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
				content.add(footerLabel);
			}

			content.revalidate();
			content.repaint();
		});
	}
}
