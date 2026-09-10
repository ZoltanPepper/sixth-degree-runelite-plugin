from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Anchor not found in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


panel = Path("src/main/java/com/sixthdegree/SixthDegreePanel.java")
plugin = Path("src/main/java/com/sixthdegree/SixthDegreePlugin.java")
api = Path("src/main/java/com/sixthdegree/SixthDegreeDominionApiClient.java")

replace_once(
    panel,
    "\tprivate final SixthDegreeApiClient apiClient;\n\tprivate final Runnable dominionWarMapAction;",
    "\tprivate final SixthDegreeApiClient apiClient;\n\tprivate final SixthDegreeDominionApiClient dominionApiClient;\n\tprivate final Runnable dominionWarMapAction;",
)
replace_once(
    panel,
    "\tprivate boolean competitionRefreshInFlight;",
    "\tprivate boolean competitionRefreshInFlight;\n\tprivate boolean dominionRefreshInFlight;",
)
replace_once(
    panel,
    "\tpublic SixthDegreePanel(SixthDegreeApiClient apiClient, Runnable dominionWarMapAction)\n\t{\n\t\tsuper(false);\n\t\tthis.apiClient = apiClient;\n\t\tthis.dominionWarMapAction = dominionWarMapAction == null ? () -> { } : dominionWarMapAction;",
    "\tpublic SixthDegreePanel(SixthDegreeApiClient apiClient, SixthDegreeDominionApiClient dominionApiClient, Runnable dominionWarMapAction)\n\t{\n\t\tsuper(false);\n\t\tthis.apiClient = apiClient;\n\t\tthis.dominionApiClient = dominionApiClient;\n\t\tthis.dominionWarMapAction = dominionWarMapAction == null ? () -> { } : dominionWarMapAction;",
)
replace_once(
    panel,
    "\tpublic void refreshCompetitionIfVisible()\n\t{\n\t\tif (sessionToken == null\n\t\t\t|| primaryPage != PrimaryPage.EVENTS\n\t\t\t|| (eventPage != EventPage.BOTW && eventPage != EventPage.SOTW)\n\t\t\t|| !isShowing())\n\t\t{\n\t\t\treturn;\n\t\t}\n\t\trequestCompetition(eventPage == EventPage.BOTW ? \"BOTW\" : \"SOTW\", false);\n\t}\n",
    "\tpublic void refreshCompetitionIfVisible()\n\t{\n\t\tif (sessionToken == null\n\t\t\t|| primaryPage != PrimaryPage.EVENTS\n\t\t\t|| (eventPage != EventPage.BOTW && eventPage != EventPage.SOTW)\n\t\t\t|| !isShowing())\n\t\t{\n\t\t\treturn;\n\t\t}\n\t\trequestCompetition(eventPage == EventPage.BOTW ? \"BOTW\" : \"SOTW\", false);\n\t}\n\n\t/** Refresh the live Dominion dashboard without forcing the War Map open. */\n\tpublic void refreshDominionIfVisible()\n\t{\n\t\tif (sessionToken == null\n\t\t\t|| primaryPage != PrimaryPage.EVENTS\n\t\t\t|| eventPage != EventPage.DOMINION\n\t\t\t|| !isShowing())\n\t\t{\n\t\t\treturn;\n\t\t}\n\t\trequestDominion(false);\n\t}\n",
)
replace_once(
    panel,
    "\t\telse if (eventPage == EventPage.DOMINION)\n\t\t{\n\t\t\tdominionWarMapAction.run();\n\t\t\trenderDominionLauncher();\n\t\t}",
    "\t\telse if (eventPage == EventPage.DOMINION)\n\t\t{\n\t\t\trequestDominion(true);\n\t\t}",
)
start = panel.read_text(encoding="utf-8")
old_start = "\tprivate void renderDominionLauncher()\n\t{"
old_end = "\n\tprivate void requestCompetition(String kind, boolean showSpinner)"
start_idx = start.find(old_start)
end_idx = start.find(old_end, start_idx)
if start_idx < 0 or end_idx < 0:
    raise SystemExit("Dominion launcher method anchors not found")

new_dashboard = r'''	private void requestDominion(boolean showSpinner)
	{
		if (dominionRefreshInFlight || sessionToken == null || dominionApiClient == null)
		{
			return;
		}
		dominionRefreshInFlight = true;
		final int scrollPosition = showSpinner ? 0 : scrollPane.getVerticalScrollBar().getValue();
		if (showSpinner)
		{
			showLoading("Dominion");
		}
		dominionApiClient.getState(sessionToken).whenComplete((data, error) ->
			SwingUtilities.invokeLater(() ->
			{
				dominionRefreshInFlight = false;
				if (primaryPage == PrimaryPage.EVENTS && eventPage == EventPage.DOMINION)
				{
					renderDominionDashboard(data, error);
					if (!showSpinner)
					{
						SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(scrollPosition));
					}
				}
			}));
	}

	private void renderDominionDashboard(SixthDegreeDominionApiClient.StateResponse data, Throwable error)
	{
		if (primaryPage != PrimaryPage.EVENTS || eventPage != EventPage.DOMINION)
		{
			return;
		}
		if (error != null || data == null || !data.ok)
		{
			renderError("DOMINION", error);
			return;
		}

		clearContent();
		addHeading("DOMINION");
		if (!data.available)
		{
			JPanel unavailable = card();
			addCardStrong(unavailable, "No active Dominion assignment");
			addCardText(unavailable, "When this RuneScape account is placed on a Dominion team, your war dashboard will appear here automatically.");
			addCard(unavailable);
			finishContent();
			return;
		}

		JPanel summary = card();
		String teamName = data.team == null ? "Dominion" : escape(nullTo(data.team.name, data.team.code));
		addCardTitle(summary, teamName.toUpperCase(Locale.ROOT));
		int dayNumber = data.day == null ? 0 : data.day.day_number;
		addCardStrong(summary, dayNumber > 0 ? "Day " + dayNumber + " / 14" : "Campaign ready");
		addCardText(summary, dominionScoreline(data));
		addCardText(summary, "Phase: <b>" + escape(dominionPhase(data.event_phase)) + "</b>");
		if (data.day != null && data.day.ends_at > Instant.now().getEpochSecond() && "BATTLE".equalsIgnoreCase(data.event_phase))
		{
			addCardText(summary, "Resolution in <b>" + remaining(data.day.ends_at) + "</b>");
		}
		else if ("VOTING".equalsIgnoreCase(data.event_phase))
		{
			addCardText(summary, "Attack voting is open in your Discord war room. Scoring is paused until orders lock.");
		}
		addCard(summary);

		addGap(10);
		JPanel orders = card();
		addCardTitle(orders, "TODAY'S ORDERS");
		boolean hasFront = false;
		if (data.team != null && data.team.attack_orders != null && data.team.attack_orders.orders != null)
		{
			for (SixthDegreeDominionApiClient.AttackOrder order : data.team.attack_orders.orders)
			{
				if (order == null || order.region_id == null || order.region_id.isBlank()) continue;
				hasFront = true;
				addCardStrong(orders, "ATTACK — " + escape(dominionRegionName(order.region_id)));
				addCardText(orders, dominionBattleLine(data, order.region_id));
			}
		}
		if (data.publicState != null && data.publicState.battles != null && data.team != null)
		{
			for (SixthDegreeDominionApiClient.Battle battle : data.publicState.battles)
			{
				if (battle == null || battle.teams == null || battle.region_id == null) continue;
				boolean defence = false;
				for (SixthDegreeDominionApiClient.BattleTeam participant : battle.teams)
				{
					if (participant != null && participant.team_id == data.team.id && "DEFENCE".equalsIgnoreCase(participant.role))
					{
						defence = true;
						break;
					}
				}
				if (defence)
				{
					hasFront = true;
					addCardStrong(orders, "DEFEND — " + escape(dominionRegionName(battle.region_id)));
					addCardText(orders, dominionBattleLine(data, battle.region_id));
				}
			}
		}
		if (!hasFront)
		{
			addCardText(orders, "No battle fronts are open yet. Attack Orders appear here as soon as voting is locked.");
		}
		addCard(orders);

		addGap(10);
		JPanel personal = card();
		addCardTitle(personal, "PERSONAL ORDER");
		JsonObject personalOrder = data.player == null ? null : data.player.personal_order;
		if (personalOrder == null || personalOrder.size() == 0)
		{
			addCardText(personal, "Your Personal Order is assigned when today's battles open.");
		}
		else
		{
			addCardStrong(personal, escape(jsonString(personalOrder, "title", "Personal Order")));
			String description = jsonString(personalOrder, "description", "");
			if (!description.isBlank()) addCardText(personal, escape(description));
			addCardText(personal, dominionObjectiveProgress(personalOrder));
			String reward = jsonString(personalOrder, "reward_influence", "");
			if (!reward.isBlank()) addCardText(personal, "Reward: <b>+" + escape(reward) + " War Reserve</b>");
		}
		addCard(personal);

		addGap(10);
		JPanel shared = card();
		addCardTitle(shared, "SHARED MISSION");
		JsonObject sharedMission = data.team == null ? null : data.team.shared_mission;
		if (sharedMission == null || sharedMission.size() == 0)
		{
			addCardText(shared, "Your team mission is assigned when today's battles open.");
		}
		else
		{
			addCardStrong(shared, escape(jsonString(sharedMission, "title", "Shared Mission")));
			addCardText(shared, dominionObjectiveProgress(sharedMission));
			JsonObject progress = nested(sharedMission, "progress");
			try
			{
				if (progress.has("components") && progress.get("components").isJsonArray())
				{
					int shown = 0;
					for (com.google.gson.JsonElement element : progress.getAsJsonArray("components"))
					{
						if (!element.isJsonObject() || shown++ >= 4) continue;
						JsonObject component = element.getAsJsonObject();
						String label = jsonString(component, "label", "Objective");
						int current = jsonInt(component, "current", 0);
						int target = jsonInt(component, "target", 0);
						boolean complete = bool(component, "complete", false);
						addCardText(shared, (complete ? "✓ " : "• ") + escape(label) + (target > 0 ? " — <b>" + current + "/" + target + "</b>" : ""));
					}
				}
			}
			catch (Exception ignored) { }
			String reward = jsonString(sharedMission, "reward_influence", "");
			if (!reward.isBlank()) addCardText(shared, "Reward: <b>+" + escape(reward) + " War Reserve</b>");
		}
		addCard(shared);

		addGap(10);
		JPanel contribution = card();
		addCardTitle(contribution, "YOUR CONTRIBUTION");
		if (data.player != null && data.player.contribution != null)
		{
			addCardText(contribution, "Territory: <b>" + escape(nullTo(data.player.contribution.territory_influence, "0")) + " Influence</b>");
			addCardText(contribution, "Support / Reserve: <b>" + escape(nullTo(data.player.contribution.war_influence, "0")) + " Influence</b>");
		}
		if (data.player != null && data.player.xp != null)
		{
			addCardText(contribution, "XP Influence: <b>" + data.player.xp.used_units + " / " + data.player.xp.cap_units + "</b>");
			addCardText(contribution, NUMBER.format((long) data.player.xp.used_units * data.player.xp.xp_per_influence)
				+ " / " + NUMBER.format((long) data.player.xp.cap_units * data.player.xp.xp_per_influence) + " qualifying XP");
		}
		addCardText(contribution, "Routine diminishing returns are applied automatically before Support routing.");
		addCard(contribution);

		addGap(10);
		JPanel reserve = card();
		addCardTitle(reserve, "WAR RESERVE");
		if (data.team != null && data.team.war_reserve != null)
		{
			addCardStrong(reserve, escape(nullTo(data.team.war_reserve.available_influence, "0")) + " available");
			addCardText(reserve, "Earned: <b>" + escape(nullTo(data.team.war_reserve.gross_influence, "0")) + "</b><br>Deployed today: <b>" + escape(nullTo(data.team.war_reserve.deployed_influence, "0")) + "</b>");
		}
		JsonObject reserveProposal = data.team == null ? null : data.team.reserve_proposal;
		if (reserveProposal != null && reserveProposal.size() > 0)
		{
			long amountMilli = jsonLong(reserveProposal, "amount_milli", 0L);
			String region = jsonString(reserveProposal, "region_id", "");
			int approve = jsonInt(reserveProposal, "approve_count", 0);
			int required = jsonInt(reserveProposal, "required_votes", 0);
			addCardText(reserve, "Pending: <b>" + formatInfluence(amountMilli) + " → " + escape(dominionRegionName(region)) + "</b>");
			addCardText(reserve, "Team approval: <b>" + approve + " / " + required + "</b>");
		}
		else
		{
			addCardText(reserve, "Reserve proposals and voting are handled privately in your Discord war room.");
		}
		addCard(reserve);

		addGap(12);
		JButton map = wideButton("Open Dominion Map");
		map.setMaximumSize(new Dimension(CARD_WIDTH, 38));
		map.addActionListener(e -> dominionWarMapAction.run());
		content.add(centerRow(map));
		addGap(6);
		JButton refresh = smallButton("Refresh Dominion");
		refresh.addActionListener(e -> requestDominion(false));
		content.add(centerRow(refresh));
		finishContent();
	}

	private static String dominionScoreline(SixthDegreeDominionApiClient.StateResponse data)
	{
		int red = 0;
		int blue = 0;
		if (data.publicState != null && data.publicState.dominion_standings != null)
		{
			for (SixthDegreeDominionApiClient.Standing standing : data.publicState.dominion_standings)
			{
				if (standing == null || standing.code == null) continue;
				if ("RED".equalsIgnoreCase(standing.code)) red = standing.points;
				if ("BLUE".equalsIgnoreCase(standing.code)) blue = standing.points;
			}
		}
		return "<font color='#e45b5b'><b>Red " + red + " DP</b></font> • <font color='#6297ea'><b>Blue " + blue + " DP</b></font>";
	}

	private static String dominionPhase(String phase)
	{
		if (phase == null || phase.isBlank()) return "Ready";
		if ("BATTLE".equalsIgnoreCase(phase)) return "Battles live";
		if ("VOTING".equalsIgnoreCase(phase)) return "Attack voting";
		if ("RESOLVED".equalsIgnoreCase(phase)) return "Day resolved";
		if ("COMPLETE".equalsIgnoreCase(phase)) return "Campaign complete";
		return phase.substring(0, 1).toUpperCase(Locale.ROOT) + phase.substring(1).toLowerCase(Locale.ROOT);
	}

	private static String dominionRegionName(String regionId)
	{
		SixthDegreeDominionMapModel.TerritorySpec spec = SixthDegreeDominionMapModel.byId(regionId);
		return spec == null ? nullTo(regionId, "Unknown region") : spec.name;
	}

	private static String dominionBattleLine(SixthDegreeDominionApiClient.StateResponse data, String regionId)
	{
		if (data.publicState == null || data.publicState.battles == null)
		{
			return "Waiting for live score.";
		}
		for (SixthDegreeDominionApiClient.Battle battle : data.publicState.battles)
		{
			if (battle == null || battle.region_id == null || !battle.region_id.equalsIgnoreCase(regionId)) continue;
			if (battle.scores == null || battle.scores.length == 0) return "Battle open — no Influence scored yet.";
			StringBuilder score = new StringBuilder();
			long our = 0;
			long bestOther = 0;
			for (SixthDegreeDominionApiClient.BattleScore row : battle.scores)
			{
				if (row == null) continue;
				if (score.length() > 0) score.append(" • ");
				score.append(escape(nullTo(row.code, row.name))).append(" ").append(formatInfluence(row.final_milli));
				if (data.team != null && row.team_id == data.team.id) our = row.final_milli;
				else bestOther = Math.max(bestOther, row.final_milli);
			}
			long delta = our - bestOther;
			if (battle.scores.length > 1)
			{
				score.append("<br>").append(delta >= 0 ? "Ahead by <b>" : "Behind by <b>")
					.append(formatInfluence(Math.abs(delta))).append("</b>");
			}
			return score.toString();
		}
		return "Front preparing.";
	}

	private static String dominionObjectiveProgress(JsonObject objective)
	{
		JsonObject progress = nested(objective, "progress");
		if (progress.size() == 0)
		{
			String status = jsonString(objective, "status", "ASSIGNED");
			return "Status: <b>" + escape(status) + "</b>";
		}
		int current = jsonInt(progress, "current", 0);
		int target = jsonInt(progress, "target", 0);
		String label = jsonString(progress, "label", "Progress");
		boolean complete = bool(progress, "complete", false);
		if (target > 0)
		{
			return (complete ? "Complete ✓" : escape(label)) + ": <b>" + current + " / " + target + "</b>";
		}
		return complete ? "Complete ✓" : "In progress";
	}

	private static String formatInfluence(long milli)
	{
		String value = String.format(Locale.UK, "%.2f", Math.max(0L, milli) / 1000.0);
		while (value.contains(".") && value.endsWith("0")) value = value.substring(0, value.length() - 1);
		if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
		return value;
	}

	private static String jsonString(JsonObject object, String key, String fallback)
	{
		try { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback; }
		catch (Exception ignored) { return fallback; }
	}

	private static int jsonInt(JsonObject object, String key, int fallback)
	{
		try { return object != null && object.has(key) ? object.get(key).getAsInt() : fallback; }
		catch (Exception ignored) { return fallback; }
	}

	private static long jsonLong(JsonObject object, String key, long fallback)
	{
		try { return object != null && object.has(key) ? object.get(key).getAsLong() : fallback; }
		catch (Exception ignored) { return fallback; }
	}
'''

panel.write_text(start[:start_idx] + new_dashboard + start[end_idx:], encoding="utf-8")

replace_once(
    panel,
    "\t\tcompetitionRefreshInFlight = false;\n\t\tprimaryGroup.clearSelection();",
    "\t\tcompetitionRefreshInFlight = false;\n\t\tdominionRefreshInFlight = false;\n\t\tprimaryGroup.clearSelection();",
)

replace_once(
    api,
    "\t\tContribution contribution;\n\t\tXpState xp;",
    "\t\tContribution contribution;\n\t\tXpState xp;\n\t\tJsonObject gameplay;\n\t\tJsonObject personal_order;",
)
replace_once(
    api,
    "\t\tAttackOrders attack_orders;\n\t\tWarReserve war_reserve;",
    "\t\tAttackOrders attack_orders;\n\t\tWarReserve war_reserve;\n\t\tJsonObject routine_support;\n\t\tJsonObject shared_mission;\n\t\tJsonObject reserve_proposal;",
)

replace_once(
    plugin,
    "\t@Inject\n\tprivate SixthDegreeApiClient apiClient;\n\n\t@Inject\n\tprivate SixthDegreeRealtimeClient realtimeClient;",
    "\t@Inject\n\tprivate SixthDegreeApiClient apiClient;\n\n\t@Inject\n\tprivate SixthDegreeDominionApiClient dominionApiClient;\n\n\t@Inject\n\tprivate SixthDegreeRealtimeClient realtimeClient;",
)
replace_once(
    plugin,
    "\t\tpanel = new SixthDegreePanel(apiClient, notificationCoordinator::openDominionWarMap);",
    "\t\tpanel = new SixthDegreePanel(apiClient, dominionApiClient, notificationCoordinator::openDominionWarMap);",
)
replace_once(
    plugin,
    "\t\t\t\t\tpanel.refreshLootLeaderboardIfVisible();\n\t\t\t\t\tpanel.refreshCompetitionIfVisible();",
    "\t\t\t\t\tpanel.refreshLootLeaderboardIfVisible();\n\t\t\t\t\tpanel.refreshCompetitionIfVisible();\n\t\t\t\t\tpanel.refreshDominionIfVisible();",
)

print("Dominion dashboard source patch applied")
