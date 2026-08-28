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

This plugin will communicate with a Sixth Degree-operated HTTPS service. Depending on clan configuration it may transmit the logged-in RuneScape name, event/competition telemetry, loot/activity metadata and screenshots used for clan notifications or event evidence.

The Discord bot token and Discord OAuth client secret are never stored in this public repository or distributed with the RuneLite plugin.

## Development

The project follows the RuneLite example-plugin / Plugin Hub structure and targets Java 11.

The production API origin is intentionally not hard-coded until the Sixth Degree public HTTPS endpoint is finalised.

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
