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
	final String thumbnailUrl;

	private SixthDegreeNotificationEvent(
		String eventId,
		String type,
		String title,
		String detail,
		String source,
		long valueGp,
		long occurredAt,
		boolean screenshot,
		String thumbnailUrl)
	{
		this.eventId = eventId;
		this.type = type;
		this.title = title;
		this.detail = detail;
		this.source = source;
		this.valueGp = Math.max(0L, valueGp);
		this.occurredAt = occurredAt;
		this.screenshot = screenshot;
		this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
	}

	static SixthDegreeNotificationEvent of(
		String type,
		String title,
		String detail,
		String source,
		long valueGp,
		boolean screenshot)
	{
		return of(type, title, detail, source, valueGp, screenshot, "");
	}

	static SixthDegreeNotificationEvent of(
		String type,
		String title,
		String detail,
		String source,
		long valueGp,
		boolean screenshot,
		String thumbnailUrl)
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
			thumbnailUrl);
	}
}
