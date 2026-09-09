package com.sixthdegree;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

@Slf4j
@Singleton
final class SixthDegreeDominionHud extends Overlay
{
	private static final long HUD_REFRESH_SECONDS = 3L;
	private static final long ENTRY_BANNER_MILLIS = 4_000L;
	private static final long TOAST_MILLIS = 4_500L;
	private static final Color BACKGROUND = new Color(18, 18, 18, 218);
	private static final Color BORDER = new Color(111, 91, 53, 230);
	private static final Color GOLD = new Color(218, 177, 92);
	private static final Color TEXT = new Color(240, 236, 224);
	private static final Color MUTED = new Color(184, 179, 165);
	private static final Color RED = new Color(220, 72, 72);
	private static final Color BLUE = new Color(86, 145, 235);
	private static final Color GREEN = new Color(105, 210, 120);
	private static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	private static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12);
	private static final Font BANNER = new Font(Font.SANS_SERIF, Font.BOLD, 18);

	private final Client client;
	private final ClientThread clientThread;
	private final SixthDegreeDominionApiClient apiClient;
	private final SixthDegreeDominionWarMap warMap;
	private final OverlayManager overlayManager;
	private final AtomicBoolean refreshing = new AtomicBoolean(false);

	private ScheduledExecutorService scheduler;
	private volatile String sessionToken;
	private volatile SixthDegreeDominionApiClient.StateResponse state;
	private volatile boolean running;
	private volatile String lastRegionId;
	private volatile long bannerUntil;
	private volatile String bannerRegion = "";
	private volatile String bannerMode = "";
	private volatile String toastText = "";
	private volatile long toastUntil;

	@Inject
	SixthDegreeDominionHud(
		Client client,
		ClientThread clientThread,
		SixthDegreeDominionApiClient apiClient,
		SixthDegreeDominionWarMap warMap,
		OverlayManager overlayManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.apiClient = apiClient;
		this.warMap = warMap;
		this.overlayManager = overlayManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(Overlay.PRIORITY_HIGH);
	}

	void start()
	{
		if (running)
		{
			return;
		}
		running = true;
		overlayManager.add(this);
		scheduler = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread thread = new Thread(r, "sixth-degree-dominion-hud");
			thread.setDaemon(true);
			return thread;
		});
		scheduler.scheduleAtFixedRate(this::refreshHud, 1, HUD_REFRESH_SECONDS, TimeUnit.SECONDS);
	}

	void stop()
	{
		running = false;
		deactivate();
		if (scheduler != null)
		{
			scheduler.shutdownNow();
			scheduler = null;
		}
		overlayManager.remove(this);
	}

	void activate(String token)
	{
		if (token == null || token.isBlank())
		{
			return;
		}
		sessionToken = token;
		refreshHud();
	}

	void deactivate()
	{
		sessionToken = null;
		state = null;
		lastRegionId = null;
		bannerUntil = 0L;
		toastUntil = 0L;
		toastText = "";
		refreshing.set(false);
	}

	void onGameStateChanged(GameState gameState)
	{
		if (gameState == GameState.LOGIN_SCREEN)
		{
			state = null;
			lastRegionId = null;
			bannerUntil = 0L;
		}
		else if (gameState == GameState.LOGGED_IN && sessionToken != null)
		{
			refreshHud();
		}
	}

	void onTelemetryResult(String label, SixthDegreeDominionApiClient.TelemetryResponse response)
	{
		if (response == null || !response.ok || !response.accepted)
		{
			return;
		}
		long awarded = awardedMilli(response);
		if (awarded <= 0)
		{
			return;
		}
		String regionId = response.classification == null ? "" : safe(response.classification.region);
		String regionName = regionName(regionId);
		String routing = routing(response);
		String destination = routing.startsWith("SUPPORT") ? "War Reserve" : regionName;
		String suffix = label == null || label.isBlank() ? "Dominion activity" : label;
		toastText = "+" + influence(awarded) + " " + destination + "  —  " + suffix;
		toastUntil = System.currentTimeMillis() + TOAST_MILLIS;
		refreshHud();
	}

	private void refreshHud()
	{
		String token = sessionToken;
		if (!running || token == null || token.isBlank() || !refreshing.compareAndSet(false, true))
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			JsonObject location = captureLocation();
			if (location == null)
			{
				refreshing.set(false);
				return;
			}
			apiClient.getHud(token, location).whenComplete((response, error) ->
			{
				refreshing.set(false);
				if (!running || !token.equals(sessionToken))
				{
					return;
				}
				if (error != null)
				{
					log.debug("Dominion HUD refresh failed", error);
					return;
				}
				state = response;
				updateEntryBanner(response);
			});
		});
	}

	private JsonObject captureLocation()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getWorldLocation() == null)
		{
			return null;
		}
		WorldPoint world = player.getWorldLocation();
		boolean instance = client.isInInstancedRegion();
		WorldPoint template = world;
		if (instance)
		{
			try
			{
				LocalPoint local = player.getLocalLocation();
				WorldPoint resolved = local == null ? null : WorldPoint.fromLocalInstance(client, local);
				if (resolved != null)
				{
					template = resolved;
				}
			}
			catch (Exception ignored)
			{
				// Boss Lady will fail closed if the instance cannot be resolved.
			}
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("x", world.getX());
		payload.addProperty("y", world.getY());
		payload.addProperty("plane", world.getPlane());
		payload.addProperty("map_region_id", world.getRegionID());
		payload.addProperty("in_instance", instance);
		payload.addProperty("template_x", template.getX());
		payload.addProperty("template_y", template.getY());
		payload.addProperty("template_plane", template.getPlane());
		payload.addProperty("template_region_id", template.getRegionID());
		return payload;
	}

	private void updateEntryBanner(SixthDegreeDominionApiClient.StateResponse response)
	{
		SixthDegreeDominionApiClient.CurrentRegion current = response == null ? null : response.current_region;
		String region = current == null || !current.scoreable ? "" : safe(current.region_id);
		if (region.isEmpty())
		{
			lastRegionId = null;
			return;
		}
		if (!region.equalsIgnoreCase(lastRegionId))
		{
			lastRegionId = region;
			bannerRegion = current.name == null || current.name.isBlank() ? regionName(region) : current.name;
			bannerMode = modeLine(current.mode);
			bannerUntil = System.currentTimeMillis() + ENTRY_BANNER_MILLIS;
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		SixthDegreeDominionApiClient.StateResponse snapshot = state;
		if (!shouldRender(snapshot) || warMap.isVisible())
		{
			return null;
		}
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			int width = client.getCanvasWidth();
			int height = client.getCanvasHeight();
			drawCompactHud(g, snapshot, width);
			long now = System.currentTimeMillis();
			if (bannerUntil > now)
			{
				drawEntryBanner(g, width);
			}
			if (toastUntil > now && !toastText.isBlank())
			{
				drawToast(g, width, height);
			}
		}
		finally
		{
			g.dispose();
		}
		return new Dimension(client.getCanvasWidth(), client.getCanvasHeight());
	}

	private boolean shouldRender(SixthDegreeDominionApiClient.StateResponse snapshot)
	{
		return snapshot != null
			&& snapshot.ok
			&& snapshot.available
			&& snapshot.day != null
			&& snapshot.day.day_number > 0
			&& snapshot.current_region != null
			&& snapshot.current_region.scoreable
			&& client.getGameState() == GameState.LOGGED_IN;
	}

	private void drawCompactHud(Graphics2D g, SixthDegreeDominionApiClient.StateResponse snapshot, int canvasWidth)
	{
		SixthDegreeDominionApiClient.CurrentRegion current = snapshot.current_region;
		int x = 12;
		int y = 48;
		int width = Math.min(290, Math.max(230, canvasWidth / 5));
		int height = 89;
		g.setColor(BACKGROUND);
		g.fillRoundRect(x, y, width, height, 9, 9);
		g.setStroke(new BasicStroke(1.5f));
		g.setColor(BORDER);
		g.drawRoundRect(x, y, width, height, 9, 9);

		g.setFont(TITLE);
		g.setColor(GOLD);
		g.drawString("DOMINION — " + current.name.toUpperCase(Locale.ROOT), x + 11, y + 20);
		g.setFont(BOLD);
		g.setColor(modeColor(current.mode));
		g.drawString(modeLine(current.mode), x + 11, y + 39);

		SixthDegreeDominionApiClient.Battle battle = battle(snapshot, current.region_id);
		String score = battleScore(battle);
		g.setFont(BODY);
		if (!score.isBlank())
		{
			g.setColor(TEXT);
			g.drawString(score, x + 11, y + 58);
		}
		else
		{
			g.setColor(MUTED);
			g.drawString(snapshot.scoring_active ? "No direct battle on this front" : "Waiting for battle orders", x + 11, y + 58);
		}

		String contribution = "You: -";
		String xp = "XP: -";
		if (snapshot.player != null)
		{
			if (snapshot.player.contribution != null)
			{
				contribution = "You: " + influence(snapshot.player.contribution.total_milli) + " Influence";
			}
			if (snapshot.player.xp != null)
			{
				xp = "XP: " + snapshot.player.xp.used_units + " / " + snapshot.player.xp.cap_units;
			}
		}
		g.setColor(MUTED);
		g.drawString(contribution, x + 11, y + 77);
		FontMetrics fm = g.getFontMetrics();
		g.drawString(xp, x + width - fm.stringWidth(xp) - 11, y + 77);
	}

	private void drawEntryBanner(Graphics2D g, int canvasWidth)
	{
		String title = "ENTERING " + bannerRegion.toUpperCase(Locale.ROOT);
		String subtitle = bannerMode;
		g.setFont(BANNER);
		int titleWidth = g.getFontMetrics().stringWidth(title);
		g.setFont(BOLD);
		int subtitleWidth = g.getFontMetrics().stringWidth(subtitle);
		int width = Math.max(titleWidth, subtitleWidth) + 42;
		int x = Math.max(8, (canvasWidth - width) / 2);
		int y = 65;
		g.setColor(new Color(10, 10, 10, 232));
		g.fillRoundRect(x, y, width, 65, 12, 12);
		g.setColor(BORDER);
		g.setStroke(new BasicStroke(2f));
		g.drawRoundRect(x, y, width, 65, 12, 12);
		g.setFont(BANNER);
		g.setColor(GOLD);
		g.drawString(title, x + (width - titleWidth) / 2, y + 27);
		g.setFont(BOLD);
		g.setColor(modeColorFromLine(subtitle));
		g.drawString(subtitle, x + (width - subtitleWidth) / 2, y + 49);
	}

	private void drawToast(Graphics2D g, int canvasWidth, int canvasHeight)
	{
		g.setFont(BOLD);
		FontMetrics fm = g.getFontMetrics();
		int width = Math.min(canvasWidth - 24, fm.stringWidth(toastText) + 28);
		int x = Math.max(12, (canvasWidth - width) / 2);
		int y = Math.max(150, canvasHeight - 115);
		g.setColor(new Color(12, 12, 12, 225));
		g.fillRoundRect(x, y, width, 34, 9, 9);
		g.setColor(GOLD);
		g.drawRoundRect(x, y, width, 34, 9, 9);
		g.setColor(GREEN);
		String text = toastText;
		int textWidth = fm.stringWidth(text);
		g.drawString(text, x + Math.max(10, (width - textWidth) / 2), y + 22);
	}

	private static SixthDegreeDominionApiClient.Battle battle(
		SixthDegreeDominionApiClient.StateResponse snapshot, String regionId)
	{
		if (snapshot.publicState == null || snapshot.publicState.battles == null || regionId == null)
		{
			return null;
		}
		for (SixthDegreeDominionApiClient.Battle battle : snapshot.publicState.battles)
		{
			if (battle != null && regionId.equalsIgnoreCase(battle.region_id)
				&& "OPEN".equalsIgnoreCase(battle.status))
			{
				return battle;
			}
		}
		return null;
	}

	private static String battleScore(SixthDegreeDominionApiClient.Battle battle)
	{
		if (battle == null || battle.scores == null || battle.scores.length == 0)
		{
			return "";
		}
		String red = null;
		String blue = null;
		for (SixthDegreeDominionApiClient.BattleScore score : battle.scores)
		{
			if (score == null)
			{
				continue;
			}
			if ("RED".equalsIgnoreCase(score.code)) red = influence(score.final_milli);
			if ("BLUE".equalsIgnoreCase(score.code)) blue = influence(score.final_milli);
		}
		if (red == null && blue == null)
		{
			return "";
		}
		return "Red " + (red == null ? "0" : red) + "  •  Blue " + (blue == null ? "0" : blue);
	}

	private static String modeLine(String mode)
	{
		String value = safe(mode).toUpperCase(Locale.ROOT);
		switch (value)
		{
			case "ATTACK": return "ATTACK ORDER — FULL INFLUENCE";
			case "DEFENCE": return "DEFENCE FRONT — FULL INFLUENCE";
			case "SUPPORT": return "OFF FRONT — 25% SUPPORT";
			case "PAUSED": return "SCORING PAUSED";
			default: return "DOMINION REGION";
		}
	}

	private static Color modeColor(String mode)
	{
		String value = safe(mode).toUpperCase(Locale.ROOT);
		if ("ATTACK".equals(value)) return GOLD;
		if ("DEFENCE".equals(value)) return GREEN;
		if ("SUPPORT".equals(value)) return new Color(202, 180, 120);
		return MUTED;
	}

	private static Color modeColorFromLine(String line)
	{
		if (line.startsWith("ATTACK")) return GOLD;
		if (line.startsWith("DEFENCE")) return GREEN;
		if (line.startsWith("OFF FRONT")) return new Color(202, 180, 120);
		return MUTED;
	}

	private static long awardedMilli(SixthDegreeDominionApiClient.TelemetryResponse response)
	{
		long total = 0L;
		JsonObject result = response.result;
		if (result != null)
		{
			if (result.has("awarded_milli"))
			{
				try { total += result.get("awarded_milli").getAsLong(); }
				catch (Exception ignored) { }
			}
			JsonArray derived = array(result, "derived");
			total += sumAwarded(derived);
		}
		total += sumAwarded(response.milestones);
		return Math.max(0L, total);
	}

	private static String routing(SixthDegreeDominionApiClient.TelemetryResponse response)
	{
		JsonObject result = response.result;
		if (result != null)
		{
			if (result.has("routing"))
			{
				try { return safe(result.get("routing").getAsString()).toUpperCase(Locale.ROOT); }
				catch (Exception ignored) { }
			}
			JsonArray derived = array(result, "derived");
			if (derived != null)
			{
				for (JsonElement element : derived)
				{
					if (element != null && element.isJsonObject() && element.getAsJsonObject().has("routing"))
					{
						try
						{
							String value = safe(element.getAsJsonObject().get("routing").getAsString()).toUpperCase(Locale.ROOT);
							if (!value.isBlank()) return value;
						}
						catch (Exception ignored) { }
					}
				}
			}
		}
		return "DIRECT";
	}

	private static JsonArray array(JsonObject object, String key)
	{
		try
		{
			return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null;
		}
		catch (Exception ignored)
		{
			return null;
		}
	}

	private static long sumAwarded(JsonArray array)
	{
		if (array == null)
		{
			return 0L;
		}
		long total = 0L;
		for (JsonElement element : array)
		{
			if (element == null || !element.isJsonObject())
			{
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			if (object.has("awarded_milli"))
			{
				try { total += object.get("awarded_milli").getAsLong(); }
				catch (Exception ignored) { }
			}
		}
		return total;
	}

	private static String regionName(String regionId)
	{
		SixthDegreeDominionMapModel.TerritorySpec spec = SixthDegreeDominionMapModel.byId(regionId);
		return spec == null ? (regionId == null || regionId.isBlank() ? "Dominion" : regionId) : spec.name;
	}

	private static String influence(long milli)
	{
		double value = milli / 1000.0;
		if (Math.abs(value - Math.rint(value)) < 0.0001)
		{
			return String.format(Locale.UK, "%.0f", value);
		}
		if (Math.abs(value * 10.0 - Math.rint(value * 10.0)) < 0.0001)
		{
			return String.format(Locale.UK, "%.1f", value);
		}
		return String.format(Locale.UK, "%.2f", value);
	}

	private static String safe(String value)
	{
		return value == null ? "" : value;
	}
}