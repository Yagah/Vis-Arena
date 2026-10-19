# Vis Arena Design Doc (NeoForge 1.21.1)

## Overview

Vis Arena turns a Vis Mod server into a minigame server. Players wait in a lobby, open a menu from a hotbar item, queue
for a mode, and are sent into an arena map when enough players are ready. Modes:

- **FFA** (free-for-all): everyone for themselves; most kills wins.
- **TDM** (team deathmatch): two teams; most team kills wins.
- **1v1** (duel): two players, best of several short rounds.
- **Core Siege**: two teams take turns, one half each, attacking one core while the other defends. Break it faster
  than the other team did, or do more damage to it if neither breaks it.

The server's world is dedicated to the arenas, so the mod owns everything about players there: their position, game
mode, inventory and health. There is no saving and restoring of survival inventories.

It runs on the server only (see CLAUDE.md, "Server side only").

## Players and the lobby

- On joining, a player is placed at the lobby spawn in adventure mode, at full health and Vis, with an empty inventory
  except the **Arena menu** item (a nether star named "Arena", in the first hotbar slot). It can't be dropped, moved or
  put in a container, and it comes back if lost.
- Nobody in the lobby can be hurt (abilities included), except by the void or `/kill`, which respawn them at the lobby
  spawn.
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
4. **Playing**: the mode keeps score. A sidebar shows the scores; a boss bar shows the time left (and, in Core Siege,
   a second one the core's health).
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
| TDM | 4–12 | 2, balanced | first team to 15 kills (2 a side), 20 (3), 30 (4+); else most | 10 min |
| 1v1 | 2 | none | first to 3 rounds | 2 min per round |
| Core Siege | 4–12 | 2, balanced | broke the core faster; else more damage | 4 min per half |

All numbers are config. Ties: FFA and TDM go to sudden death (next kill wins, 60 s,
then a draw); Core Siege has none (see below).

**1v1 rounds.** Each round starts with both players refreshed and frozen on the two spawns farthest apart. A downed
player watches the rest of the round as a spectator (and can't affect anything); the one left standing takes it. When a
round's time runs out, whoever has more health left (as a share of their max) takes it; level on health, or both
down, is a drawn round. A pause (`roundPauseSeconds`, 3) shows the round's result and the score, then the next round's
countdown starts. First to 3 rounds wins; after 9 rounds (drawn ones count) the one ahead wins, or it's a draw.

**TDM and Core Siege teams** are vanilla teams named after their colour (red, blue), made for the match and removed
after, with friendly fire off and name tags shown to the team only.

**Picking teams.** A team mode's countdown is longer (`teamCountdownSeconds`, 20) and doesn't shorten when full. When it
starts, everyone queued gets the **team screen** (and the Arena item opens it while the countdown runs): Red and Blue
buttons, the countdown, and three columns of player heads (red, undecided, blue), live. Clicking a side joins it,
clicking it again goes back to undecided; a side can't take more than half the players, rounded up. When the match
starts, picks are kept (in queue order, while their side has room) and everyone else is placed strongest first, each on
the smaller side (the weaker one on a tie). Strength is a rough rating from the stats: mostly win rate, some kills per
death, pulled toward average for players with few games.

**Underdog bonus.** With an odd number of players one team is a player short. That team gets extra max health (4, two
hearts), extra Vis (1 a second) and, in Core Siege, respawn delays cut to 60%; all three are config. It's checked again
whenever someone leaves, so it moves to whichever team is short.

**Core Siege.** Two halves on one core. In the first, red attacks and blue defends; in the second they swap.

- **The core** is a vanilla shulker (no AI, glowing, in the defenders' colour, on the defending team) standing in the
  map's core zone. It can't be moved or killed: hits on it become damage to the core's own health (`coreHealth`, 400),
  shown on its own boss bar. Defenders' abilities pass it by (it's their ally, through Vis Mod's ally check).
- **Shielded while defended**: while any defender stands in the core zone, the core takes no damage. Attackers must
  clear or pull the defenders out, then burst it while it's exposed. The action bar tells each side which.
- **Shockwaves**: as it falls past 75%, 50% and 25%, the core blasts attackers near it away and is immune for 2 s.
- **Regeneration**: with no attacker near the zone for 10 s, the core heals slowly (1% a second), but never back past
  the last shockwave mark it fell below.
- **Home advantage**: defenders near their core regain Vis faster (2 a second).
- **Moving the core**: attackers' moving abilities (Sphinx's cube, pushes, pulls) can take it for a moment, but it's
  put back in place every tick, and while held it can't be hit, so it only costs the attackers time. Defenders' can't
  touch it. Left as is on purpose (2026-10-19); an "immovable" entity tag in Vis Mod would stop it if ever needed.
- **Respawning**: a downed player watches as a spectator for a fixed delay (5 s, each side its own setting), then
  respawns at their side's spawns.
- **A half ends** when the core breaks or its time (4 min) runs out. The attackers' score is their time to break it,
  or, if the defenders held, the share of the core's health they took.
- **Winning**: a team that broke the core beats one that didn't; if both did, the faster one; if neither, the one that
  did more damage; level is a draw. The second half ends early once it's settled: when the second attackers pass the
  first team's time without breaking it, or pass their damage when the first team didn't break it.

## Maps

An arena map is a named area in the world with:

- **Modes** it supports.
- **Spawns**: points with a facing, either free (FFA, Duel) or per team (red, blue).
- **Floor height**: below it is a void death.
- **Core zone** (Core Siege only): a box; the core stands on its floor, in the middle. Core Siege uses its own
  spawns, marked as attacker or defender spawns.
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
- `/arena map <name> spawn add [red|blue|attack|defend]` (where you stand and your facing; plain spawns are for FFA
  and 1v1, red and blue for TDM, attack and defend for Core Siege), `/arena map <name> spawn clear`.
- `/arena map <name> floor <y|none>`, `/arena map <name> core <from> <to>` (the core zone), `core clear`.
- `/arena map <name> check` (everything marked, and what each mode still needs), `/arena map <name> tp` (to its first
  spawn).
- `/arena start <mode> [map]`: start a match now from that mode's queue (for testing with few players).
- `/arena stop`: end every match, no winner.

## HUD

All vanilla, so players need nothing:

- **Sidebar**: mode name, time left and the top scores. Sent with vanilla scoreboard packets to that match's players
  only, so two matches at once each see their own.
- **Boss bar**: time left; in Core Siege a second bar shows the core's health and whether it's shielded.
- **Titles**: countdown, "Fight!", kills ("You eliminated X"), the winner.
- **Action bar**: your kills and deaths in FFA/TDM; "Core shielded" or "Core exposed" in Core Siege.
- **Lobby**: the menu item, and a chat line when a queue starts counting down.

## Stats

Every finished match (not a stopped one) adds to each fighter's record for that mode: one game, a win if they were among
the winners, their kills and deaths. Leavers count, as a game not won. Records are saved with the world. The mode menu's
book opens **Stats and leaderboard**: for all modes together and for each mode, your record and the five players with
the most wins (then kills).

## Config

A COMMON config, `config/visarena-common.toml`: countdown times, freeze time, spawn protection, the end screen time,
and each mode's player counts, score limit and time limit (per round in 1v1, per half in Core Siege). Core Siege: core
health and a damage multiplier, respawn delay per side, regeneration delay and rate, shockwave radius, strength and
immunity, and the defenders' Vis bonus and its radius. Characters are locked during matches (Vis Mod's
`CharacterChangeEvent` is refused), so a player must have one before they can queue.

## Milestones

1. **Maps and lobby.** Map SavedData and the `/arena map` and `/arena lobby` commands; on join, players go to the lobby
   in adventure mode with the menu item (kept in place); the mode menu opens and shows the modes (no queueing yet).
2. **Match engine and FFA.** Queues, countdown, start, deaths and respawns, kills, the end screen and return to the
   lobby; FFA scoring; sidebar, boss bar and titles; `/arena start` and `/arena stop`.
3. **TDM.** Match teams, balancing, team spawns, team scores.
4. **1v1.** Rounds, spectating the rest of a round, best of N.
5. **Core Siege.** Halves on one core, the core entity and its health, shielding, shockwaves, regeneration, the Vis
   bonus, timed respawns, its boss bar and win rules.
6. **Polish.** Stats and a leaderboard; map rotation (the least recently played free map, random among ties) and the
   menu's "Waiting for a free map"; no more "moved too quickly" after match teleports. Map voting is left for later.

## Decisions and open questions

- Players choose their character in Vis Mod's menu, not in the arena menu; switching between matches needs the
  `visCharacterSwapping` gamerule on.
- Vis Mod's abilities stay as they are in every mode (no per-mode bans) unless a test shows a problem.
