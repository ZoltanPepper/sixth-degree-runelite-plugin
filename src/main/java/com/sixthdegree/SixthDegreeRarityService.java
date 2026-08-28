package com.sixthdegree;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Looks up NPC drop probabilities using the DinkPlugin drop-rate dataset served by
 * the Sixth Degree API. Keeping the data behind the clan API means the plugin only
 * communicates with the service already disclosed to members.
 */
@Slf4j
@Singleton
final class SixthDegreeRarityService
{
	private static final Type RAW_TYPE = new TypeToken<Map<String, List<RawDrop>>>() { }.getType();

	private final ItemManager itemManager;
	private final Gson gson;
	private volatile Map<String, List<RareDrop>> dropsBySource = Collections.emptyMap();
	private volatile boolean loaded;

	@Inject
	SixthDegreeRarityService(ItemManager itemManager, Gson gson)
	{
		this.itemManager = itemManager;
		this.gson = gson;
	}

	boolean isLoaded()
	{
		return loaded;
	}

	synchronized boolean load(String json)
	{
		if (json == null || json.isBlank())
		{
			return false;
		}
		try
		{
			Map<String, List<RawDrop>> raw = gson.fromJson(json, RAW_TYPE);
			if (raw == null || raw.isEmpty())
			{
				return false;
			}

			Map<String, List<RareDrop>> parsed = new HashMap<>(raw.size());
			for (Map.Entry<String, List<RawDrop>> entry : raw.entrySet())
			{
				List<RareDrop> transformed = new ArrayList<>();
				if (entry.getValue() != null)
				{
					for (RawDrop drop : entry.getValue())
					{
						transformed.addAll(transform(drop));
					}
				}
				parsed.put(normaliseSource(entry.getKey()), transformed);
			}
			dropsBySource = parsed;
			loaded = true;
			return true;
		}
		catch (Exception ex)
		{
			log.warn("Could not load Sixth Degree NPC rarity data", ex);
			return false;
		}
	}

	boolean qualifies(String source, Collection<ItemStack> items, int oneInX)
	{
		if (!loaded || oneInX <= 0 || source == null || source.isBlank() || items == null)
		{
			return false;
		}
		double threshold = 1.0d / (double) oneInX;
		for (ItemStack item : items)
		{
			if (item == null || item.getId() < 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			OptionalDouble probability = getProbability(source, item.getId(), item.getQuantity());
			if (probability.isPresent() && probability.getAsDouble() <= threshold)
			{
				return true;
			}
		}
		return false;
	}

	OptionalDouble getProbability(String source, int itemId, int quantity)
	{
		List<RareDrop> drops = dropsBySource.get(normaliseSource(source));
		if (drops == null || drops.isEmpty())
		{
			return OptionalDouble.empty();
		}

		ItemComposition composition;
		try
		{
			composition = itemManager.getItemComposition(itemId);
		}
		catch (Exception ex)
		{
			composition = null;
		}

		int canonical = composition != null && composition.getNote() != -1
			? composition.getLinkedNoteId()
			: itemId;
		String itemName = composition == null ? "" : composition.getMembersName();
		Set<Integer> variants = new HashSet<>(
			ItemVariationMapping.getVariations(ItemVariationMapping.map(canonical)));

		double sum = 0.0d;
		boolean found = false;
		for (RareDrop drop : drops)
		{
			if (quantity < drop.minQuantity || quantity > drop.maxQuantity)
			{
				continue;
			}
			boolean itemMatches = drop.itemId == itemId;
			if (!itemMatches && variants.contains(drop.itemId))
			{
				try
				{
					itemMatches = itemName.equals(itemManager.getItemComposition(drop.itemId).getMembersName());
				}
				catch (Exception ignored)
				{
					itemMatches = false;
				}
			}
			if (itemMatches)
			{
				found = true;
				sum += drop.probability;
			}
		}
		return found ? OptionalDouble.of(sum) : OptionalDouble.empty();
	}

	private static Collection<RareDrop> transform(RawDrop raw)
	{
		if (raw == null || raw.denominator <= 0.0d)
		{
			return Collections.emptyList();
		}
		int rolls = raw.rolls == null ? 1 : Math.max(1, raw.rolls);
		int baseQuantity = raw.quantity == null ? 1 : Math.max(1, raw.quantity);
		int minimum = raw.minimum == null ? baseQuantity : Math.max(1, raw.minimum);
		int maximum = raw.maximum == null ? baseQuantity : Math.max(minimum, raw.maximum);
		double probability = 1.0d / raw.denominator;

		List<RareDrop> output = new ArrayList<>(rolls);
		if (rolls == 1)
		{
			output.add(new RareDrop(raw.itemId, minimum, maximum, probability));
			return output;
		}

		for (int successes = 1; successes <= rolls; successes++)
		{
			output.add(new RareDrop(
				raw.itemId,
				minimum * successes,
				maximum * successes,
				binomialProbability(probability, rolls, successes)));
		}
		return output;
	}

	private static double binomialProbability(double probability, int trials, int successes)
	{
		double combinations = 1.0d;
		for (int i = 1; i <= successes; i++)
		{
			combinations *= (double) (trials - (successes - i)) / (double) i;
		}
		return combinations
			* Math.pow(probability, successes)
			* Math.pow(1.0d - probability, trials - successes);
	}

	private static String normaliseSource(String source)
	{
		return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
	}

	private static final class RareDrop
	{
		final int itemId;
		final int minQuantity;
		final int maxQuantity;
		final double probability;

		RareDrop(int itemId, int minQuantity, int maxQuantity, double probability)
		{
			this.itemId = itemId;
			this.minQuantity = minQuantity;
			this.maxQuantity = maxQuantity;
			this.probability = probability;
		}
	}

	private static final class RawDrop
	{
		@SerializedName("i")
		int itemId;
		@SerializedName("r")
		Integer rolls;
		@SerializedName("d")
		double denominator;
		@SerializedName("q")
		Integer quantity;
		@SerializedName("m")
		Integer minimum;
		@SerializedName("n")
		Integer maximum;
	}
}
