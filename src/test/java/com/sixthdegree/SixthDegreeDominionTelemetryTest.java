package com.sixthdegree;

import org.junit.Test;

import static org.junit.Assert.*;

public class SixthDegreeDominionTelemetryTest
{
	@Test
	public void parsesBossKillCount()
	{
		SixthDegreeDominionTelemetry.BossCount result =
			SixthDegreeDominionTelemetry.parseBossCount("Your Vorkath kill count is: 1,234");
		assertNotNull(result);
		assertEquals("Vorkath", result.source);
		assertEquals(1234, result.count);
	}

	@Test
	public void parsesCompletedRaidCount()
	{
		SixthDegreeDominionTelemetry.BossCount result =
			SixthDegreeDominionTelemetry.parseBossCount("Your completed Tombs of Amascut: Expert Mode count is: 42");
		assertNotNull(result);
		assertEquals("Tombs of Amascut: Expert Mode", result.source);
		assertEquals(42, result.count);
	}

	@Test
	public void recognisesPets()
	{
		assertTrue(SixthDegreeDominionTelemetry.isPetMessage(
			"you have a funny feeling like you're being followed"));
		assertTrue(SixthDegreeDominionTelemetry.isPetMessage(
			"you feel something weird sneaking into your backpack"));
		assertFalse(SixthDegreeDominionTelemetry.isPetMessage("you receive a drop"));
	}

	@Test
	public void infersClueTier()
	{
		assertEquals("elite", SixthDegreeDominionTelemetry.clueTier("Clue Scroll (Elite)"));
		assertEquals("master", SixthDegreeDominionTelemetry.clueTier("Reward casket (master)"));
		assertNull(SixthDegreeDominionTelemetry.clueTier("Barrows"));
	}

	@Test
	public void normalisesPunctuationForServerBindings()
	{
		assertEquals("k ril tsutsaroth", SixthDegreeDominionTelemetry.normalise("K'ril Tsutsaroth"));
		assertEquals("calvar ion", SixthDegreeDominionTelemetry.normalise("Calvar'ion"));
		assertEquals("tombs of amascut expert mode",
			SixthDegreeDominionTelemetry.normalise("Tombs of Amascut: Expert Mode"));
	}
}
