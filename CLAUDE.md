# Vis Arena

A server-side-only NeoForge mod that runs minigames (FFA, TDM, 1v1, Core Siege) for servers playing Vis Mod. The full
design is in [docs/design.md](docs/design.md). Read the relevant section before starting a milestone.

Vis Mod is a separate project at `../vismod/MDK-1.21-ModDevGradle-main` (its own CLAUDE.md, repo and jar). This mod
depends on it and uses its hooks; it never copies Vis Mod code.

## Target versions

- Minecraft **1.21.1**
- NeoForge **21.1.x**: compiled against 21.1.256, and it must also run on 21.1.226, the build Apex Hosting pins
  (`neo_version_range`)
- Java **21**
- Build system: ModDevGradle

When unsure of an API, check the NeoForge 1.21.1 docs or the decompiled game sources
(`build/moddev/artifacts/neoforge-*-sources.jar`). Don't guess.

## Server side only

Players join with Vis Mod alone, so this mod must never need anything on the client:

- No new items, blocks, entities, menus types, particles, sounds, data components, attachments that sync, or network
  payloads. A vanilla client would not know them and could not join.
- The hotbar menu item is a vanilla item marked with `minecraft:custom_data`. Menus are vanilla chest screens
  (`ChestMenu`) whose slots can't be taken from. The HUD is vanilla: scoreboard sidebar, boss bars, titles, action bar,
  chat.
- Config is `ModConfig.Type.COMMON` (SERVER configs are synced to clients). Maps and stats are world `SavedData`.
- `neoforge.mods.toml` sets `displayTest="IGNORE_SERVER_VERSION"`.

## Working with Vis Mod

- Teams are vanilla scoreboard teams. Vis Mod's `com.yagah.vismod.team.VisAllies` spares allies while a team's
  friendly fire is off; this mod only makes teams, puts players on them and sets friendly fire.
- Between rounds, call `com.yagah.vismod.team.VisRound.reset(player)`.
- If the arena needs something new from Vis Mod, add a small, general hook there (in its `.team` package), build its
  jar, and say so: both mods then need updating on the server, and Vis Mod on every client.
- The dependency is the Vis Mod jar at `vismod_jar` in `gradle.properties`. Rebuild Vis Mod (`.\gradlew.bat build`
  there) before building or running this one after any Vis Mod change.

## Architecture rules

- **The server is authoritative**, as in Vis Mod. Every menu click is checked against the match state before it acts.
- **One match engine, modes plug in.** The engine owns the flow (waiting, countdown, playing, ended) and players; each
  mode decides scoring, teams, spawns and the win condition.
- **Everything tunable is config** (times, score limits, player counts), not hardcoded numbers.

## Package layout

The mod id is `visarena`, the display name is "Vis Arena", the base package is `com.yagah.visarena` and the main class
is `VisArena`.

| Package | Holds |
| --- | --- |
| `.map` | Arena maps (spawns, regions, flags, cores), their SavedData and setup commands |
| `.match` | The match engine: states, players, queue, countdown, end of match |
| `.mode` | One class per game mode (FFA, TDM, Duel, Core Siege) |
| `.lobby` | Lobby spawn, the hotbar menu item and the chest menus |
| `.hud` | Sidebar, boss bars, titles |
| `.stats` | Players' records per mode (SavedData) for the leaderboard |
| `.command` | `/arena` commands |

## Conventions

- Register event handlers with `@EventBusSubscriber(modid = VisArena.MODID)` and leave out `bus`.
- Player-facing text uses `Component.literal` (there is no client lang file, since clients don't have this mod).

## Workflow

- Work one milestone at a time (see "Milestones" in the design doc). Don't start the next one until asked.
- Build with `.\gradlew.bat build`. Fix every compile error before you call a task finished.
- Test: `.\gradlew.bat runServer` here, then `.\gradlew.bat runClient` and `.\gradlew.bat runClient2` in the Vis Mod
  project (they load Vis Mod only, as players will) and join `localhost`.
