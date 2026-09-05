package com.sixthdegree;

import com.google.gson.JsonObject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SixthDegreeNotificationRulesTest
{
	@Test
	public void disabledDefaultsAreSafe()
	{
		SixthDegreeNotificationRules rules = SixthDegreeNotificationRules.from(null);
		assertFalse(rules.engineLive);
		assertTrue(rules.loot.enabled);
		assertEquals(5_000_000L, rules.loot.minimumValue);
		assertEquals(0, rules.loot.rarityOverride);
		assertEquals(99, rules.milestones.minimumLevel);
		assertTrue(rules.bossPbs.notifyPersonalBests);
		assertTrue(rules.deaths.enabled);
		assertTrue(rules.deaths.screenshots);
	}

	@Test
	public void parsesImportedClanThresholds()
	{
		JsonObject root = new JsonObject();
		root.addProperty("engine_live", true);

		JsonObject loot = new JsonObject();
		loot.addProperty("minimum_value", 1_234_567L);
		loot.addProperty("screenshot_minimum_value", 7_500_000L);
		loot.addProperty("rarity_override", 1_000);
		loot.addProperty("screenshots", true);
		root.add("loot", loot);

		JsonObject milestones = new JsonObject();
		milestones.addProperty("minimum_level", 90);
		milestones.addProperty("level_interval", 5);
		milestones.addProperty("xp_interval_millions", 10);
		root.add("milestones", milestones);

		JsonObject bosses = new JsonObject();
		bosses.addProperty("kill_count_interval", 25);
		bosses.addProperty("notify_initial", true);
		bosses.addProperty("notify_personal_bests", false);
		root.add("boss_pbs", bosses);

		JsonObject deaths = new JsonObject();
		deaths.addProperty("enabled", false);
		deaths.addProperty("screenshots", false);
		root.add("deaths", deaths);

		SixthDegreeNotificationRules rules = SixthDegreeNotificationRules.from(root);
		assertTrue(rules.engineLive);
		assertEquals(1_234_567L, rules.loot.minimumValue);
		assertEquals(7_500_000L, rules.loot.screenshotMinimumValue);
		assertEquals(1_000, rules.loot.rarityOverride);
		assertEquals(90, rules.milestones.minimumLevel);
		assertEquals(5, rules.milestones.levelInterval);
		assertEquals(10, rules.milestones.xpIntervalMillions);
		assertEquals(25, rules.bossPbs.killCountInterval);
		assertTrue(rules.bossPbs.notifyInitial);
		assertFalse(rules.bossPbs.notifyPersonalBests);
		assertFalse(rules.deaths.enabled);
		assertFalse(rules.deaths.screenshots);
	}

	@Test
	public void createsScreenshotNotificationOnlyForLocalPlayerDeath()
	{
		Client client = mock(Client.class);
		Player localPlayer = mock(Player.class);
		Actor otherActor = mock(Actor.class);
		when(client.getLocalPlayer()).thenReturn(localPlayer);

		SixthDegreeNotificationEngine engine = new SixthDegreeNotificationEngine(
			client, mock(ItemManager.class), mock(SixthDegreeRarityService.class));
		JsonObject enabled = new JsonObject();
		enabled.addProperty("engine_live", true);
		engine.setRules(SixthDegreeNotificationRules.from(enabled));

		ActorDeath localDeath = mock(ActorDeath.class);
		when(localDeath.getActor()).thenReturn(localPlayer);
		SixthDegreeNotificationEvent notification = engine.onActorDeath(localDeath);
		assertNotNull(notification);
		assertEquals("death", notification.type);
		assertTrue(notification.screenshot);

		ActorDeath otherDeath = mock(ActorDeath.class);
		when(otherDeath.getActor()).thenReturn(otherActor);
		assertNull(engine.onActorDeath(otherDeath));

		JsonObject deaths = new JsonObject();
		deaths.addProperty("enabled", false);
		enabled.add("deaths", deaths);
		engine.setRules(SixthDegreeNotificationRules.from(enabled));
		assertNull(engine.onActorDeath(localDeath));
	}
}
