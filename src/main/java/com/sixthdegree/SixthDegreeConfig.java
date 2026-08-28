package com.sixthdegree;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("sixthdegree")
public interface SixthDegreeConfig extends Config
{
	@ConfigItem(
		keyName = "notifications",
		name = "Notifications",
		description = "Allow Sixth Degree event and clan notifications"
	)
	default boolean notifications()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notificationSound",
		name = "Notification sound",
		description = "Play RuneLite notification sounds for Sixth Degree alerts"
	)
	default boolean notificationSound()
	{
		return true;
	}
}
