# Sixth Degree RuneLite Plugin

Official RuneLite companion plugin for the **Sixth Degree** Old School RuneScape clan.

## V1 scope

- Public recruitment state for non-members
- In-game Sixth Degree clan membership gate
- Discord OAuth linking and `Clan Member` role verification
- Clan-controlled configuration
- Events, RSVP and logged-in reminders
- Boss of the Week tracking
- Skill of the Week tracking
- Looking For Group (LFG)
- Always-on clan notifications/screenshots for configured loot, pets, collection logs, milestones and related activity

Bingo integration is planned for Phase 2 and will reuse the same authentication, telemetry, screenshot and event infrastructure.

## Privacy / external service

This plugin communicates with a Sixth Degree-operated HTTPS service. Depending on clan configuration it may transmit the logged-in RuneScape name, event/competition telemetry, loot/activity metadata and screenshots used for clan notifications or event evidence.

Development API origin:

`https://amnesty-bootlace-poach.ngrok-free.dev/runelite/v1/`

The Discord bot token and Discord OAuth client secret are never stored in this public repository or distributed with the RuneLite plugin.

## Access model

- RuneScape account not in Sixth Degree: recruitment screen only.
- RuneScape account in Sixth Degree but Discord not linked: Discord-link screen.
- RuneScape account in Sixth Degree + Discord server membership + `Clan Member` role: full member access.
- Multiple Sixth Degree RSNs may be linked to one Discord member; competition entries remain separate by RSN.

## Development

The project follows the RuneLite example-plugin / Plugin Hub structure and targets Java 11 compatibility.

`SixthDegreePluginTest` launches a normal RuneLite client in developer mode with the plugin loaded directly.

CI also produces an owner-only `sixth-degree-runelite-dev.zip` development bundle for private testing before Plugin Hub submission. The bundle is intentionally not a public clan release.

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
