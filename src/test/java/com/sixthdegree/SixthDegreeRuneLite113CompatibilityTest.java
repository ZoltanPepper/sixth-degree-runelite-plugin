package com.sixthdegree;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SixthDegreeRuneLite113CompatibilityTest
{
	private static final int TEST_ITEM_ID = 1234;
	private static final long ABOVE_OLD_MAX_CASH = 3_000_000_000L;

	@Test
	public void integrationApiUsesLongItemPrices() throws Exception
	{
		Method method = ItemManager.class.getMethod("getItemPrice", int.class);
		String configuredVersion = System.getProperty("sixthdegree.runeliteVersion", "latest.release");

		if ("latest.integration".equals(configuredVersion))
		{
			assertEquals(
				"RuneLite integration API must expose max-cash-safe item prices",
				long.class,
				method.getReturnType());
		}
		else
		{
			assertTrue(method.getReturnType() == int.class || method.getReturnType() == long.class);
		}
	}

	@Test
	public void lootTelemetryKeepsValuesAboveOldMaxCash() throws Exception
	{
		Assume.assumeTrue(itemPriceApiUsesLong());

		Client client = mock(Client.class);
		ItemManager itemManager = mock(ItemManager.class);
		when(client.getTickCount()).thenReturn(42);
		doAnswer(invocation -> ABOVE_OLD_MAX_CASH)
			.when(itemManager).getItemPrice(TEST_ITEM_ID);

		SixthDegreeLootService service = new SixthDegreeLootService(client, itemManager);
		service.record(List.of(new ItemStack(TEST_ITEM_ID, 2)));
		service.sealBatch();

		SixthDegreeLootService.Batch batch = service.peekBatch();
		assertNotNull(batch);
		assertEquals(6_000_000_000L, batch.valueGp);
		assertEquals(1, batch.dropCount);
	}

	@Test
	public void lootNotificationsKeepValuesAboveOldMaxCash() throws Exception
	{
		Assume.assumeTrue(itemPriceApiUsesLong());

		Client client = mock(Client.class);
		ItemManager itemManager = mock(ItemManager.class);
		ItemComposition composition = mock(ItemComposition.class);
		when(client.getTickCount()).thenReturn(84);
		doAnswer(invocation -> ABOVE_OLD_MAX_CASH)
			.when(itemManager).getItemPrice(TEST_ITEM_ID);
		when(itemManager.getItemComposition(TEST_ITEM_ID)).thenReturn(composition);
		when(composition.getName()).thenReturn("Max cash test item");

		SixthDegreeNotificationEngine engine = new SixthDegreeNotificationEngine(
			client, itemManager, mock(SixthDegreeRarityService.class));

		JsonObject root = new JsonObject();
		root.addProperty("engine_live", true);
		JsonObject loot = new JsonObject();
		loot.addProperty("enabled", true);
		loot.addProperty("minimum_value", 2_500_000_000L);
		loot.addProperty("screenshots", true);
		loot.addProperty("screenshot_minimum_value", 2_500_000_000L);
		root.add("loot", loot);
		engine.setRules(SixthDegreeNotificationRules.from(root));

		SixthDegreeNotificationEvent event = engine.onLoot(
			List.of(new ItemStack(TEST_ITEM_ID, 2)),
			"Compatibility test");

		assertNotNull(event);
		assertEquals("loot", event.type);
		assertEquals(6_000_000_000L, event.valueGp);
		assertTrue(event.screenshot);
	}

	@Test
	public void productionCodeDoesNotUseAffectedDependencyOrPacketScriptApis() throws Exception
	{
		String source = productionSource();
		assertFalse("Sixth Degree should not depend on @PluginDependency", source.contains("@PluginDependency"));
		assertFalse("Sixth Degree should not manually call client.runScript", source.contains(".runScript("));
		assertFalse("Sixth Degree should not manually create ScriptEvent packets", source.contains("ScriptEvent"));
	}

	private static boolean itemPriceApiUsesLong() throws Exception
	{
		return ItemManager.class.getMethod("getItemPrice", int.class).getReturnType() == long.class;
	}

	private static String productionSource() throws IOException
	{
		StringBuilder all = new StringBuilder();
		try (Stream<Path> files = Files.walk(Path.of("src/main/java")))
		{
			files.filter(path -> path.toString().endsWith(".java"))
				.sorted()
				.forEach(path ->
				{
					try
					{
						all.append(Files.readString(path)).append('\n');
					}
					catch (IOException e)
					{
						throw new RuntimeException(e);
					}
				});
		}
		return all.toString();
	}
}
