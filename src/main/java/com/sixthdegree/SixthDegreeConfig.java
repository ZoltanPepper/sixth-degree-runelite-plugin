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
		name = "Notification sounds",
		description = "Play Sixth Degree sounds for supported alerts"
	)
	default boolean notificationSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "deathScreenshots",
		name = "Death screenshots",
		description = "Include a RuneLite screenshot with your Discord death notification"
	)
	default boolean deathScreenshots()
	{
		return true;
	}
}
