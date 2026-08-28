package com.sixthdegree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

@Singleton
final class SixthDegreeLootService
{
	private final Client client;
	private final ItemManager itemManager;
	private final Deque<Batch> pending = new ArrayDeque<>();
	private final List<String> signaturesThisTick = new ArrayList<>();

	private int signatureTick = -1;
	private long bucketValueGp;
	private int bucketDropCount;

	@Inject
	SixthDegreeLootService(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/**
	 * Value one RuneLite loot event. This intentionally happens before any
	 * notification/screenshot filtering: every unique drop contributes to the fun
	 * clan GP leaderboard.
	 */
	void record(Collection<ItemStack> items)
	{
		if (items == null || items.isEmpty())
		{
			return;
		}

		int tick = client.getTickCount();
		String signature = signature(items);
		synchronized (this)
		{
			if (tick != signatureTick)
			{
				signatureTick = tick;
				signaturesThisTick.clear();
			}
			// RuneLite exposes some rewards through more than one loot event. Treat the
			// same item payload in the same game tick as one drop to avoid double-counting.
			if (signaturesThisTick.contains(signature))
			{
				return;
			}
			signaturesThisTick.add(signature);
		}

		long total = 0L;
		for (ItemStack item : items)
		{
			if (item == null || item.getId() < 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			long unitPrice = price(item.getId());
			if (unitPrice > 0)
			{
				total += unitPrice * (long) item.getQuantity();
			}
		}

		synchronized (this)
		{
			bucketValueGp += Math.max(0L, total);
			bucketDropCount++;
		}
	}

	private long price(int itemId)
	{
		try
		{
			int price = itemManager.getItemPrice(itemId);
			if (price > 0)
			{
				return price;
			}
			ItemComposition composition = itemManager.getItemComposition(itemId);
			return composition == null ? 0L : Math.max(0, composition.getPrice());
		}
		catch (Exception ignored)
		{
			return 0L;
		}
	}

	synchronized void sealBatch()
	{
		if (bucketDropCount <= 0)
		{
			return;
		}
		pending.addLast(new Batch(
			UUID.randomUUID().toString(),
			bucketValueGp,
			bucketDropCount,
			System.currentTimeMillis() / 1000L));
		bucketValueGp = 0L;
		bucketDropCount = 0;

		// If Boss Lady is unreachable for a very long time, keep memory bounded while
		// still retaining a generous amount of unsent gameplay.
		while (pending.size() > 120)
		{
			Batch first = pending.removeFirst();
			Batch second = pending.pollFirst();
			if (second == null)
			{
				pending.addFirst(first);
				break;
			}
			pending.addFirst(new Batch(
				second.id,
				first.valueGp + second.valueGp,
				first.dropCount + second.dropCount,
				second.recordedAt));
		}
	}

	synchronized Batch peekBatch()
	{
		return pending.peekFirst();
	}

	synchronized void acknowledge(String batchId)
	{
		Batch current = pending.peekFirst();
		if (current != null && current.id.equals(batchId))
		{
			pending.removeFirst();
		}
	}

	synchronized boolean hasPending()
	{
		return !pending.isEmpty() || bucketDropCount > 0;
	}

	private static String signature(Collection<ItemStack> items)
	{
		List<String> values = new ArrayList<>(items.size());
		for (ItemStack item : items)
		{
			if (item != null)
			{
				values.add(item.getId() + "x" + item.getQuantity());
			}
		}
		Collections.sort(values);
		return String.join(",", values);
	}

	static final class Batch
	{
		final String id;
		final long valueGp;
		final int dropCount;
		final long recordedAt;

		Batch(String id, long valueGp, int dropCount, long recordedAt)
		{
			this.id = id;
			this.valueGp = valueGp;
			this.dropCount = dropCount;
			this.recordedAt = recordedAt;
		}
	}
}
