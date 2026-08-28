package com.sixthdegree;

import java.util.UUID;

final class SixthDegreeNotificationEvent
{
	final String eventId;
	final String type;
	final String title;
	final String detail;
	final String source;
	final long valueGp;
	final long occurredAt;
	final boolean screenshot;
	final int itemId;
	final boolean rarityTriggered;

	private SixthDegreeNotificationEvent(
		String eventId,
		String type,
		String title,
		String detail,
		String source,
		long valueGp,
		long occurredAt,
		boolean screenshot,
		int itemId,
		boolean rarityTriggered)
	{
		this.eventId = eventId;
		this.type = type;
		this.title = title;
		this.detail = detail;
		this.source = source;
		this.valueGp = Math.max(0L, valueGp);
		this.occurredAt = occurredAt;
		this.screenshot = screenshot;
		this.itemId = Math.max(0, itemId);
		this.rarityTriggered = rarityTriggered;
	}

	static SixthDegreeNotificationEvent of(
		String type,
		String title,
		String detail,
		String source,
		long valueGp,
		boolean screenshot)
	{
		return new SixthDegreeNotificationEvent(
			UUID.randomUUID().toString(),
			type,
			title,
			detail,
			source,
			valueGp,
			System.currentTimeMillis() / 1000L,
			screenshot,
			0,
			false);
	}

	static SixthDegreeNotificationEvent loot(
		String title,
		String detail,
		String source,
		long valueGp,
		boolean screenshot,
		int itemId,
		boolean rarityTriggered)
	{
		return new SixthDegreeNotificationEvent(
			UUID.randomUUID().toString(),
			"loot",
			title,
			detail,
			source,
			valueGp,
			System.currentTimeMillis() / 1000L,
			screenshot,
			itemId,
			rarityTriggered);
	}
}
