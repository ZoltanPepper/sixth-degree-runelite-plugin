package com.sixthdegree;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class SixthDegreeNotificationRules
{
	static final SixthDegreeNotificationRules DISABLED = from(null);

	final boolean engineLive;
	final LootRule loot;
	final BasicRule pets;
	final BasicRule collectionLogs;
	final BasicRule deaths;
	final MilestoneRule milestones;
	final BossRule bossPbs;

	private SixthDegreeNotificationRules(
		boolean engineLive,
		LootRule loot,
		BasicRule pets,
		BasicRule collectionLogs,
		BasicRule deaths,
		MilestoneRule milestones,
		BossRule bossPbs)
	{
		this.engineLive = engineLive;
		this.loot = loot;
		this.pets = pets;
		this.collectionLogs = collectionLogs;
		this.deaths = deaths;
		this.milestones = milestones;
		this.bossPbs = bossPbs;
	}

	static SixthDegreeNotificationRules from(JsonObject root)
	{
		boolean live = bool(root, "engine_live", false);
		JsonObject loot = object(root, "loot");
		JsonObject pets = object(root, "pets");
		JsonObject clogs = object(root, "collection_logs");
		JsonObject deaths = object(root, "deaths");
		JsonObject milestones = object(root, "milestones");
		JsonObject boss = object(root, "boss_pbs");

		long lootMinimum = longValue(loot, "minimum_value", 5_000_000L);
		return new SixthDegreeNotificationRules(
			live,
			new LootRule(
				bool(loot, "enabled", true),
				bool(loot, "screenshots", true),
				Math.max(0L, lootMinimum),
				Math.max(0L, longValue(loot, "screenshot_minimum_value", lootMinimum)),
				Math.max(0, integer(loot, "rarity_override", 0))),
			new BasicRule(bool(pets, "enabled", true), bool(pets, "screenshots", true)),
			new BasicRule(bool(clogs, "enabled", true), bool(clogs, "screenshots", true)),
			new BasicRule(bool(deaths, "enabled", true), bool(deaths, "screenshots", true)),
			new MilestoneRule(
				bool(milestones, "enabled", true),
				bool(milestones, "screenshots", true),
				Math.max(1, integer(milestones, "minimum_level", 99)),
				Math.max(1, integer(milestones, "level_interval", 1)),
				Math.max(0, integer(milestones, "level_interval_override", 0)),
				Math.max(1, integer(milestones, "screenshot_minimum_level", 99)),
				Math.max(0, integer(milestones, "xp_interval_millions", 0))),
			new BossRule(
				bool(boss, "enabled", true),
				bool(boss, "screenshots", true),
				bool(boss, "notify_personal_bests", true),
				Math.max(0, integer(boss, "kill_count_interval", 0)),
				bool(boss, "notify_initial", false)));
	}

	private static JsonObject object(JsonObject root, String key)
	{
		if (root == null)
		{
			return null;
		}
		JsonElement value = root.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
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

	private static int integer(JsonObject object, String key, int fallback)
	{
		try
		{
			return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
		}
		catch (Exception ignored)
		{
			return fallback;
		}
	}

	private static long longValue(JsonObject object, String key, long fallback)
	{
		try
		{
			return object != null && object.has(key) ? object.get(key).getAsLong() : fallback;
		}
		catch (Exception ignored)
		{
			return fallback;
		}
	}

	static class BasicRule
	{
		final boolean enabled;
		final boolean screenshots;

		BasicRule(boolean enabled, boolean screenshots)
		{
			this.enabled = enabled;
			this.screenshots = screenshots;
		}
	}

	static final class LootRule extends BasicRule
	{
		final long minimumValue;
		final long screenshotMinimumValue;
		final int rarityOverride;

		LootRule(
			boolean enabled,
			boolean screenshots,
			long minimumValue,
			long screenshotMinimumValue,
			int rarityOverride)
		{
			super(enabled, screenshots);
			this.minimumValue = minimumValue;
			this.screenshotMinimumValue = screenshotMinimumValue;
			this.rarityOverride = rarityOverride;
		}
	}

	static final class MilestoneRule extends BasicRule
	{
		final int minimumLevel;
		final int levelInterval;
		final int levelIntervalOverride;
		final int screenshotMinimumLevel;
		final int xpIntervalMillions;

		MilestoneRule(
			boolean enabled,
			boolean screenshots,
			int minimumLevel,
			int levelInterval,
			int levelIntervalOverride,
			int screenshotMinimumLevel,
			int xpIntervalMillions)
		{
			super(enabled, screenshots);
			this.minimumLevel = minimumLevel;
			this.levelInterval = levelInterval;
			this.levelIntervalOverride = levelIntervalOverride;
			this.screenshotMinimumLevel = screenshotMinimumLevel;
			this.xpIntervalMillions = xpIntervalMillions;
		}
	}

	static final class BossRule extends BasicRule
	{
		final boolean notifyPersonalBests;
		final int killCountInterval;
		final boolean notifyInitial;

		BossRule(
			boolean enabled,
			boolean screenshots,
			boolean notifyPersonalBests,
			int killCountInterval,
			boolean notifyInitial)
		{
			super(enabled, screenshots);
			this.notifyPersonalBests = notifyPersonalBests;
			this.killCountInterval = killCountInterval;
			this.notifyInitial = notifyInitial;
		}
	}
}
