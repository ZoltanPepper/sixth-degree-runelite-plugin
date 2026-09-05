# Sixth Degree RuneLite Plugin

RuneLite companion for the **Sixth Degree** Old School RuneScape clan.

The plugin keeps clan events, competitions, LFG and clan notifications in one RuneLite sidebar. Members link Discord once, then the plugin checks that the logged-in RuneScape account belongs to Sixth Degree before member features are unlocked.

## Features

- Recruitment screen for players who are not yet in Sixth Degree
- Sixth Degree clan membership check using RuneLite clan data
- Discord linking and `Clan Member` role verification
- Daily, weekly and monthly clan loot leaderboards using all tracked drops
- Clan events shown in RuneLite; Discord remains the place for RSVP and attendance
- Boss of the Week (BOTW) standings and tracking
- Skill of the Week (SOTW) standings and tracking
- Looking For Group (LFG) with current world and live member notifications
- Clan-managed loot, pet, collection-log, death, level/XP and boss KC/PB notifications
- Automatic screenshots for notification types enabled by the clan configuration

Bingo is intentionally separate from this plugin and continues to use the existing Sixth Degree Discord system.

## Access

- **Not in Sixth Degree:** recruitment screen and Discord invite.
- **Clan member, Discord not linked:** Connect Discord screen.
- **Clan member + linked Discord account + `Clan Member` role:** full plugin access.
- Multiple Sixth Degree RuneScape accounts can be linked to the same Discord member. Competition scores remain separate by RuneScape account.

Discord linking is normally required once per RuneScape account on each PC. The saved plugin session is revalidated in the background, including Discord role checks.

## Clan configuration

Notification thresholds and screenshot rules are set centrally through Boss Lady. Members do not configure webhooks, Discord channels or clan thresholds themselves.

Local member settings are limited to normal RuneLite notification/sound preferences.

## Service and privacy

The plugin communicates with the Sixth Degree HTTPS API:

`https://amnesty-bootlace-poach.ngrok-free.dev/runelite/v1/`

Depending on the feature being used, the service may receive:

- RuneScape display name
- linked Discord user ID
- clan event and competition telemetry
- LFG activity and current world when an LFG is posted
- loot values used for the clan leaderboard
- notification metadata such as loot, pets, collection-log entries, levels/XP and boss updates
- RuneLite screenshots when the clan notification rules require one

Screenshots are passed through Boss Lady to the configured Discord channel; the application does not save screenshot files to its own storage. Notification metadata, account links, leaderboard totals and session records are stored in the Sixth Degree database where needed for the feature to work.

Discord OAuth access tokens are used only to identify the Discord account during linking and are not retained. Plugin session tokens are stored as hashes on the server. Bot/OAuth secrets are kept in the hosting environment and are not included in this repository or the RuneLite plugin.

As with any internet service, absolute protection from compromise cannot be guaranteed. See the Plugin Hub installation warning for the data sent to the Sixth Degree service.

## Development

The project follows the RuneLite Plugin Hub structure, targets Java 11 and builds against RuneLite `latest.release`.

`SixthDegreePluginTest` launches RuneLite in developer mode with the plugin loaded directly. CI builds and tests the plugin and also produces a development ZIP used before Plugin Hub releases.

## Third-party work

Parts of the notification behaviour and event parsing are adapted from the open-source **DinkPlugin** project under its BSD 2-Clause licence. Full attribution and licence text are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
