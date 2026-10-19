# Vis Arena Design Doc (NeoForge 1.21.1)

## Overview

Vis Arena turns a Vis Mod server into a minigame server. Players wait in a lobby, open a menu from a hotbar item, queue
for a mode, and are sent into an arena map when enough players are ready. Modes:

- **FFA** (free-for-all): everyone for themselves; most kills wins.
- **TDM** (team deathmatch): two teams; most team kills wins.
- **1v1** (duel): two players, best of several short rounds.
- **Core Siege**: two teams, each defending a core. Standing in the enemy's core zone, uncontested, drains its hit
  points. Destroy theirs first, or have more left when time runs out.

The server's world is dedicated to the arenas, so the mod owns everything about players there: their position, game
mode, inventory and health. There is no saving and restoring of survival inventories.

It runs on the server only (see CLAUDE.md, "Server side only").

## Players and the lobby

- On joining, a player is placed at the lobby spawn in adventure mode, at full health and Vis, with an empty inventory
  except the **Arena menu** item (a nether star named "Arena", in the first hotbar slot). It can't be dropped, moved or
  put in a container, and it comes back if lost.
- Using the item (right click) opens the **mode menu**: a chest screen with one icon per mode showing its name, how many
  are queued, and the players needed to start. Clicking queues you; clicking again (or the barrier icon) leaves.
  A player is in at most one queue.
- Players pick their Vis character from Vis Mod's own menu (K) as usual. Character switching follows Vis Mod's
  `visCharacterSwapping` gamerule, which the server should turn on so players can change between matches (Vis Mod still
  refuses switches during combat).

## Matches

Each match runs on one arena map that supports its mode and isn't in use. One flow for every mode:

1. **Waiting**: the queue fills. Once it reaches the mode's minimum, the countdown starts.
2. **Countdown** (15 s, shortened to 5 s once the queue reaches the mode's maximum): queued players can still leave.
   If the queue falls below the minimum, it goes back to waiting. A sound and a chat line mark 10, 5, 3, 2, 1.
3. **Starting**: players are teleported to their spawns, put in adventure mode, healed, given a round reset
   (`VisRound.reset`), and frozen for 3 s while a title counts down ("3", "2", "1", "Fight!").
4. **Playing**: the mode keeps score. A sidebar shows the scores; a boss bar shows the time left (or, in Core Siege,
   the cores' hit points).
5. **Ended**: a title shows the winner to everyone in the match, with a chat summary (scores and top players). After
   10 s everyone goes back to the lobby with a round reset, and the map is free again.

A player who leaves the server mid-match is removed from it. If a mode can no longer be won (one team empty, a duel
opponent gone), the match ends and the remaining side wins.

### Deaths

Death in a match never shows the death screen or drops anything. When a player would die, the damage is cancelled
instead: they are healed, given a round reset, and respawn as the mode says (FFA and TDM: at once, at the spawn point
farthest from enemies, with 3 s of spawn protection; Duel: they sit out the rest of the round as a spectator; Core
Siege: after 5 s). The kill goes to whoever dealt the killing blow, following Vis Mod's owner chain
(`VisAllies.owner`), so a Glass wolf's kill is Glass's; with no attacker it counts as a death without a kill (a fall, or
the void).

Falling below the map's floor height counts as a death (void arenas).

## Modes

| Mode | Players | Teams | Wins when | Time limit |
| --- | --- | --- | --- | --- |
| FFA | 2–12 | none | first to 15 kills; else most kills | 8 min |
| TDM | 4–12 | 2, balanced | first team to 30 kills; else most | 10 min |
| 1v1 | 2 | none | first to 3 rounds | 2 min per round |
| Core Siege | 4–12 | 2, balanced | enemy core destroyed; else most core HP | 12 min |

All numbers are config. Ties: FFA and TDM go to sudden death (next kill wins, 60 s, then a draw); Core Siege compares
team kills, then draws.

**TDM and Core Siege teams** are vanilla teams named after their colour (red, blue), made for the match and removed
after, with friendly fire off and name tags shown to the team only. Players are dealt into teams by queue order,
alternating; parties are a later idea.

**Core Siege.** Each team's core is a zone (a box) set on the map, with hit points (default 1000). Every second, if the
zone holds attackers and no defenders, the core loses 10 HP per attacker (up to 3). Defenders inside stop the drain
("contested"), shown on the boss bar. The cores' HP are the two boss bars.

## Maps

An arena map is a named area in the world with:

- **Modes** it supports.
- **Spawns**: points with a facing, either free (FFA, Duel) or per team (red, blue).
- **Floor height**: below it is a void death.
- **Cores** (Core Siege only): one box per team.
- **Bounds** (optional): leaving them counts as a death.

Maps are built by hand in the world and marked with operator commands; they are stored in the world's SavedData. A map
is ready when it has what each of its modes needs (at least two spawns, a spawn per team, both cores), and `/arena map
check` says what's missing.

## Commands

All `/arena` commands need operator level 2.

- `/arena lobby set`: the lobby spawn (where you stand, and your facing).
- `/arena build`: builder mode on or off. A builder is in creative and the lobby leaves them alone (no teleports, no
  menu item, no game mode resets), for building and marking maps. Turning it off sends them to the lobby.
- `/arena map create <name>` (in the dimension you're in), `/arena map delete <name>`, `/arena map list` (each map's
  modes, green when ready).
- `/arena map <name> mode add|remove <mode>` (modes: `ffa`, `tdm`, `duel`, `core_siege`).
- `/arena map <name> spawn add [red|blue]` (where you stand and your facing), `/arena map <name> spawn clear`.
- `/arena map <name> floor <y|none>`, `/arena map <name> core <red|blue> <from> <to>`.
- `/arena map <name> check` (everything marked, and what each mode still needs), `/arena map <name> tp` (to its first
  spawn).
- `/arena start <mode> [map]`: start a match now from that mode's queue (for testing with few players).
- `/arena stop`: end every match, no winner.

## HUD

All vanilla, so players need nothing:

- **Sidebar**: mode name, time left and the top scores. Sent with vanilla scoreboard packets to that match's players
  only, so two matches at once each see their own.
- **Boss bar**: time left, or the cores' HP in Core Siege.
- **Titles**: countdown, "Fight!", kills ("You eliminated X"), the winner.
- **Action bar**: your kills and deaths in FFA/TDM; "Contesting the core" in Core Siege.
- **Lobby**: the menu item, and a chat line when a queue starts counting down.

## Config

A COMMON config, `config/visarena-common.toml`: countdown times, freeze time, spawn protection, the end screen time,
and each mode's player counts, score limit and time limit. Core Siege: core HP, drain per attacker, attacker cap,
respawn delay.

## Milestones

1. **Maps and lobby.** Map SavedData and the `/arena map` and `/arena lobby` commands; on join, players go to the lobby
   in adventure mode with the menu item (kept in place); the mode menu opens and shows the modes (no queueing yet).
2. **Match engine and FFA.** Queues, countdown, start, deaths and respawns, kills, the end screen and return to the
   lobby; FFA scoring; sidebar, boss bar and titles; `/arena start` and `/arena stop`.
3. **TDM.** Match teams, balancing, team spawns, team scores.
4. **1v1.** Rounds, spectating the rest of a round, best of N.
5. **Core Siege.** Core zones, HP and draining, contesting, its boss bars and win rules.
6. **Polish.** Stats (wins, kills) kept per player, a leaderboard in the menu, map voting.

## Decisions and open questions

- Players choose their character in Vis Mod's menu, not in the arena menu; switching between matches needs the
  `visCharacterSwapping` gamerule on.
- Vis Mod's abilities stay as they are in every mode (no per-mode bans) unless a test shows a problem.
