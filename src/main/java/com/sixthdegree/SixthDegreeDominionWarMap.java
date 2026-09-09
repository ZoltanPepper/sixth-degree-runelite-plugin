package com.sixthdegree;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

@Slf4j
@Singleton
final class SixthDegreeDominionWarMap extends Overlay
{
	private static final long STATE_REFRESH_SECONDS = 10L;
	private static final Color RED = new Color(180, 38, 38);
	private static final Color BLUE = new Color(35, 92, 180);
	private static final Color NEUTRAL = new Color(95, 91, 82);
	private static final Color GOLD = new Color(218, 177, 92);
	private static final Color PARCHMENT = new Color(185, 155, 101);
	private static final Color TEXT = new Color(239, 230, 207);
	private static final Color MUTED = new Color(177, 168, 148);
	private static final Color FRAME = new Color(19, 18, 17);
	private static final Color PANEL = new Color(29, 27, 24);
	private static final Font TITLE = new Font(Font.SERIF, Font.BOLD, 28);
	private static final Font SUBTITLE = new Font(Font.SERIF, Font.BOLD, 15);
	private static final Font LABEL = new Font(Font.SANS_SERIF, Font.BOLD, 12);
	private static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font BODY_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12);

	private final Client client;
	private final SixthDegreeDominionApiClient apiClient;
	private final OverlayManager overlayManager;
	private final ClientToolbar clientToolbar;
	private final KeyManager keyManager;
	private final AtomicBoolean refreshing = new AtomicBoolean(false);
	private final MapKeyListener mapKeyListener = new MapKeyListener();

	private ScheduledExecutorService scheduler;
	private volatile String sessionToken;
	private volatile SixthDegreeDominionApiClient.StateResponse state;
	private volatile boolean available;
	private volatile boolean visible;
	private volatile boolean running;
	private volatile boolean openRequested;
	private volatile String selectedRegionId = "morytania";
	private NavigationButton navigationButton;

	@Inject
	SixthDegreeDominionWarMap(
		Client client,
		SixthDegreeDominionApiClient apiClient,
		OverlayManager overlayManager,
		ClientToolbar clientToolbar,
		KeyManager keyManager)
	{
		this.client = client;
		this.apiClient = apiClient;
		this.overlayManager = overlayManager;
		this.clientToolbar = clientToolbar;
		this.keyManager = keyManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(Overlay.PRIORITY_HIGHEST);
	}

	void start()
	{
		if (running)
		{
			return;
		}
		running = true;
		overlayManager.add(this);
		keyManager.registerKeyListener(mapKeyListener);
		scheduler = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread thread = new Thread(r, "sixth-degree-dominion-map");
			thread.setDaemon(true);
			return thread;
		});
		scheduler.scheduleAtFixedRate(this::refreshState, 2, STATE_REFRESH_SECONDS, TimeUnit.SECONDS);
	}

	void stop()
	{
		running = false;
		visible = false;
		available = false;
		openRequested = false;
		sessionToken = null;
		state = null;
		refreshing.set(false);
		if (scheduler != null)
		{
			scheduler.shutdownNow();
			scheduler = null;
		}
		keyManager.unregisterKeyListener(mapKeyListener);
		overlayManager.remove(this);
		removeNavigationButton();
	}

	void activate(String token)
	{
		if (token == null || token.isBlank())
		{
			return;
		}
		sessionToken = token;
		refreshState();
	}

	void deactivate()
	{
		sessionToken = null;
		state = null;
		visible = false;
		openRequested = false;
		setAvailable(false);
	}

	void onGameStateChanged(GameState gameState)
	{
		if (gameState == GameState.LOGIN_SCREEN)
		{
			visible = false;
			openRequested = false;
		}
		else if (gameState == GameState.LOGGED_IN && sessionToken != null)
		{
			refreshState();
		}
	}

	boolean isVisible()
	{
		return visible;
	}

	void openVisible()
	{
		openRequested = true;
		if (available)
		{
			openRequested = false;
			visible = true;
		}
		refreshState();
	}

	void toggleVisible()
	{
		if (!available)
		{
			return;
		}
		visible = !visible;
		if (visible)
		{
			refreshState();
		}
	}

	private void refreshState()
	{
		String token = sessionToken;
		if (!running || token == null || token.isBlank() || !refreshing.compareAndSet(false, true))
		{
			return;
		}
		apiClient.getState(token).whenComplete((response, error) ->
		{
			refreshing.set(false);
			if (!running || !token.equals(sessionToken))
			{
				return;
			}
			if (error != null)
			{
				// Keep the last good map visible through a transient network error.
				log.debug("Dominion War Map state refresh failed", error);
				return;
			}
			state = response;
			boolean nextAvailable = response != null && response.ok && response.available;
			setAvailable(nextAvailable);
			if (openRequested)
			{
				openRequested = false;
				if (nextAvailable)
				{
					visible = true;
				}
			}
			if (response != null && response.available)
			{
				selectUsefulDefault(response);
			}
		});
	}

	private void selectUsefulDefault(SixthDegreeDominionApiClient.StateResponse response)
	{
		SixthDegreeDominionMapModel.TerritorySpec current = SixthDegreeDominionMapModel.byId(selectedRegionId);
		if (current != null)
		{
			return;
		}
		if (response.publicState != null && response.publicState.battles != null)
		{
			for (SixthDegreeDominionApiClient.Battle battle : response.publicState.battles)
			{
				if (battle != null && "OPEN".equalsIgnoreCase(battle.status)
					&& SixthDegreeDominionMapModel.byId(battle.region_id) != null)
				{
					selectedRegionId = battle.region_id;
					return;
				}
			}
		}
		selectedRegionId = "morytania";
	}

	private void setAvailable(boolean next)
	{
		available = next;
		if (!next)
		{
			visible = false;
		}
	}

	private void removeNavigationButton()
	{
		NavigationButton button = navigationButton;
		navigationButton = null;
		if (button != null)
		{
			SwingUtilities.invokeLater(() -> clientToolbar.removeNavigation(button));
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		SixthDegreeDominionApiClient.StateResponse snapshot = state;
		if (!visible || !available || snapshot == null || !snapshot.available
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		int canvasWidth = client.getCanvasWidth();
		int canvasHeight = client.getCanvasHeight();
		if (canvasWidth < 640 || canvasHeight < 420)
		{
			return null;
		}

		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			drawScreen(g, snapshot, canvasWidth, canvasHeight);
		}
		finally
		{
			g.dispose();
		}
		return new Dimension(canvasWidth, canvasHeight);
	}

	private void drawScreen(Graphics2D g, SixthDegreeDominionApiClient.StateResponse snapshot, int width, int height)
	{
		g.setColor(new Color(0, 0, 0, 188));
		g.fillRect(0, 0, width, height);

		int margin = Math.max(14, Math.min(width, height) / 42);
		Rectangle frame = new Rectangle(margin, margin, width - margin * 2, height - margin * 2);
		g.setColor(FRAME);
		g.fillRoundRect(frame.x, frame.y, frame.width, frame.height, 12, 12);
		g.setColor(GOLD.darker());
		g.setStroke(new BasicStroke(2f));
		g.drawRoundRect(frame.x, frame.y, frame.width, frame.height, 12, 12);

		int headerHeight = Math.max(62, Math.min(92, frame.height / 9));
		int footerHeight = 34;
		int sideWidth = Math.max(245, Math.min(350, (int) (frame.width * 0.28)));
		Rectangle header = new Rectangle(frame.x + 8, frame.y + 7, frame.width - 16, headerHeight);
		Rectangle footer = new Rectangle(frame.x + 8, frame.y + frame.height - footerHeight - 7, frame.width - 16, footerHeight);
		Rectangle map = new Rectangle(
			frame.x + 16,
			header.y + header.height + 8,
			frame.width - sideWidth - 40,
			frame.height - header.height - footerHeight - 38);
		Rectangle side = new Rectangle(
			map.x + map.width + 12,
			map.y,
			frame.x + frame.width - 16 - (map.x + map.width + 12),
			map.height);

		drawHeader(g, snapshot, header);
		drawMap(g, snapshot, map);
		drawSidePanel(g, snapshot, side);
		drawFooter(g, snapshot, footer);
	}

	private void drawHeader(Graphics2D g, SixthDegreeDominionApiClient.StateResponse snapshot, Rectangle header)
	{
		g.setPaint(new GradientPaint(header.x, header.y, new Color(34, 31, 26), header.x, header.y + header.height, new Color(17, 16, 15)));
		g.fillRoundRect(header.x, header.y, header.width, header.height, 8, 8);
		g.setColor(new Color(86, 70, 43));
		g.drawRoundRect(header.x, header.y, header.width, header.height, 8, 8);

		int x = header.x + 18;
		int baseline = header.y + 35;
		g.setFont(TITLE);
		g.setColor(GOLD);
		g.drawString("DOMINION", x, baseline);
		g.setFont(SUBTITLE);
		g.setColor(MUTED);
		g.drawString("WAR MAP", x + 4, baseline + 22);

		int dayNumber = snapshot.day == null ? 0 : snapshot.day.day_number;
		int redPoints = standingPoints(snapshot, "RED");
		int bluePoints = standingPoints(snapshot, "BLUE");
		String phase = phaseLabel(snapshot);
		String timing = countdown(snapshot);

		int right = header.x + header.width - 18;
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
		String score = "RED " + redPoints + " DP     BLUE " + bluePoints + " DP";
		int scoreWidth = g.getFontMetrics().stringWidth(score);
		g.setColor(TEXT);
		g.drawString(score, right - scoreWidth, header.y + 31);
		g.setFont(BODY_BOLD);
		String day = "Day " + (dayNumber > 0 ? dayNumber : "-") + " / 14   •   " + phase;
		int dayWidth = g.getFontMetrics().stringWidth(day);
		g.setColor(PARCHMENT);
		g.drawString(day, right - dayWidth, header.y + 51);
		g.setFont(BODY);
		int timeWidth = g.getFontMetrics().stringWidth(timing);
		g.setColor(MUTED);
		g.drawString(timing, right - timeWidth, header.y + 68);
	}

	private void drawMap(Graphics2D g, SixthDegreeDominionApiClient.StateResponse snapshot, Rectangle map)
	{
		g.setPaint(new GradientPaint(map.x, map.y, new Color(61, 73, 66), map.x, map.y + map.height, new Color(34, 52, 51)));
		g.fillRoundRect(map.x, map.y, map.width, map.height, 8, 8);
		g.setColor(new Color(104, 82, 49));
		g.drawRoundRect(map.x, map.y, map.width, map.height, 8, 8);

		Rectangle territoryBounds = new Rectangle(map.x + 12, map.y + 10, map.width - 24, map.height - 20);
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		SixthDegreeDominionMapModel.TerritorySpec hovered = null;
		if (mouse != null)
		{
			hovered = SixthDegreeDominionMapModel.hitTest(territoryBounds, mouse.getX(), mouse.getY());
			if (hovered != null)
			{
				selectedRegionId = hovered.id;
			}
		}

		long now = System.currentTimeMillis();
		double wave = (Math.sin(now / 330.0) + 1.0) / 2.0;
		for (SixthDegreeDominionMapModel.TerritorySpec spec : SixthDegreeDominionMapModel.territories())
		{
			SixthDegreeDominionApiClient.Territory territory = territory(snapshot, spec.id);
			SixthDegreeDominionApiClient.Battle battle = battle(snapshot, spec.id);
			Polygon polygon = SixthDegreeDominionMapModel.polygon(spec, territoryBounds);
			Color owner = ownerColor(territory == null ? null : territory.owner_team_code);
			g.setColor(withAlpha(owner, 150));
			g.fillPolygon(polygon);

			boolean contested = isContested(battle);
			boolean selected = spec.id.equalsIgnoreCase(selectedRegionId);
			Stroke oldStroke = g.getStroke();
			if (contested)
			{
				float pulse = (float) (2.0 + wave * 2.4);
				g.setStroke(new BasicStroke(pulse));
				g.setColor(withAlpha(RED, (int) (135 + wave * 100)));
				g.drawPolygon(polygon);
				g.setStroke(new BasicStroke(Math.max(1.5f, pulse - 1.3f)));
				g.setColor(withAlpha(BLUE, (int) (225 - wave * 80)));
				g.drawPolygon(polygon);
			}
			else
			{
				g.setStroke(new BasicStroke(selected ? 2.8f : 1.5f));
				g.setColor(selected ? GOLD : new Color(198, 166, 100, 190));
				g.drawPolygon(polygon);
			}
			g.setStroke(oldStroke);

			java.awt.Point center = SixthDegreeDominionMapModel.center(spec, territoryBounds);
			drawTerritoryLabel(g, spec.name, center.x, center.y - 4, selected || hovered == spec);
			String role = teamRole(snapshot, battle);
			if (role != null && "OPEN".equalsIgnoreCase(battle.status))
			{
				drawFrontMarker(g, role, center.x, center.y + 18);
			}
		}
	}

	private void drawTerritoryLabel(Graphics2D g, String name, int centerX, int centerY, boolean selected)
	{
		g.setFont(LABEL);
		FontMetrics metrics = g.getFontMetrics();
		int pad = 5;
		int width = metrics.stringWidth(name) + pad * 2;
		int height = 19;
		int x = centerX - width / 2;
		int y = centerY - height / 2;
		g.setColor(new Color(12, 12, 12, selected ? 235 : 200));
		g.fillRoundRect(x, y, width, height, 6, 6);
		g.setColor(selected ? GOLD : TEXT);
		g.drawString(name, x + pad, y + 14);
	}

	private void drawFrontMarker(Graphics2D g, String role, int centerX, int centerY)
	{
		String text = "DEFENCE".equalsIgnoreCase(role) ? "◆ DEFEND" : "⚔ ATTACK";
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		FontMetrics metrics = g.getFontMetrics();
		int width = metrics.stringWidth(text) + 10;
		int x = centerX - width / 2;
		g.setColor(new Color(5, 5, 5, 220));
		g.fillRoundRect(x, centerY - 9, width, 17, 7, 7);
		g.setColor("DEFENCE".equalsIgnoreCase(role) ? new Color(232, 213, 151) : new Color(255, 225, 167));
		g.drawString(text, x + 5, centerY + 4);
	}

	private void drawSidePanel(Graphics2D g, SixthDegreeDominionApiClient.StateResponse snapshot, Rectangle side)
	{
		g.setColor(PANEL);
		g.fillRoundRect(side.x, side.y, side.width, side.height, 8, 8);
		g.setColor(new Color(92, 73, 45));
		g.drawRoundRect(side.x, side.y, side.width, side.height, 8, 8);

		SixthDegreeDominionMapModel.TerritorySpec spec = SixthDegreeDominionMapModel.byId(selectedRegionId);
		if (spec == null)
		{
			spec = SixthDegreeDominionMapModel.territories().get(0);
		}
		SixthDegreeDominionApiClient.Territory territory = territory(snapshot, spec.id);
		SixthDegreeDominionApiClient.Battle battle = battle(snapshot, spec.id);

		int x = side.x + 16;
		int y = side.y + 31;
		g.setFont(new Font(Font.SERIF, Font.BOLD, 21));
		g.setColor(GOLD);
		g.drawString(spec.name.toUpperCase(Locale.ROOT), x, y);
		y += 26;
		g.setFont(BODY);
		g.setColor(MUTED);
		g.drawString("Selected territory", x, y);
		y += 25;
		drawRule(g, side.x + 12, y, side.width - 24);
		y += 21;

		String owner = territory == null || territory.owner_team_code == null || territory.owner_team_code.isBlank()
			? "Neutral" : titleCase(territory.owner_team_code);
		y = drawDetail(g, x, y, "Owner", owner, ownerColor(territory == null ? null : territory.owner_team_code));
		String held = territory == null || territory.consecutive_days_held <= 0
			? "-" : territory.consecutive_days_held + (territory.consecutive_days_held == 1 ? " day" : " days");
		y = drawDetail(g, x, y, "Held", held, TEXT);

		String role = teamRole(snapshot, battle);
		String status;
		if (battle != null && "OPEN".equalsIgnoreCase(battle.status))
		{
			status = role == null ? "Contested" : ("DEFENCE".equalsIgnoreCase(role) ? "Defence" : "Attack");
		}
		else
		{
			status = "Held";
		}
		y = drawDetail(g, x, y, "Status", status, battle != null && "OPEN".equalsIgnoreCase(battle.status) ? GOLD : TEXT);

		y += 4;
		drawRule(g, side.x + 12, y, side.width - 24);
		y += 20;
		g.setFont(BODY_BOLD);
		g.setColor(PARCHMENT);
		g.drawString("LIVE BATTLE", x, y);
		y += 21;
		if (battle == null || !"OPEN".equalsIgnoreCase(battle.status))
		{
			g.setFont(BODY);
			g.setColor(MUTED);
			g.drawString("No active battle in this region.", x, y);
			y += 22;
		}
		else
		{
			if (battle.scores != null && battle.scores.length > 0)
			{
				for (SixthDegreeDominionApiClient.BattleScore score : battle.scores)
				{
					if (score == null)
					{
						continue;
					}
					String code = score.code == null || score.code.isBlank() ? "Team" : titleCase(score.code);
					y = drawDetail(g, x, y, code, influence(score.final_milli), ownerColor(score.code));
				}
			}
			long reserve = playerTeamReserve(snapshot, battle);
			if (reserve > 0)
			{
				y = drawDetail(g, x, y, "Your Reserve", "+" + influence(reserve), GOLD);
			}
		}

		int legendY = Math.max(y + 18, side.y + side.height - 157);
		drawRule(g, side.x + 12, legendY, side.width - 24);
		legendY += 22;
		g.setFont(BODY_BOLD);
		g.setColor(PARCHMENT);
		g.drawString("MAP LEGEND", x, legendY);
		legendY += 20;
		legendY = drawLegend(g, x, legendY, RED, "Red owned");
		legendY = drawLegend(g, x, legendY, BLUE, "Blue owned");
		legendY = drawLegend(g, x, legendY, NEUTRAL, "Neutral");
		g.setColor(GOLD);
		g.setFont(BODY);
		g.drawString("⚔ Attack   ◆ Defence", x, legendY + 3);
	}

	private int drawDetail(Graphics2D g, int x, int y, String label, String value, Color valueColor)
	{
		g.setFont(BODY);
		g.setColor(MUTED);
		g.drawString(label + ":", x, y);
		g.setFont(BODY_BOLD);
		g.setColor(valueColor);
		g.drawString(value, x + 92, y);
		return y + 23;
	}

	private int drawLegend(Graphics2D g, int x, int y, Color color, String text)
	{
		g.setColor(color);
		g.fillRect(x, y - 10, 11, 11);
		g.setColor(TEXT);
		g.setFont(BODY);
		g.drawString(text, x + 20, y);
		return y + 19;
	}

	private void drawFooter(Graphics2D g, SixthDegreeDominionApiClient.StateResponse snapshot, Rectangle footer)
	{
		g.setColor(new Color(14, 13, 12));
		g.fillRoundRect(footer.x, footer.y, footer.width, footer.height, 6, 6);
		g.setFont(BODY_BOLD);
		g.setColor(TEXT);
		g.drawString("Close [Esc]", footer.x + 12, footer.y + 22);

		String live = snapshot.scoring_active ? "● SCORING LIVE" : "○ SCORING PAUSED — " + phaseLabel(snapshot).toUpperCase(Locale.ROOT);
		FontMetrics metrics = g.getFontMetrics();
		g.setColor(snapshot.scoring_active ? new Color(98, 214, 112) : MUTED);
		g.drawString(live, footer.x + footer.width - metrics.stringWidth(live) - 12, footer.y + 22);
	}

	private static void drawRule(Graphics2D g, int x, int y, int width)
	{
		g.setColor(new Color(86, 70, 43));
		g.drawLine(x, y, x + width, y);
	}

	private SixthDegreeDominionApiClient.Territory territory(SixthDegreeDominionApiClient.StateResponse snapshot, String regionId)
	{
		if (snapshot.publicState == null || snapshot.publicState.territories == null)
		{
			return null;
		}
		for (SixthDegreeDominionApiClient.Territory territory : snapshot.publicState.territories)
		{
			if (territory != null && regionId.equalsIgnoreCase(territory.region_id))
			{
				return territory;
			}
		}
		return null;
	}

	private SixthDegreeDominionApiClient.Battle battle(SixthDegreeDominionApiClient.StateResponse snapshot, String regionId)
	{
		if (snapshot.publicState == null || snapshot.publicState.battles == null)
		{
			return null;
		}
		for (SixthDegreeDominionApiClient.Battle battle : snapshot.publicState.battles)
		{
			if (battle != null && regionId.equalsIgnoreCase(battle.region_id))
			{
				return battle;
			}
		}
		return null;
	}

	private String teamRole(SixthDegreeDominionApiClient.StateResponse snapshot, SixthDegreeDominionApiClient.Battle battle)
	{
		if (snapshot.team == null || battle == null || battle.teams == null)
		{
			return null;
		}
		for (SixthDegreeDominionApiClient.BattleTeam team : battle.teams)
		{
			if (team != null && team.team_id == snapshot.team.id)
			{
				return team.role;
			}
		}
		return null;
	}

	private static boolean isContested(SixthDegreeDominionApiClient.Battle battle)
	{
		return battle != null && "OPEN".equalsIgnoreCase(battle.status)
			&& battle.teams != null && battle.teams.length > 1;
	}

	private long playerTeamReserve(SixthDegreeDominionApiClient.StateResponse snapshot, SixthDegreeDominionApiClient.Battle battle)
	{
		if (snapshot.team == null || battle == null || battle.scores == null)
		{
			return 0;
		}
		for (SixthDegreeDominionApiClient.BattleScore score : battle.scores)
		{
			if (score != null && score.team_id == snapshot.team.id)
			{
				return score.war_deployed_milli;
			}
		}
		return 0;
	}

	private static int standingPoints(SixthDegreeDominionApiClient.StateResponse snapshot, String code)
	{
		if (snapshot.publicState == null || snapshot.publicState.dominion_standings == null)
		{
			return 0;
		}
		for (SixthDegreeDominionApiClient.Standing standing : snapshot.publicState.dominion_standings)
		{
			if (standing != null && code.equalsIgnoreCase(standing.code))
			{
				return standing.points;
			}
		}
		return 0;
	}

	private static Color ownerColor(String code)
	{
		if ("RED".equalsIgnoreCase(code))
		{
			return RED;
		}
		if ("BLUE".equalsIgnoreCase(code))
		{
			return BLUE;
		}
		return NEUTRAL;
	}

	private static Color withAlpha(Color color, int alpha)
	{
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
	}

	private static String influence(long milli)
	{
		double value = milli / 1000.0;
		if (Math.abs(value - Math.rint(value)) < 0.0001)
		{
			return String.format(Locale.UK, "%.0f", value);
		}
		return String.format(Locale.UK, "%.1f", value);
	}

	private static String titleCase(String value)
	{
		if (value == null || value.isBlank())
		{
			return "-";
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	private static String phaseLabel(SixthDegreeDominionApiClient.StateResponse snapshot)
	{
		String phase = snapshot.event_phase == null ? "READY" : snapshot.event_phase.toUpperCase(Locale.ROOT);
		switch (phase)
		{
			case "VOTING":
				return "Attack voting";
			case "BATTLE":
				return "Battles live";
			case "RESOLVED":
				return "Night resolved";
			case "COMPLETE":
				return "Campaign complete";
			default:
				return "Ready";
		}
	}

	private static String countdown(SixthDegreeDominionApiClient.StateResponse snapshot)
	{
		if (!snapshot.scoring_active || snapshot.day == null || snapshot.day.ends_at <= 0)
		{
			return snapshot.scoring_active ? "Live battle window" : "Waiting for battle orders";
		}
		long remaining = Math.max(0, snapshot.day.ends_at - System.currentTimeMillis() / 1000L);
		long hours = remaining / 3600L;
		long minutes = (remaining % 3600L) / 60L;
		long seconds = remaining % 60L;
		return String.format(Locale.UK, "Resolution in %02d:%02d:%02d", hours, minutes, seconds);
	}

	private static BufferedImage buildMapIcon()
	{
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(28, 27, 24));
			g.fillOval(1, 1, 30, 30);
			g.setColor(GOLD);
			g.setStroke(new BasicStroke(2f));
			g.drawOval(2, 2, 27, 27);
			Polygon crown = new Polygon(
				new int[]{7, 10, 14, 17, 22, 25, 24, 8},
				new int[]{12, 7, 12, 5, 12, 7, 18, 18},
				8);
			g.fillPolygon(crown);
			g.setColor(TEXT);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
			g.drawString("D", 12, 28);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	private final class MapKeyListener implements KeyListener
	{
		@Override
		public void keyTyped(KeyEvent event)
		{
		}

		@Override
		public void keyPressed(KeyEvent event)
		{
			if (visible && event.getKeyCode() == KeyEvent.VK_ESCAPE)
			{
				visible = false;
			}
		}

		@Override
		public void keyReleased(KeyEvent event)
		{
		}
	}
}
