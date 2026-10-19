package com.yagah.visarena.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.yagah.visarena.ArenaConfig;
import com.yagah.visarena.lobby.ModeMenu;
import com.yagah.visarena.lobby.TeamMenu;
import com.yagah.visarena.map.ArenaData;
import com.yagah.visarena.map.ArenaMap;
import com.yagah.visarena.map.TeamColor;
import com.yagah.visarena.mode.ArenaMode;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * One queue per mode, in joining order. Once a queue holds the mode's fewest players and a map for it is free, a
 * countdown runs (shortened once the queue is full); at zero, the first players in line (up to the most a match takes)
 * start a match. Leaving, logging out or falling below the minimum stops the countdown.
 */
public final class Queues {
    private static final Map<ArenaMode, Set<UUID>> QUEUES = new EnumMap<>(ArenaMode.class);
    /** Ticks left on each counting-down queue. */
    private static final Map<ArenaMode, Integer> COUNTDOWNS = new EnumMap<>(ArenaMode.class);
    /** Sides picked on the team screen, by player (team modes only; undecided players have none). */
    private static final Map<UUID, TeamColor> PICKS = new HashMap<>();
    /** When each map last had a match start (game time), for rotating through them. Forgotten on restart. */
    private static final Map<String, Long> LAST_PLAYED = new HashMap<>();
    /** Seconds at which the countdown is announced. */
    private static final Set<Integer> ANNOUNCE = Set.of(10, 5, 3, 2, 1);

    static {
        for (ArenaMode mode : ArenaMode.values()) {
            QUEUES.put(mode, new LinkedHashSet<>());
        }
    }

    private Queues() {
    }

    public static Optional<ArenaMode> queuedFor(UUID player) {
        return QUEUES.entrySet().stream().filter(entry -> entry.getValue().contains(player)).map(Map.Entry::getKey).findFirst();
    }

    public static int size(ArenaMode mode) {
        return QUEUES.get(mode).size();
    }

    /** Seconds until the mode's next match starts, if counting down. */
    public static Optional<Integer> secondsLeft(ArenaMode mode) {
        return Optional.ofNullable(COUNTDOWNS.get(mode)).map(ticks -> (ticks + 19) / 20);
    }

    /** Joins the mode's queue (leaving any other), or leaves it if already in. True if now queued. */
    public static boolean toggle(ServerPlayer player, ArenaMode mode) {
        boolean wasIn = QUEUES.get(mode).contains(player.getUUID());
        leave(player.getUUID());
        if (!wasIn) {
            QUEUES.get(mode).add(player.getUUID());
        }
        ModeMenu.refreshAll(player.server);
        TeamMenu.refreshAll(player.server);
        if (!wasIn && picking(player.getUUID())) {
            // Joined a team countdown already under way: straight to the team screen.
            TeamMenu.open(player);
        }
        return !wasIn;
    }

    public static void leave(UUID player) {
        QUEUES.values().forEach(queue -> queue.remove(player));
        PICKS.remove(player);
    }

    // ---- Team picks ----

    /** True while the player is queued for a team mode that is counting down: the team screen is open to them. */
    public static boolean picking(UUID player) {
        return queuedFor(player).filter(mode -> mode.teams() && COUNTDOWNS.containsKey(mode)).isPresent();
    }

    public static Optional<TeamColor> pick(UUID player) {
        return Optional.ofNullable(PICKS.get(player));
    }

    /** The players queued for the mode who will play its next match (the first in line, up to its most), in order. */
    public static List<UUID> nextPlayers(ArenaMode mode) {
        return QUEUES.get(mode).stream().limit(ArenaConfig.maxPlayers(mode)).toList();
    }

    /** How many of the next match's players have picked this side. */
    public static long picked(ArenaMode mode, TeamColor team) {
        return nextPlayers(mode).stream().filter(id -> PICKS.get(id) == team).count();
    }

    /** The most players one side may have in the mode's next match. */
    public static int sideCap(ArenaMode mode) {
        return TeamDeal.cap(nextPlayers(mode).size());
    }

    /**
     * Picks a side on the team screen (or, picking the same side again, goes back to undecided). Refused with a reason
     * if that side already holds half the players.
     */
    public static Optional<String> choose(ServerPlayer player, TeamColor team) {
        Optional<ArenaMode> mode = queuedFor(player.getUUID()).filter(ArenaMode::teams);
        if (mode.isEmpty()) {
            return Optional.of("You're not queued for a team mode");
        }
        if (PICKS.get(player.getUUID()) == team) {
            PICKS.remove(player.getUUID());
        } else if (picked(mode.get(), team) >= sideCap(mode.get())) {
            return Optional.of(team.displayName() + " is full");
        } else {
            PICKS.put(player.getUUID(), team);
        }
        TeamMenu.refreshAll(player.server);
        return Optional.empty();
    }

    /** The picks of these players, for dealing the teams. */
    static Map<UUID, TeamColor> picksOf(List<ServerPlayer> players) {
        Map<UUID, TeamColor> picks = new HashMap<>();
        players.forEach(player -> pick(player.getUUID()).ifPresent(team -> picks.put(player.getUUID(), team)));
        return picks;
    }

    static void clear() {
        QUEUES.values().forEach(Set::clear);
        COUNTDOWNS.clear();
    }

    /** True if some map that hosts the mode is free. Checked every tick while a queue is ready, so it copies nothing. */
    private static boolean hasFreeMap(MinecraftServer server, ArenaMode mode) {
        return ArenaData.get(server).maps().stream().anyMatch(map -> map.hosts(mode) && !Matches.inUse(map));
    }

    /**
     * A free map that hosts the mode: the one played least recently (never played counts as longest ago), random among
     * ties, so the maps take turns.
     */
    public static Optional<ArenaMap> freeMap(MinecraftServer server, ArenaMode mode) {
        List<ArenaMap> free = new ArrayList<>(ArenaData.get(server).maps().stream()
                .filter(map -> map.hosts(mode) && !Matches.inUse(map)).toList());
        Collections.shuffle(free);
        return free.stream().min(Comparator.comparingLong(map -> LAST_PLAYED.getOrDefault(map.name(), 0L)));
    }

    /** Records a map as just played, for the rotation. */
    static void played(ArenaMap map, long gameTime) {
        LAST_PLAYED.put(map.name(), gameTime);
    }

    /** True if the mode's queue is full enough to start but every map for it is in use. */
    public static boolean waitingForMap(MinecraftServer server, ArenaMode mode) {
        return mode.playable() && size(mode) >= ArenaConfig.minPlayers(mode) && !hasFreeMap(server, mode)
                && ArenaData.get(server).maps().stream().anyMatch(map -> map.hosts(mode));
    }

    /** The queued players still online and not already playing, in order. */
    private static List<ServerPlayer> waiting(MinecraftServer server, ArenaMode mode) {
        Set<UUID> queue = QUEUES.get(mode);
        queue.removeIf(id -> !(server.getPlayerList().getPlayer(id) instanceof ServerPlayer player) || Matches.inMatch(player));
        return queue.stream().map(id -> server.getPlayerList().getPlayer(id)).toList();
    }

    static void tick(MinecraftServer server) {
        boolean changed = false;
        for (ArenaMode mode : ArenaMode.values()) {
            changed |= tick(server, mode);
        }
        if (changed || server.getTickCount() % 20 == 0) {
            ModeMenu.refreshAll(server);
            TeamMenu.refreshAll(server);
        }
    }

    /** True if the queue's countdown started, stopped or ended. */
    private static boolean tick(MinecraftServer server, ArenaMode mode) {
        List<ServerPlayer> waiting = waiting(server, mode);
        Integer ticks = COUNTDOWNS.get(mode);
        boolean ready = mode.playable() && waiting.size() >= ArenaConfig.minPlayers(mode) && hasFreeMap(server, mode);
        if (!ready) {
            if (ticks != null) {
                COUNTDOWNS.remove(mode);
                announce(waiting, Component.literal(mode.displayName() + " countdown stopped: waiting for players").withStyle(ChatFormatting.YELLOW));
                return true;
            }
            return false;
        }
        // Team modes count down longer and never shorten, leaving time to pick sides.
        int full = mode.teams() ? Integer.MAX_VALUE : ArenaConfig.ticks(ArenaConfig.FULL_COUNTDOWN_SECONDS);
        if (ticks == null) {
            ticks = mode.teams() ? ArenaConfig.ticks(ArenaConfig.TEAM_COUNTDOWN_SECONDS)
                    : waiting.size() >= ArenaConfig.maxPlayers(mode) ? full : ArenaConfig.ticks(ArenaConfig.COUNTDOWN_SECONDS);
            COUNTDOWNS.put(mode, ticks);
            announce(waiting, Component.literal(mode.displayName() + " starts in " + (ticks / 20) + " s"
                    + (mode.teams() ? ": pick your team" : "")).withStyle(ChatFormatting.GREEN));
            if (mode.teams()) {
                waiting.forEach(TeamMenu::open);
            }
            return true;
        }
        if (waiting.size() >= ArenaConfig.maxPlayers(mode) && ticks > full) {
            ticks = full;
        }
        ticks--;
        if (ticks <= 0) {
            COUNTDOWNS.remove(mode);
            List<ServerPlayer> players = waiting.subList(0, Math.min(waiting.size(), ArenaConfig.maxPlayers(mode)));
            freeMap(server, mode).ifPresent(map -> Matches.start(server, mode, map, List.copyOf(players)));
            return true;
        }
        COUNTDOWNS.put(mode, ticks);
        if (ticks % 20 == 0 && ANNOUNCE.contains(ticks / 20)) {
            for (ServerPlayer player : waiting) {
                player.displayClientMessage(Component.literal(mode.displayName() + " starts in " + (ticks / 20)).withStyle(ChatFormatting.GREEN), true);
                player.playNotifySound(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.MASTER, 1.0f, 1.2f);
            }
        }
        return false;
    }

    /**
     * Starts the mode's match now with whoever is queued (at least one), for testing. Returns the reason it couldn't,
     * or empty on success.
     */
    public static Optional<String> startNow(MinecraftServer server, ArenaMode mode, Optional<ArenaMap> chosen) {
        if (!mode.playable()) {
            return Optional.of(mode.displayName() + " isn't playable yet");
        }
        List<ServerPlayer> waiting = waiting(server, mode);
        if (waiting.isEmpty()) {
            return Optional.of("Nobody is queued for " + mode.displayName());
        }
        Optional<ArenaMap> map = chosen.isPresent() ? chosen : freeMap(server, mode);
        if (map.isEmpty() || !map.get().hosts(mode)) {
            return Optional.of("No map ready for " + mode.displayName());
        }
        if (Matches.inUse(map.get())) {
            return Optional.of("Map " + map.get().name() + " is in use");
        }
        COUNTDOWNS.remove(mode);
        Matches.start(server, mode, map.get(), List.copyOf(waiting.subList(0, Math.min(waiting.size(), ArenaConfig.maxPlayers(mode)))));
        return Optional.empty();
    }

    private static void announce(List<ServerPlayer> players, Component message) {
        players.forEach(player -> player.sendSystemMessage(message));
    }
}
