package com.sixthdegree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.util.Text;

@Singleton
final class SixthDegreeNotificationEngine
{
	private static final String COLLECTION_LOG_PREFIX = "New item added to your collection log:";
	private static final Pattern BOSS_COUNT = Pattern.compile(
		"Your (.+?)\\s(?:kill|chest|completion|harvest|success|opened)\\s?count is: ?([\\d,]+)\\b",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern BOSS_COUNT_SECONDARY = Pattern.compile(
		"Your (?:completed|subdued) (.+?) count is: ([\\d,]+)\\b",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern BOSS_TIME = Pattern.compile(
		"(?:Duration|time|Subdued in):? ([\\d:]+(?:\\.\\d+)?)",
		Pattern.CASE_INSENSITIVE);
	private static final int BOSS_CONTEXT_TICKS = 10;

	private final Client client;
	private final ItemManager itemManager;
	private final SixthDegreeRarityService rarityService;
	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> experience = new EnumMap<>(Skill.class);
	private final List<String> lootSignaturesThisTick = new ArrayList<>();

	private volatile SixthDegreeNotificationRules rules = SixthDegreeNotificationRules.DISABLED;
	private int lootSignatureTick = -1;
	private String lastBoss;
	private int lastBossCount;
	private int lastBossTick = -1000;

	@Inject
	SixthDegreeNotificationEngine(
		Client client,
		ItemManager itemManager,
		SixthDegreeRarityService rarityService)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.rarityService = rarityService;
	}

	void setRules(SixthDegreeNotificationRules rules)
	{
		this.rules = rules == null ? SixthDegreeNotificationRules.DISABLED : rules;
	}

	SixthDegreeNotificationRules getRules()
	{
		return rules;
	}

	void reset()
	{
		levels.clear();
		experience.clear();
		lootSignaturesThisTick.clear();
		lootSignatureTick = -1;
		lastBoss = null;
		lastBossCount = 0;
		lastBossTick = -1000;
	}

	SixthDegreeNotificationEvent onLoot(Collection<ItemStack> items, String source)
	{
		SixthDegreeNotificationRules current = rules;
		if (!current.engineLive || !current.loot.enabled || items == null || items.isEmpty())
		{
			return null;
		}

		List<ItemStack> reduced = reduceItems(items);
		if (reduced.isEmpty() || duplicateLootThisTick(reduced, source))
		{
			return null;
		}

		List<DropPart> parts = new ArrayList<>();
		long total = 0L;
		for (ItemStack stack : reduced)
		{
			long unit = price(stack.getId());
			long value = Math.max(0L, unit * (long) stack.getQuantity());
			total += value;
			parts.add(new DropPart(
				stack.getId(), itemName(stack.getId()), stack.getQuantity(), value));
		}

		String cleanSource = safeSource(source, "Loot");
		boolean valueTriggered = total >= current.loot.minimumValue;
		boolean rarityTriggered = !valueTriggered
			&& current.loot.rarityOverride > 0
			&& rarityService.qualifies(cleanSource, reduced, current.loot.rarityOverride);
		if (!valueTriggered && !rarityTriggered)
		{
			return null;
		}

		parts.sort(Comparator.comparingLong((DropPart part) -> part.value).reversed());
		String title;
		if (parts.size() == 1)
		{
			title = parts.get(0).name;
		}
		else
		{
			title = rarityTriggered ? "Rare loot" : "Valuable loot";
		}

		String detail = parts.stream()
			.limit(8)
			.map(part -> part.quantity + " x " + part.name + " — " + formatGp(part.value))
			.collect(Collectors.joining("\n"));

		int thumbnailItemId = parts.isEmpty() ? 0 : parts.get(0).itemId;
		return SixthDegreeNotificationEvent.loot(
			title,
			detail,
			cleanSource,
			total,
			current.loot.screenshots && total >= current.loot.screenshotMinimumValue,
			thumbnailItemId);
	}

	List<SixthDegreeNotificationEvent> onGameMessage(String rawMessage)
	{
		List<SixthDegreeNotificationEvent> events = new ArrayList<>(2);
		SixthDegreeNotificationRules current = rules;
		if (!current.engineLive || rawMessage == null || rawMessage.isBlank())
		{
			return events;
		}

		String message = Text.removeTags(rawMessage).trim();
		String lower = message.toLowerCase(Locale.ROOT);

		if (current.pets.enabled && isPetMessage(lower))
		{
			events.add(SixthDegreeNotificationEvent.of(
				"pet",
				"New pet!",
				message,
				"Pet drop",
				0L,
				current.pets.screenshots));
		}

		if (current.collectionLogs.enabled && message.startsWith(COLLECTION_LOG_PREFIX))
		{
			String item = message.substring(COLLECTION_LOG_PREFIX.length()).trim();
			events.add(SixthDegreeNotificationEvent.of(
				"collection_log",
				item.isBlank() ? "New collection log item" : item,
				"New item added to the collection log.",
				"Collection Log",
				0L,
				current.collectionLogs.screenshots));
		}

		if (current.bossPbs.enabled)
		{
			BossCount count = parseBossCount(message);
			if (count != null)
			{
				lastBoss = count.boss;
				lastBossCount = count.count;
				lastBossTick = client.getTickCount();
				if ((count.count == 1 && current.bossPbs.notifyInitial)
					|| (current.bossPbs.killCountInterval > 0
						&& count.count % current.bossPbs.killCountInterval == 0))
				{
					events.add(SixthDegreeNotificationEvent.of(
						"boss_pb",
						count.boss + " milestone",
						"Kill count: " + count.count,
						count.boss,
						0L,
						current.bossPbs.screenshots));
				}
			}

			if (current.bossPbs.notifyPersonalBests
				&& lower.contains("new personal best")
				&& BOSS_TIME.matcher(message).find())
			{
				String boss = bossContext();
				events.add(SixthDegreeNotificationEvent.of(
					"boss_pb",
					boss + " — new personal best",
					message,
					boss,
					0L,
					current.bossPbs.screenshots));
			}
		}

		return events;
	}

	List<SixthDegreeNotificationEvent> onStatChanged(StatChanged event)
	{
		List<SixthDegreeNotificationEvent> events = new ArrayList<>(1);
		if (event == null)
		{
			return events;
		}

		Skill skill = event.getSkill();
		int level = event.getLevel();
		int xp = event.getXp();
		Integer previousLevel = levels.put(skill, level);
		Integer previousXp = experience.put(skill, xp);

		SixthDegreeNotificationRules current = rules;
		if (!current.engineLive || !current.milestones.enabled || previousLevel == null || previousXp == null)
		{
			return events;
		}
		if (level < previousLevel || xp < previousXp)
		{
			reset();
			return events;
		}

		if (level > previousLevel && shouldNotifyLevel(previousLevel, level, current.milestones))
		{
			events.add(SixthDegreeNotificationEvent.of(
				"milestone",
				skill.getName() + " level " + level,
				"Reached level " + level + " " + skill.getName() + ".",
				skill.getName(),
				0L,
				current.milestones.screenshots && level >= current.milestones.screenshotMinimumLevel));
			return events;
		}

		int millions = current.milestones.xpIntervalMillions;
		if (millions > 0 && level >= 99 && xp > previousXp)
		{
			long interval = millions * 1_000_000L;
			long oldBucket = previousXp / interval;
			long newBucket = xp / interval;
			if (newBucket > oldBucket)
			{
				long milestone = newBucket * interval;
				events.add(SixthDegreeNotificationEvent.of(
					"milestone",
					skill.getName() + " XP milestone",
					"Reached " + formatNumber(milestone) + " " + skill.getName() + " XP.",
					skill.getName(),
					0L,
					current.milestones.screenshots));
			}
		}
		return events;
	}

	private static List<ItemStack> reduceItems(Collection<ItemStack> items)
	{
		Map<Integer, Integer> quantities = new LinkedHashMap<>();
		for (ItemStack item : items)
		{
			if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
			{
				quantities.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		List<ItemStack> reduced = new ArrayList<>(quantities.size());
		for (Map.Entry<Integer, Integer> entry : quantities.entrySet())
		{
			reduced.add(new ItemStack(entry.getKey(), entry.getValue()));
		}
		return reduced;
	}

	private boolean duplicateLootThisTick(Collection<ItemStack> items, String source)
	{
		int tick = client.getTickCount();
		if (tick != lootSignatureTick)
		{
			lootSignatureTick = tick;
			lootSignaturesThisTick.clear();
		}
		String signature = safeSource(source, "Loot") + "|" + items.stream()
			.filter(item -> item != null)
			.map(item -> item.getId() + "x" + item.getQuantity())
			.sorted()
			.collect(Collectors.joining(","));
		if (lootSignaturesThisTick.contains(signature))
		{
			return true;
		}
		lootSignaturesThisTick.add(signature);
		return false;
	}

	private long price(int itemId)
	{
		try
		{
			int ge = itemManager.getItemPrice(itemId);
			if (ge > 0)
			{
				return ge;
			}
			ItemComposition composition = itemManager.getItemComposition(itemId);
			return composition == null ? 0L : Math.max(0, composition.getPrice());
		}
		catch (Exception ignored)
		{
			return 0L;
		}
	}

	private String itemName(int itemId)
	{
		try
		{
			ItemComposition composition = itemManager.getItemComposition(itemId);
			String name = composition == null ? null : composition.getName();
			return name == null || name.isBlank() ? "Item " + itemId : name;
		}
		catch (Exception ignored)
		{
			return "Item " + itemId;
		}
	}

	private static boolean isPetMessage(String lower)
	{
		return (lower.contains("funny feeling") && lower.contains("followed"))
			|| lower.contains("weird sneaking into your backpack");
	}

	private BossCount parseBossCount(String message)
	{
		Matcher matcher = BOSS_COUNT.matcher(message);
		if (!matcher.find())
		{
			matcher = BOSS_COUNT_SECONDARY.matcher(message);
			if (!matcher.find())
			{
				return null;
			}
		}
		try
		{
			String boss = matcher.group(1).trim();
			int count = Integer.parseInt(matcher.group(2).replace(",", ""));
			return boss.isBlank() ? null : new BossCount(boss, count);
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	private String bossContext()
	{
		if (lastBoss != null && client.getTickCount() - lastBossTick <= BOSS_CONTEXT_TICKS)
		{
			return lastBoss;
		}
		return "Boss";
	}

	private static boolean shouldNotifyLevel(
		int previous,
		int current,
		SixthDegreeNotificationRules.MilestoneRule rule)
	{
		if (current < rule.minimumLevel)
		{
			return false;
		}
		if (current == 99)
		{
			return true;
		}
		if (rule.levelIntervalOverride > 0 && current >= rule.levelIntervalOverride)
		{
			return true;
		}
		if (rule.levelInterval <= 1)
		{
			return true;
		}
		int remainder = current % rule.levelInterval;
		return remainder == 0 || current - remainder > previous;
	}

	private static String safeSource(String source, String fallback)
	{
		return source == null || source.isBlank() ? fallback : Text.removeTags(source).trim();
	}

	private static String formatGp(long value)
	{
		return String.format(Locale.UK, "%,d gp", value);
	}

	private static String formatNumber(long value)
	{
		return String.format(Locale.UK, "%,d", value);
	}

	private static final class DropPart
	{
		final int itemId;
		final String name;
		final int quantity;
		final long value;

		DropPart(int itemId, String name, int quantity, long value)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
			this.value = value;
		}
	}

	private static final class BossCount
	{
		final String boss;
		final int count;

		BossCount(String boss, int count)
		{
			this.boss = boss;
			this.count = count;
		}
	}
}
